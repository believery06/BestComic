@file:OptIn(ExperimentalMaterial3Api::class)

package com.mangareader.bookshelf

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContactMail
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangareader.R
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicExtras
import com.mangareader.data.SettingsRepository
import com.mangareader.utils.ComicMetadata
import com.mangareader.utils.MetadataScraper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 分类管理对话框：展示已有分类（可删除）与新增分类输入框。
 */
@Composable
internal fun CategoryDialog(
    categories: List<String>,
    selectedCategory: String?,
    onAddCategory: (String) -> Unit,
    onRemoveCategory: (String) -> Unit,
    onSelectedCategoryChange: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var newCategoryName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookshelf_manage_categories_title)) },
        text = {
            Column {
                if (categories.isNotEmpty()) {
                    categories.forEach { cat ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(cat, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                onRemoveCategory(cat)
                                if (selectedCategory == cat) onSelectedCategoryChange(null)
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    label = { Text(stringResource(R.string.bookshelf_new_category_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = newCategoryName.trim()
                if (trimmed.isNotBlank()) {
                    onAddCategory(trimmed)
                    newCategoryName = ""
                }
            }) { Text(stringResource(R.string.bookshelf_add_label)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookshelf_close_label)) }
        }
    )
}

/**
 * 漫画分类选择对话框：为单本或多本漫画选择分类。
 */
@Composable
internal fun ComicCategoryDialog(
    singleComic: ComicEntry?,
    uris: List<Uri>,
    categories: List<String>,
    comicCategories: Map<String, Set<String>>,
    onToggleComicCategory: (Uri, String) -> Unit,
    onDismiss: () -> Unit
) {
    val title = if (singleComic != null) {
        stringResource(R.string.bookshelf_categorize_comic_title, singleComic.title)
    } else {
        stringResource(R.string.bookshelf_categorize_multi_title, uris.size)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(categories, key = { it }) { cat ->
                    val currentCats = if (singleComic != null) {
                        comicCategories[singleComic.uri.toString()] ?: emptySet()
                    } else {
                        uris.mapNotNull { comicCategories[it.toString()] }.flatten().toSet()
                    }
                    val isInCategory = currentCats.contains(cat)
                    val allInCategory = if (singleComic != null) isInCategory else {
                        uris.all { (comicCategories[it.toString()] ?: emptySet()).contains(cat) }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                for (uri in uris) {
                                    onToggleComicCategory(uri, cat)
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = allInCategory,
                            onCheckedChange = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(cat)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookshelf_done_label)) }
        }
    )
}

/**
 * 删除确认对话框：确认是否删除已选漫画。
 */
@Composable
internal fun DeleteConfirmDialog(
    selectedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookshelf_delete_confirm_title)) },
        text = { Text(stringResource(R.string.bookshelf_delete_confirm_message, selectedCount)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.bookshelf_delete_label), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookshelf_cancel_label)) }
        }
    )
}

/**
 * 重命名对话框：输入新文件名并重命名漫画。
 */
@Composable
internal fun RenameDialog(
    renameTarget: ComicEntry,
    onRename: (ComicEntry, String, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember(renameTarget) { mutableStateOf(renameTarget.title.substringBeforeLast('.')) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookshelf_rename_title)) },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text(stringResource(R.string.bookshelf_new_file_name)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val ext = renameTarget.title.substringAfterLast('.', "")
                    val finalName = if (ext.isNotBlank()) "$newName.$ext" else newName
                    onRename(renameTarget, finalName) { success ->
                        onDismiss()
                    }
                },
                enabled = newName.isNotBlank()
            ) { Text(stringResource(R.string.bookshelf_ok_label)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookshelf_cancel_label)) }
        }
    )
}

/**
 * 多书架切换对话框：展示书架列表、切换、删除与添加入口。
 */
