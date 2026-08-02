package com.mangareader.bookshelf

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mangareader.R
import com.mangareader.data.ComicEntry

/**
 * 批量操作栏：多选模式下展示已选数量与批量动作
 * （标已读/未读/收藏/分享/分类/重命名/删除/取消）。
 */
@Composable
internal fun BookshelfBatchToolbar(
    selectedUris: Set<String>,
    comics: List<ComicEntry>,
    categories: List<String>,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    onMarkAsRead: (List<Uri>) -> Unit,
    onMarkAsUnread: (List<Uri>) -> Unit,
    onToggleFavorite: (List<Uri>) -> Unit,
    onShare: (List<ComicEntry>) -> Unit,
    onCategorize: () -> Unit,
    onRename: (ComicEntry) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.bookshelf_selected_count, selectedUris.size), modifier = Modifier.weight(1f))
        TextButton(onClick = {
            val uris = selectedUris.mapNotNull { str -> runCatching { Uri.parse(str) }.getOrNull() }
            onMarkAsRead(uris)
        }) { Text(stringResource(R.string.bookshelf_mark_read_label)) }
        TextButton(onClick = {
            val uris = selectedUris.mapNotNull { str -> runCatching { Uri.parse(str) }.getOrNull() }
            onMarkAsUnread(uris)
        }) { Text(stringResource(R.string.bookshelf_mark_unread_label)) }
        TextButton(onClick = {
            val uris = selectedUris.mapNotNull { str -> runCatching { Uri.parse(str) }.getOrNull() }
            onToggleFavorite(uris)
        }) { Text(stringResource(R.string.bookshelf_favorite)) }
        TextButton(onClick = {
            val selectedEntries = comics.filter { selectedUris.contains(it.uri.toString()) }
            onShare(selectedEntries)
        }) { Text(stringResource(R.string.bookshelf_share_label)) }
        if (categories.isNotEmpty()) {
            TextButton(onClick = onCategorize) { Text(stringResource(R.string.bookshelf_categorize_label)) }
        }
        if (selectedUris.size == 1) {
            TextButton(onClick = {
                val uriString = selectedUris.firstOrNull()
                val target = comics.find { it.uri.toString() == uriString }
                if (target != null) onRename(target)
            }) { Text(stringResource(R.string.bookshelf_rename_label)) }
        }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.bookshelf_delete_label), color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.bookshelf_cancel_label)) }
    }
}
