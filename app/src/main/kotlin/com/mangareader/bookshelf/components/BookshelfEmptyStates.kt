package com.mangareader.bookshelf.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mangareader.R

/**
 * 书架空状态/搜索无结果/筛选无结果统一提示组件。
 */
@Composable
internal fun EmptyBookshelfState(
    hasSearch: Boolean,
    hasFilter: Boolean,
    isRoot: Boolean,
    onClearFilter: () -> Unit,
    onOpenBrowser: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (icon, title, subtitle) = when {
        hasSearch -> Triple(Icons.Default.Search, stringResource(R.string.bookshelf_no_search_match), stringResource(R.string.bookshelf_search_hint_subtitle))
        hasFilter -> Triple(Icons.Default.FilterList, stringResource(R.string.bookshelf_no_filter_match), stringResource(R.string.bookshelf_filter_hint_subtitle))
        !isRoot -> Triple(Icons.Default.FolderOpen, stringResource(R.string.bookshelf_empty_folder_title), stringResource(R.string.bookshelf_empty_folder_subtitle))
        else -> Triple(Icons.Default.CollectionsBookmark, stringResource(R.string.bookshelf_empty_title), stringResource(R.string.bookshelf_empty_subtitle))
    }
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(R.string.bookshelf_empty_title),
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            subtitle,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (hasSearch || hasFilter) {
            OutlinedButton(
                onClick = onClearFilter,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.ClearAll, contentDescription = stringResource(R.string.bookshelf_clear_filters), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.bookshelf_clear_filters))
            }
        } else if (isRoot) {
            Button(
                onClick = onOpenBrowser,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.bookshelf_add_directory), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.bookshelf_add_directory))
            }
        }
    }
}

/**
 * 未添加任何书架目录时的空引导页。
 */
@Composable
internal fun EmptyBookshelf(
    onSelectFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CollectionsBookmark,
                contentDescription = stringResource(R.string.bookshelf_empty_title),
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(stringResource(R.string.bookshelf_empty_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.bookshelf_empty_subtitle_with_tilde),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onSelectFolder,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.bookshelf_add_directory), modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.bookshelf_add_directory))
        }
    }
}
