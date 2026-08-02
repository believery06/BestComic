@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.mangareader.bookshelf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangareader.Constants
import com.mangareader.R
import com.mangareader.data.*
import com.mangareader.utils.WebDavConfig
import com.mangareader.utils.WebDavConfigManager
import com.mangareader.utils.WebDavSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ContactAuthorDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.ContactMail, contentDescription = stringResource(R.string.bookshelf_contact_author)) },
        title = { Text(stringResource(R.string.bookshelf_contact_author_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.bookshelf_author_contact_label))
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        Constants.AUTHOR_CONTACT,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.bookshelf_author_contact_clip_label), Constants.AUTHOR_CONTACT))
                Toast.makeText(context, context.getString(R.string.settings_contact_copied_toast), Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.bookshelf_copy_label)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookshelf_close_label)) }
        }
    )
}

@Composable
fun ReadingStatsDialog(
    comics: List<ComicEntry>,
    progressMap: Map<String, Pair<Int, Int>>,
    stats: ReadingStats,
    extras: Map<String, ComicExtras>,
    lists: List<ReadingList>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val started = progressMap.count { it.value.first > 0 }
    val finished = progressMap.count { it.value.second > 0 && it.value.first >= it.value.second - 1 }
    val totalPages = progressMap.values.sumOf { it.first.coerceAtLeast(0) } + stats.totalPagesRead
    val reviewed = extras.count { it.value.review.isNotBlank() }
    val unlocked = Achievement.entries.filter { achievement ->
        when (achievement) {
            Achievement.FIRST_COMIC -> started >= 1
            Achievement.STREAK_7 -> stats.streakDays() >= 7
            Achievement.HUNDRED_COMICS -> comics.size >= 100
            Achievement.NIGHT_OWL -> stats.nightOwlUnlocked
            Achievement.TEN_THOUSAND_PAGES -> totalPages >= 10000
            Achievement.FIRST_REVIEW -> reviewed >= 1
            Achievement.FIRST_LIST -> lists.isNotEmpty()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookshelf_reading_stats_and_achievements)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.bookshelf_total_comics, comics.size), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.bookshelf_started_reading, started))
                Text(stringResource(R.string.bookshelf_finished_reading, finished))
                Text(stringResource(R.string.bookshelf_total_pages_flipped, totalPages))
                Text(stringResource(R.string.bookshelf_streak_days, stats.streakDays()))
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.bookshelf_achievement_badges), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Achievement.entries.forEach { ach ->
                        val isUnlocked = unlocked.contains(ach)
                        FilterChip(
                            selected = isUnlocked,
                            onClick = {},
                            label = { Text("${ach.iconEmoji} ${ach.displayTitle(context)}") },
                            enabled = false,
                            leadingIcon = if (isUnlocked) {
                                { Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.bookshelf_achievement_unlocked), modifier = Modifier.size(18.dp)) }
                            } else null
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.bookshelf_stats_note),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookshelf_close_label)) }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingListsDialog(
    comics: List<ComicEntry>,
    onDismiss: () -> Unit,
    repository: SettingsRepository,
    scope: CoroutineScope
) {
    val lists by repository.getReadingLists().collectAsStateWithLifecycle(initialValue = emptyList())
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }
    var selectedList by remember { mutableStateOf<ReadingList?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookshelf_my_reading_lists)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                if (lists.isEmpty()) {
                    Text(stringResource(R.string.bookshelf_no_reading_lists))
                }
                LazyColumn {
                    items(lists, key = { it.id }) { list ->
                        ListItem(
                            headlineContent = { Text(list.name) },
                            supportingContent = { Text(stringResource(R.string.bookshelf_reading_list_items_format, list.items.size, list.description)) },
                            trailingContent = {
                                IconButton(onClick = { selectedList = list }) {
                                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.bookshelf_add_comic_to_list))
                                }
                            },
                            modifier = Modifier.clickable { selectedList = list }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showCreate = true }) { Text(stringResource(R.string.bookshelf_create_label)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookshelf_close_label)) }
        }
    )

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.bookshelf_create_list_title)) },
            text = {
                Column {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text(stringResource(R.string.bookshelf_list_name_label)) }, singleLine = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = newDesc, onValueChange = { newDesc = it }, label = { Text(stringResource(R.string.bookshelf_list_description_label)) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            val updated = lists + ReadingList(name = newName.trim(), description = newDesc.trim())
                            repository.saveReadingLists(updated)
                        }
                        newName = ""
                        newDesc = ""
                        showCreate = false
                    }
                }) { Text(stringResource(R.string.bookshelf_create_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    selectedList?.let { list ->
        AlertDialog(
            onDismissRequest = { selectedList = null },
            title = { Text(stringResource(R.string.bookshelf_add_to_list_title, list.name)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(comics, key = { it.uri.toString() }) { comic ->
                        val already = list.items.any { it.uriString == comic.uri.toString() }
                        TextButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    repository.addComicToList(list.id, comic)
                                }
                                selectedList = null
                            },
                            enabled = !already,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (already) stringResource(R.string.bookshelf_already_added, comic.title) else comic.title)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedList = null }) { Text(stringResource(R.string.bookshelf_close_label)) }
            }
        )
    }
}