@Composable
internal fun BookshelfSwitcherDialog(
    bookshelfUris: List<String>,
    currentBookshelfIndex: Int,
    onSwitchBookshelf: (Int) -> Unit,
    onRemoveBookshelf: (Int) -> Unit,
    onAddBookshelf: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookshelf_switch_bookshelf_title)) },
        text = {
            Column {
                if (bookshelfUris.isEmpty()) {
                    Text(stringResource(R.string.bookshelf_no_bookshelf_added), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    bookshelfUris.forEachIndexed { index, uriString ->
                        val name = runCatching {
                            Uri.parse(uriString).lastPathSegment?.substringAfterLast('/')
                        }.getOrNull() ?: uriString
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSwitchBookshelf(index) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = index == currentBookshelfIndex,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(onClick = {
                                onRemoveBookshelf(index)
                            }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.bookshelf_remove_bookshelf),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAddBookshelf) { Text(stringResource(R.string.bookshelf_add_bookshelf_label)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookshelf_close_label)) }
        }
    )
}

/**
 * 元数据刮削对话框：展示文件名解析与在线搜索结果。
 */
@Composable
internal fun MetadataScrapeDialog(
    target: ComicEntry,
    onDismiss: () -> Unit
) {
    var scrapedMetadata by remember { mutableStateOf<ComicMetadata?>(null) }
    var isScraping by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        if (scrapedMetadata == null && !isScraping) {
            isScraping = true
            val localMeta = MetadataScraper.parseFilename(target.title)
            scrapedMetadata = localMeta
            try {
                val keyword = localMeta.title.ifBlank { target.title }
                val bangumi = MetadataScraper.searchBangumi(keyword)
                if (bangumi != null) {
                    scrapedMetadata = bangumi.copy(
                        volume = localMeta.volume,
                        chapter = localMeta.chapter,
                        author = localMeta.author.ifBlank { bangumi.author }
                    )
                } else {
                    val mangadex = MetadataScraper.searchMangaDex(keyword)
                    if (mangadex != null) {
                        scrapedMetadata = mangadex.copy(
                            volume = localMeta.volume,
                            chapter = localMeta.chapter
                        )
                    }
                }
            } catch (_: CancellationException) {
                // 对话框关闭时协程被取消，无需处理
            } catch (_: Exception) {
            } finally {
                isScraping = false
            }
        }
    }
    val meta = scrapedMetadata
    AlertDialog(
        onDismissRequest = {
            scrapedMetadata = null
            onDismiss()
        },
        title = { Text(stringResource(R.string.bookshelf_metadata_scrape_title)) },
        text = {
            if (isScraping) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.bookshelf_metadata_querying))
                }
            } else if (meta != null) {
                Column {
                    Text(stringResource(R.string.bookshelf_metadata_file_label, target.title), style = MaterialTheme.typography.bodySmall)
                    if (meta.title.isNotEmpty()) Text(stringResource(R.string.bookshelf_metadata_title_label, meta.title))
                    if (meta.author.isNotEmpty()) Text(stringResource(R.string.bookshelf_metadata_author_label, meta.author))
                    if (meta.volume > 0) Text(stringResource(R.string.bookshelf_metadata_volume_label, meta.volume))
                    if (meta.chapter.isNotEmpty()) Text(stringResource(R.string.bookshelf_metadata_chapter_label, meta.chapter))
                    if (meta.year > 0) Text(stringResource(R.string.bookshelf_metadata_year_label, meta.year))
                    if (meta.description.isNotEmpty()) Text(stringResource(R.string.bookshelf_metadata_description_label, meta.description.take(200)))
                    if (meta.tags.isNotEmpty()) Text(stringResource(R.string.bookshelf_metadata_tags_label, meta.tags.take(5).joinToString(", ")))
                    Text(stringResource(R.string.bookshelf_metadata_source_label, meta.source), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(stringResource(R.string.bookshelf_metadata_not_found), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scrapedMetadata = null
                onDismiss()
            }) {
                Text(stringResource(R.string.bookshelf_close_label))
            }
        }
    )
}

/**
 * 顶部更多菜单：浏览、添加书架、阅读历史、帮助、联系作者、阅读统计、
 * 我的书单、WebDAV、主题、设置、GitHub Star、捐赠等入口。
 */
