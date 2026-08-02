@file:OptIn(ExperimentalMaterial3Api::class)

package com.mangareader.bookshelf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mangareader.R

/**
 * 书架顶部栏：展示当前书架名称（可点击切换）、扫描提示、
 * 搜索/视图切换/更多菜单入口，非根目录时显示返回按钮。
 */
@Composable
internal fun BookshelfTopBar(
    currentShelfName: String,
    scanHint: String,
    isAtRoot: Boolean,
    currentName: String,
    pathLevel: Int,
    isSearching: Boolean,
    viewMode: BookshelfViewMode,
    onNavigateUp: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleViewMode: () -> Unit,
    onShowMoreMenu: () -> Unit,
    onShowBookshelfSwitcher: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                if (isAtRoot) {
                    TextButton(
                        onClick = onShowBookshelfSwitcher,
                        contentPadding = PaddingValues(horizontal = 0.dp)
                    ) {
                        Text(currentShelfName, color = MaterialTheme.colorScheme.onSurface)
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = stringResource(R.string.bookshelf_switch_bookshelf),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (scanHint.isNotBlank()) {
                        Text(
                            text = scanHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(currentName.takeIf { it.isNotEmpty() } ?: stringResource(R.string.bookshelf_default_shelf_name))
                }
                if (!isAtRoot) {
                    Text(
                        text = stringResource(R.string.bookshelf_path_level, pathLevel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        navigationIcon = {
            if (!isAtRoot) {
                IconButton(onClick = onNavigateUp) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = {
            IconButton(onClick = onToggleSearch) {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search))
            }
            IconButton(onClick = onToggleViewMode) {
                val viewModeIcon = if (viewMode == BookshelfViewMode.GRID) {
                    Icons.Default.Menu
                } else {
                    Icons.Default.GridOn
                }
                Icon(
                    imageVector = viewModeIcon,
                    contentDescription = if (viewMode == BookshelfViewMode.GRID) stringResource(R.string.bookshelf_switch_list_view) else stringResource(R.string.bookshelf_switch_grid_view)
                )
            }
            IconButton(onClick = onShowMoreMenu) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.bookshelf_more_options))
            }
        }
    )
}
