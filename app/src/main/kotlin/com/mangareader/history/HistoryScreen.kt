package com.mangareader.history

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangareader.ui.components.CoverAsyncImage
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangareader.R
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicType
import com.mangareader.data.ReadingHistoryEntry
import com.mangareader.data.SettingsRepository
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 阅读历史页面：按最后访问时间倒序展示用户打开过的漫画。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenComic: (ComicEntry) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository.getInstance(context) }
    val history by settings.getReadingHistory().collectAsStateWithLifecycle(initialValue = emptyList())

    var showClearAll by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val filteredHistory = remember(history, searchQuery) {
        if (searchQuery.isBlank()) history
        else history.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            text = { Text(stringResource(R.string.history_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { settings.clearReadingHistory(null) }
                    showClearAll = false
                }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAll = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearching) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.history_search_hint)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search)) },
                            trailingIcon = if (searchQuery.isNotEmpty()) {
                                {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear))
                                    }
                                }
                            } else null
                        )
                        LaunchedEffect(isSearching) {
                            if (isSearching) focusRequester.requestFocus()
                        }
                    } else {
                        Text(stringResource(R.string.history_title))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSearching) {
                            isSearching = false
                            searchQuery = ""
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (!isSearching && history.isNotEmpty()) {
                        IconButton(onClick = { isSearching = true }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search))
                        }
                    }
                    if (history.isNotEmpty()) {
                        IconButton(onClick = { shareHistory(context, history) }) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.history_share_title))
                        }
                    }
                    if (history.isNotEmpty()) {
                        IconButton(onClick = { showClearAll = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (filteredHistory.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, contentDescription = stringResource(R.string.history_title), modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    if (searchQuery.isNotBlank()) {
                        Text(stringResource(R.string.history_no_match_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.history_no_match_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outlineVariant)
                    } else {
                        Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.history_empty_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        } else {
            val today = remember { LocalDate.now() }
            val groupedHistory = remember(filteredHistory, context) {
                filteredHistory.groupBy { entry ->
                    val date = Instant.ofEpochMilli(entry.lastReadAt)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    when {
                        date.isEqual(today) -> context.getString(R.string.history_group_today)
                        date.isEqual(today.minusDays(1)) -> context.getString(R.string.history_group_yesterday)
                        date.isAfter(today.with(java.time.DayOfWeek.MONDAY).minusDays(1)) ->
                            context.getString(R.string.history_group_this_week)
                        date.isAfter(today.withDayOfMonth(1).minusDays(1)) ->
                            context.getString(R.string.history_group_this_month)
                        else -> context.getString(R.string.history_group_earlier)
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                groupedHistory.forEach { (group, entries) ->
                    item(key = group) {
                        Text(
                            text = group,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(entries, key = { it.uriString }) { entry ->
                        HistoryItem(
                            entry = entry,
                            onClick = {
                                val uri = runCatching { Uri.parse(entry.uriString) }.getOrNull()
                                if (uri == null) {
                                    Log.w(TAG, "历史记录 URI 解析失败: ${entry.uriString}")
                                    return@HistoryItem
                                }
                                onOpenComic(ComicEntry(uri = uri, title = entry.title, type = ComicType.UNKNOWN))
                            },
                            onRemove = { scope.launch { settings.clearReadingHistory(entry.uriString) } }
                        )
                    }
                }
            }
        }
    }
}

private const val TAG = "HistoryScreen"

@Composable
private fun HistoryItem(
    entry: ReadingHistoryEntry,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val timeText = remember(entry.lastReadAt) {
        if (entry.lastReadAt <= 0L) context.getString(R.string.history_time_never)
        else {
            val diff = System.currentTimeMillis() - entry.lastReadAt
            when {
                diff < 60_000L -> context.getString(R.string.history_time_just_now)
                diff < 3_600_000L -> context.getString(R.string.history_time_minutes_ago, diff / 60_000)
                diff < 86_400_000L -> context.getString(R.string.history_time_hours_ago, diff / 3_600_000)
                else -> {
                    val fmt = DateTimeFormatter.ofPattern(context.getString(R.string.history_time_date_format), Locale.getDefault())
                    val dt = Instant.ofEpochMilli(entry.lastReadAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    fmt.format(dt)
                }
            }
        }
    }
    val progressText = if (entry.totalPages > 0) "${entry.lastReadPage + 1}/${entry.totalPages}" else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (entry.coverUriString != null) {
            CoverAsyncImage(
                uri = android.net.Uri.parse(entry.coverUriString),
                contentDescription = stringResource(R.string.history_cover_desc, entry.title),
                modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = stringResource(R.string.history_cover_desc, entry.title), modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row {
                if (progressText.isNotEmpty()) {
                    Text(progressText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Text(" · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Text(timeText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.history_remove), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 将阅读历史生成纯文本报告，并通过系统分享面板发送。
 * 为避免报告过长，仅导出最近 50 条记录。
 */
private fun shareHistory(context: android.content.Context, history: List<ReadingHistoryEntry>) {
    val report = buildString {
        appendLine(context.getString(R.string.history_share_header))
        appendLine(context.getString(R.string.history_share_count, history.size))
        appendLine()
        history.take(50).forEachIndexed { index, entry ->
            appendLine("${index + 1}. ${entry.title}")
            if (entry.totalPages > 0) {
                appendLine(
                    "   " + context.getString(
                        R.string.history_share_progress,
                        "${entry.lastReadPage + 1}/${entry.totalPages}"
                    )
                )
            }
            val time = formatHistoryShareTime(context, entry.lastReadAt)
            appendLine("   " + context.getString(R.string.history_share_time, time))
        }
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.history_share_subject))
        putExtra(Intent.EXTRA_TEXT, report)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.history_share_chooser)))
    }
}

private fun formatHistoryShareTime(context: android.content.Context, timestamp: Long): String {
    return if (timestamp <= 0L) context.getString(R.string.history_time_never)
    else {
        val fmt = DateTimeFormatter.ofPattern(context.getString(R.string.history_time_date_format), Locale.getDefault())
        val dt = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime()
        fmt.format(dt)
    }
}