@Composable
fun WebDavSyncDialog(
    progressMap: Map<String, Pair<Int, Int>>,
    bookmarks: Map<String, List<Int>>,
    favorites: Set<String>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember { WebDavConfigManager(context) }
    var config by remember { mutableStateOf(manager.getConfig()) }
    var status by remember { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun applyMergedData(merged: com.mangareader.utils.SyncData) {
        val repo = SettingsRepository.getInstance(context)
        for ((uri, page) in merged.progress) {
            runCatching {
                val u = android.net.Uri.parse(uri)
                repo.saveProgress(u, page)
                merged.totalPages[uri]?.let { repo.saveTotalPages(u, it) }
            }
        }
        for ((uri, pages) in merged.bookmarks) {
            runCatching {
                val u = android.net.Uri.parse(uri)
                pages.forEach { repo.addBookmark(u, it) }
            }
        }
        val currentFavorites = repo.getFavorites().first()
        val toAdd = merged.favorites subtract currentFavorites
        for (uri in toAdd) {
            runCatching { repo.toggleFavorite(android.net.Uri.parse(uri)) }
        }
    }

    fun doSync(directionKey: String) {
        if (isSyncing) return
        isSyncing = true
        manager.saveConfig(config)
        scope.launch(Dispatchers.IO) {
            val repo = SettingsRepository.getInstance(context)
            val directionLabel = when (directionKey) {
                "upload" -> context.getString(R.string.bookshelf_webdav_upload)
                "download" -> context.getString(R.string.bookshelf_webdav_download)
                "bidirectional" -> context.getString(R.string.bookshelf_webdav_bidirectional)
                else -> context.getString(R.string.bookshelf_webdav_unknown_direction)
            }
            status = context.getString(R.string.bookshelf_webdav_syncing_prefix, directionLabel)
            val progressTimes = try {
                repo.getRecentReads().first()
            } catch (_: Exception) {
                emptyMap<String, Long>()
            }
            val local = com.mangareader.utils.SyncData(
                progress = progressMap.mapValues { it.value.first },
                totalPages = progressMap.mapValues { it.value.second },
                bookmarks = bookmarks,
                favorites = favorites,
                progressTimes = progressTimes
            )
            val result = when (directionKey) {
                "upload" -> WebDavSync.uploadSyncData(
                    config,
                    local.progress,
                    local.totalPages,
                    local.bookmarks,
                    local.favorites
                )
                "download" -> WebDavSync.downloadSyncData(config).map { remote ->
                    applyMergedData(remote)
                    Unit
                }
                "bidirectional" -> WebDavSync.downloadSyncData(config).map { remote ->
                    val merged = WebDavSync.mergeSyncData(local, remote)
                    applyMergedData(merged)
                    WebDavSync.uploadSyncData(
                        config,
                        merged.progress,
                        merged.totalPages,
                        merged.bookmarks,
                        merged.favorites,
                        merged.progressTimes
                    )
                }.map { }
                else -> Result.failure(Exception(context.getString(R.string.bookshelf_webdav_unknown_direction)))
            }
            status = result.fold(
                onSuccess = { context.getString(R.string.bookshelf_webdav_result_success, directionLabel) },
                onFailure = { context.getString(R.string.bookshelf_webdav_result_failure, it.message ?: "") }
            )
            isSyncing = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSyncing) onDismiss() },
        title = { Text(stringResource(R.string.bookshelf_webdav_sync_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = config.serverUrl,
                    onValueChange = { config = config.copy(serverUrl = it) },
                    label = { Text(stringResource(R.string.bookshelf_server_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = config.username,
                    onValueChange = { config = config.copy(username = it) },
                    label = { Text(stringResource(R.string.bookshelf_username_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                var passwordVisible by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = config.password,
                    onValueChange = { config = config.copy(password = it) },
                    label = { Text(stringResource(R.string.bookshelf_password_label)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        val icon = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(icon, contentDescription = if (passwordVisible) stringResource(R.string.bookshelf_password_hide) else stringResource(R.string.bookshelf_password_show))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                var intervalExpanded by remember { mutableStateOf(false) }
                val intervalOptions = listOf(
                    0 to stringResource(R.string.bookshelf_sync_manual),
                    15 to stringResource(R.string.bookshelf_sync_15min),
                    30 to stringResource(R.string.bookshelf_sync_30min),
                    60 to stringResource(R.string.bookshelf_sync_hourly),
                    360 to stringResource(R.string.bookshelf_sync_6hours),
                    720 to stringResource(R.string.bookshelf_sync_12hours),
                    1440 to stringResource(R.string.bookshelf_sync_daily)
                )
                ExposedDropdownMenuBox(
                    expanded = intervalExpanded,
                    onExpandedChange = { intervalExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = intervalOptions.find { it.first == config.syncInterval }?.second ?: stringResource(R.string.bookshelf_sync_manual),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.bookshelf_auto_sync_interval_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = intervalExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = intervalExpanded,
                        onDismissRequest = { intervalExpanded = false }
                    ) {
                        intervalOptions.forEach { (minutes, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    config = config.copy(syncInterval = minutes)
                                    intervalExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.bookshelf_webdav_desc),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        manager.saveConfig(config)
                        scope.launch(Dispatchers.IO) {
                            status = context.getString(R.string.bookshelf_webdav_testing)
                            val test = WebDavSync.testConnection(config)
                            status = test.getOrElse { context.getString(R.string.bookshelf_smb_connection_failed, it.message ?: "") }
                        }
                    },
                    enabled = !isSyncing
                ) { Text(stringResource(R.string.bookshelf_webdav_test_label)) }
                TextButton(onClick = { doSync("upload") }, enabled = !isSyncing) { Text(stringResource(R.string.bookshelf_webdav_upload)) }
                TextButton(onClick = { doSync("download") }, enabled = !isSyncing) { Text(stringResource(R.string.bookshelf_webdav_download)) }
                TextButton(onClick = { doSync("bidirectional") }, enabled = !isSyncing) { Text(stringResource(R.string.bookshelf_webdav_bidirectional)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSyncing) { Text(stringResource(R.string.bookshelf_close_label)) }
        }
    )
}



@Composable
fun ThemePickerDialog(
    currentBackground: String,
    currentTexture: BackgroundTexture,
    onApply: (backgroundColor: String, texture: BackgroundTexture) -> Unit,
    onDismiss: () -> Unit
) {
    data class ThemePreset(val nameRes: Int, val color: String, val texture: BackgroundTexture)
    val presets = listOf(
        ThemePreset(R.string.bookshelf_theme_default_black, "#FF000000", BackgroundTexture.NONE),
        ThemePreset(R.string.bookshelf_theme_eye_care_paper, "#FFF5E6C8", BackgroundTexture.KRAFT),
        ThemePreset(R.string.bookshelf_theme_frosted_glass, "#FF1A1A2E", BackgroundTexture.GLASS),
        ThemePreset(R.string.bookshelf_theme_starry_sky, "#FF050510", BackgroundTexture.STARS),
        ThemePreset(R.string.bookshelf_theme_wood, "#FF3E2723", BackgroundTexture.WOOD)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookshelf_theme_picker_title)) },
        text = {
            Column {
                Text(stringResource(R.string.bookshelf_theme_picker_hint))
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = currentBackground == preset.color && currentTexture == preset.texture,
                            onClick = { onApply(preset.color, preset.texture) },
                            label = { Text(stringResource(preset.nameRes)) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookshelf_close_label)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicExtrasDialog(
    comic: ComicEntry,
    initialExtras: ComicExtras,
    allTags: List<String>,
    onSave: (ComicExtras) -> Unit,
    onDismiss: () -> Unit
) {
    var rating by remember { mutableFloatStateOf(initialExtras.rating) }
    var review by remember { mutableStateOf(initialExtras.review) }
    var tagInput by remember { mutableStateOf("") }
    val tags = remember { initialExtras.tags.toMutableStateList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookshelf_rating_and_review_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(comic.title, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.bookshelf_rating_label, rating.toInt()))
                Slider(value = rating, onValueChange = { rating = it }, valueRange = 0f..5f, steps = 4)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = review,
                    onValueChange = { review = it },
                    label = { Text(stringResource(R.string.bookshelf_review_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.bookshelf_tags_label), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    allTags.forEach { tag ->
                        val selected = tags.contains(tag)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (selected) tags.remove(tag) else tags.add(tag)
                            },
                            label = { Text(tag) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    label = { Text(stringResource(R.string.bookshelf_new_tag_label)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            val t = tagInput.trim()
                            if (t.isNotBlank() && !tags.contains(t)) tags.add(t)
                            tagInput = ""
                        }) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.bookshelf_add_tag)) }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(ComicExtras(rating = rating, review = review.trim(), tags = tags.toSortedSet()))
            }) { Text(stringResource(R.string.bookshelf_save_label)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * SMB/NAS 流式阅读配置对话框。
 *
 * 当前实现基于 HTTP Range 请求，可直接读取支持 HTTP 文件服务的 NAS
 * （如群晖 File Station、QNAP、路由器 USB 共享的 nginx/apache 目录、
 * Python `python -m http.server` 等）。
 *
 * 真正的 SMB/CIFS 协议需要引入 jcifs-ng / smbj 等第三方库并处理 NTLM
 * 认证，属于可选进阶依赖，当前版本未内置。
 */
@Composable
fun SmbNasDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("smb_prefs", Context.MODE_PRIVATE) }
    var url by remember { mutableStateOf(prefs.getString("smb_url", "") ?: "") }
    var status by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<com.mangareader.utils.SmbClient.RemoteFileInfo>>(emptyList()) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookshelf_smb_nas_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.bookshelf_smb_nas_url_label)) },
                    placeholder = { Text(stringResource(R.string.bookshelf_smb_nas_url_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.bookshelf_smb_nas_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (files.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.bookshelf_remote_directory, files.size), style = MaterialTheme.typography.titleSmall)
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(files, key = { it.url }) { file ->
                            ListItem(
                                headlineContent = { Text(file.name) },
                                supportingContent = {
                                    Text(
                                        if (file.isDirectory) stringResource(R.string.bookshelf_remote_directory_type_folder)
                                        else if (file.isComic) stringResource(R.string.bookshelf_remote_directory_type_comic)
                                        else stringResource(R.string.bookshelf_remote_directory_type_file)
                                    )
                                },
                                modifier = Modifier.clickable {
                                    if (file.isDirectory) {
                                        scope.launch(Dispatchers.IO) {
                                            status = context.getString(R.string.bookshelf_smb_listing_prefix, file.name)
                                            files = com.mangareader.utils.SmbClient.listDirectory(file.url)
                                            status = if (files.isEmpty()) context.getString(R.string.bookshelf_smb_empty_or_unreachable) else ""
                                        }
                                    } else if (file.isComic) {
                                        // 复制远程地址到剪贴板，用户可在外部下载后导入
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.bookshelf_remote_comic_address_clip_label), file.url))
                                        Toast.makeText(context, context.getString(R.string.bookshelf_copied_address_toast, file.name), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.edit().putString("smb_url", url).apply()
                scope.launch(Dispatchers.IO) {
                    status = context.getString(R.string.bookshelf_smb_testing_connection)
                    val test = com.mangareader.utils.SmbClient.testConnection(url)
                    status = test.getOrElse { context.getString(R.string.bookshelf_smb_connection_failed, it.message ?: "") }
                    if (test.isSuccess) {
                        files = com.mangareader.utils.SmbClient.listDirectory(url)
                    }
                }
            }) { Text(stringResource(R.string.bookshelf_smb_test_browse_label)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookshelf_close_label)) }
        }
    )
}