@Composable
internal fun BookshelfMoreMenu(
    showMoreMenu: Boolean,
    newComicUris: Set<String>,
    onOpenBrowser: () -> Unit,
    onAddDirectory: () -> Unit,
    onShowHistory: () -> Unit,
    onShowHelp: () -> Unit,
    onShowContact: () -> Unit,
    onShowStats: () -> Unit,
    onShowLists: () -> Unit,
    onShowWebDav: () -> Unit,
    onShowSmbNas: () -> Unit,
    onShowTheme: () -> Unit,
    onClearNewBadges: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowGitHubStar: () -> Unit,
    onShowDonation: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    DropdownMenu(
        expanded = showMoreMenu,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_browser)) },
            leadingIcon = { Icon(Icons.Default.Explore, contentDescription = stringResource(R.string.bookshelf_browser)) },
            onClick = onOpenBrowser
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_add_directory)) },
            leadingIcon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.bookshelf_add_directory)) },
            onClick = onAddDirectory
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_read_history)) },
            leadingIcon = { Icon(Icons.Default.History, contentDescription = stringResource(R.string.bookshelf_read_history)) },
            onClick = onShowHistory
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_help)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Help, contentDescription = stringResource(R.string.bookshelf_help)) },
            onClick = onShowHelp
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_contact_author)) },
            leadingIcon = { Icon(Icons.Default.ContactMail, contentDescription = stringResource(R.string.bookshelf_contact_author)) },
            onClick = onShowContact
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_reading_stats)) },
            leadingIcon = { Icon(Icons.Default.BarChart, contentDescription = stringResource(R.string.bookshelf_reading_stats)) },
            onClick = onShowStats
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_reading_lists)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = stringResource(R.string.bookshelf_reading_lists)) },
            onClick = onShowLists
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_webdav_sync)) },
            leadingIcon = { Icon(Icons.Default.CloudSync, contentDescription = stringResource(R.string.bookshelf_webdav_sync)) },
            onClick = onShowWebDav
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_smb_nas)) },
            leadingIcon = { Icon(Icons.Default.Storage, contentDescription = stringResource(R.string.bookshelf_smb_nas)) },
            onClick = {
                Toast.makeText(context, context.getString(R.string.bookshelf_smb_nas_toast), Toast.LENGTH_SHORT).show()
                onShowSmbNas()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_theme)) },
            leadingIcon = { Icon(Icons.Default.Palette, contentDescription = stringResource(R.string.bookshelf_theme)) },
            onClick = onShowTheme
        )
        if (newComicUris.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bookshelf_clear_new_badges)) },
                leadingIcon = { Icon(Icons.Default.NewReleases, contentDescription = stringResource(R.string.bookshelf_clear_new_badges)) },
                onClick = onClearNewBadges
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_settings)) },
            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.bookshelf_settings)) },
            onClick = onOpenSettings
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_github_star)) },
            leadingIcon = { Icon(Icons.Default.Star, contentDescription = stringResource(R.string.bookshelf_github_star)) },
            onClick = onShowGitHubStar
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_donation)) },
            leadingIcon = { Icon(Icons.Default.Star, contentDescription = stringResource(R.string.bookshelf_donation)) },
            onClick = onShowDonation
        )
    }
}

/**
 * 漫画评分与短评对话框。
 */
@Composable
internal fun ComicExtrasDialogWrapper(
    comic: ComicEntry,
    settings: SettingsRepository,
    onDismiss: () -> Unit
) {
    val extras by settings.getComicExtras(comic.uri).collectAsStateWithLifecycle(initialValue = ComicExtras())
    val allTags = remember(settings) {
        emptyList<String>()
    }
    ComicExtrasDialog(
        comic = comic,
        initialExtras = extras,
        allTags = allTags,
        onSave = { newExtras ->
            kotlinx.coroutines.MainScope().launch {
                settings.saveComicExtras(comic.uri, newExtras)
            }
            onDismiss()
        },
        onDismiss = onDismiss
    )
}
