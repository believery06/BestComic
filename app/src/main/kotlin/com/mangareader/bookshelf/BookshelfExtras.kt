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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangareader.Constants
import com.mangareader.data.*
import com.mangareader.utils.WebDavConfig
import com.mangareader.utils.WebDavConfigManager
import com.mangareader.utils.WebDavSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ContactAuthorDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.ContactMail, contentDescription = null) },
        title = { Text("联系作者") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("作者联系方式：")
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
                clipboard.setPrimaryClip(ClipData.newPlainText("作者联系方式", Constants.AUTHOR_CONTACT))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }) { Text("复制") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
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
        title = { Text("阅读统计与成就") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("书架漫画：${comics.size} 本", style = MaterialTheme.typography.bodyLarge)
                Text("已开始阅读：$started 本")
                Text("已读完：$finished 本")
                Text("累计翻页：$totalPages 页")
                Text("连续阅读：${stats.streakDays()} 天")
                Spacer(modifier = Modifier.height(12.dp))
                Text("成就徽章", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Achievement.entries.forEach { ach ->
                        val isUnlocked = unlocked.contains(ach)
                        FilterChip(
                            selected = isUnlocked,
                            onClick = {},
                            label = { Text("${ach.iconEmoji} ${ach.title}") },
                            enabled = false,
                            leadingIcon = if (isUnlocked) {
                                { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "注：阅读时长、最爱作者/题材等需要云端服务或更复杂的本地埋点，当前版本仅统计翻页与完成数。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
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
        title = { Text("我的书单") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                if (lists.isEmpty()) {
                    Text("还没有书单，点击右下角创建。")
                }
                LazyColumn {
                    items(lists, key = { it.id }) { list ->
                        ListItem(
                            headlineContent = { Text(list.name) },
                            supportingContent = { Text("${list.items.size} 本 | ${list.description}") },
                            trailingContent = {
                                IconButton(onClick = { selectedList = list }) {
                                    Icon(Icons.Default.Add, contentDescription = "添加漫画")
                                }
                            },
                            modifier = Modifier.clickable { selectedList = list }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showCreate = true }) { Text("新建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建书单") },
            text = {
                Column {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("书单名称") }, singleLine = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = newDesc, onValueChange = { newDesc = it }, label = { Text("描述（可选）") }, singleLine = true)
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
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("取消") }
            }
        )
    }

    selectedList?.let { list ->
        AlertDialog(
            onDismissRequest = { selectedList = null },
            title = { Text("添加到：${list.name}") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(comics) { comic ->
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
                            Text(if (already) "✓ ${comic.title}" else comic.title)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedList = null }) { Text("关闭") }
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
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WebDAV 同步") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = config.serverUrl,
                    onValueChange = { config = config.copy(serverUrl = it) },
                    label = { Text("服务器地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = config.username,
                    onValueChange = { config = config.copy(username = it) },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = config.password,
                    onValueChange = { config = config.copy(password = it) },
                    label = { Text("密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "支持 Nextcloud、坚果云、NAS 等 WebDAV 服务。同步内容：进度、书签、收藏。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                manager.saveConfig(config)
                scope.launch(Dispatchers.IO) {
                    status = "正在测试连接..."
                    val test = WebDavSync.testConnection(config)
                    status = test.getOrElse { "连接失败：${it.message}" }
                }
            }) { Text("测试连接") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
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
    data class ThemePreset(val name: String, val color: String, val texture: BackgroundTexture)
    val presets = listOf(
        ThemePreset("默认黑", "#FF000000", BackgroundTexture.NONE),
        ThemePreset("护眼纸", "#FFF5E6C8", BackgroundTexture.KRAFT),
        ThemePreset("磨砂玻璃", "#FF1A1A2E", BackgroundTexture.GLASS),
        ThemePreset("星空", "#FF050510", BackgroundTexture.STARS),
        ThemePreset("木纹", "#FF3E2723", BackgroundTexture.WOOD)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主题与阅读背景") },
        text = {
            Column {
                Text("快速切换阅读背景主题。自定义图片背景需在阅读菜单中后续扩展。")
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = currentBackground == preset.color && currentTexture == preset.texture,
                            onClick = { onApply(preset.color, preset.texture) },
                            label = { Text(preset.name) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
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
        title = { Text("评分与短评") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(comic.title, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text("评分：${rating.toInt()} / 5")
                Slider(value = rating, onValueChange = { rating = it }, valueRange = 0f..5f, steps = 4)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = review,
                    onValueChange = { review = it },
                    label = { Text("一句话短评") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("标签", style = MaterialTheme.typography.titleSmall)
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
                    label = { Text("新标签") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            val t = tagInput.trim()
                            if (t.isNotBlank() && !tags.contains(t)) tags.add(t)
                            tagInput = ""
                        }) { Icon(Icons.Default.Add, contentDescription = "添加") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(ComicExtras(rating = rating, review = review.trim(), tags = tags.toSortedSet()))
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
        title = { Text("SMB/NAS 流式阅读") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("NAS HTTP 文件服务地址") },
                    placeholder = { Text("http://192.168.1.100:5000/comics/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "提示：当前使用 HTTP 流式读取。若 NAS 仅提供 SMB，请在 NAS 上开启 WebDAV/HTTP 文件服务，或后续集成 jcifs-ng 库。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (files.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("远程目录（${files.size} 项）", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(files) { file ->
                            ListItem(
                                headlineContent = { Text(file.name) },
                                supportingContent = {
                                    Text(
                                        if (file.isDirectory) "目录"
                                        else if (file.isComic) "漫画"
                                        else "文件"
                                    )
                                },
                                modifier = Modifier.clickable {
                                    if (file.isDirectory) {
                                        scope.launch(Dispatchers.IO) {
                                            status = "正在列出 ${file.name}..."
                                            files = com.mangareader.utils.SmbClient.listDirectory(file.url)
                                            status = if (files.isEmpty()) "目录为空或无法访问" else ""
                                        }
                                    } else if (file.isComic) {
                                        // 复制远程地址到剪贴板，用户可在外部下载后导入
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("远程漫画地址", file.url))
                                        Toast.makeText(context, "已复制地址：${file.name}", Toast.LENGTH_SHORT).show()
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
                    status = "正在测试连接..."
                    val test = com.mangareader.utils.SmbClient.testConnection(url)
                    status = test.getOrElse { "连接失败：${it.message}" }
                    if (test.isSuccess) {
                        files = com.mangareader.utils.SmbClient.listDirectory(url)
                    }
                }
            }) { Text("测试/浏览") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
