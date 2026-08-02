package com.mangareader.bookshelf

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mangareader.R
import com.mangareader.bookshelf.components.NewBadge

/**
 * 筛选/排序栏：展示筛选芯片（全部/收藏/未读/在读/已读/新增）、
 * 排序切换按钮以及分类筛选行。
 */
@Composable
internal fun BookshelfFilterBar(
    filter: BookshelfFilter,
    sortBy: BookshelfSort,
    selectedCategory: String?,
    categories: List<String>,
    searchQuery: String,
    isSearching: Boolean,
    newComicUrisSize: Int,
    newComicUrisNotEmpty: Boolean,
    onFilterChange: (BookshelfFilter) -> Unit,
    onSortChange: (BookshelfSort) -> Unit,
    onSelectedCategoryChange: (String?) -> Unit,
    onClearFilters: () -> Unit,
    onShowCategoryDialog: () -> Unit
) {
    val hasActiveFilters = filter != BookshelfFilter.ALL
            || sortBy != BookshelfSort.NAME
            || selectedCategory != null
            || searchQuery.isNotBlank()
            || isSearching
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasActiveFilters) {
                FilterChip(
                    selected = false,
                    onClick = onClearFilters,
                    label = { Text(stringResource(R.string.bookshelf_clear_filters)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
            FilterChip(
                selected = filter == BookshelfFilter.ALL,
                onClick = { onFilterChange(BookshelfFilter.ALL) },
                label = { Text(stringResource(R.string.bookshelf_all)) }
            )
            FilterChip(
                selected = filter == BookshelfFilter.FAVORITES,
                onClick = { onFilterChange(BookshelfFilter.FAVORITES) },
                label = { Text(stringResource(R.string.bookshelf_favorites)) }
            )
            FilterChip(
                selected = filter == BookshelfFilter.UNREAD,
                onClick = { onFilterChange(BookshelfFilter.UNREAD) },
                label = { Text(stringResource(R.string.bookshelf_unread)) }
            )
            FilterChip(
                selected = filter == BookshelfFilter.READING,
                onClick = { onFilterChange(BookshelfFilter.READING) },
                label = { Text(stringResource(R.string.bookshelf_reading)) }
            )
            FilterChip(
                selected = filter == BookshelfFilter.READ,
                onClick = { onFilterChange(BookshelfFilter.READ) },
                label = { Text(stringResource(R.string.bookshelf_read)) }
            )
            FilterChip(
                selected = filter == BookshelfFilter.NEW,
                onClick = { onFilterChange(BookshelfFilter.NEW) },
                label = {
                    Text(
                        if (newComicUrisNotEmpty) stringResource(R.string.bookshelf_new_added_count, newComicUrisSize)
                        else stringResource(R.string.bookshelf_new_added)
                    )
                },
                leadingIcon = if (newComicUrisNotEmpty) {
                    { NewBadge() }
                } else null
            )
        }
        IconButton(onClick = {
            val next = when (sortBy) {
                BookshelfSort.NAME -> BookshelfSort.RECENT
                BookshelfSort.RECENT -> BookshelfSort.PROGRESS
                BookshelfSort.PROGRESS -> BookshelfSort.NAME
            }
            onSortChange(next)
        }) {
            val sortDescription = when (sortBy) {
                BookshelfSort.NAME -> stringResource(R.string.bookshelf_sort_by_name)
                BookshelfSort.RECENT -> stringResource(R.string.bookshelf_sort_by_recent)
                BookshelfSort.PROGRESS -> stringResource(R.string.bookshelf_sort_by_progress)
            }
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = sortDescription,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
    if (categories.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedCategory == null,
                onClick = { onSelectedCategoryChange(null) },
                label = { Text(stringResource(R.string.bookshelf_all_categories)) }
            )
            categories.forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { onSelectedCategoryChange(if (selectedCategory == cat) null else cat) },
                    label = { Text(cat) }
                )
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onShowCategoryDialog) {
                Icon(Icons.Default.Category, contentDescription = stringResource(R.string.bookshelf_manage_categories))
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onShowCategoryDialog) {
                Icon(Icons.Default.Category, contentDescription = stringResource(R.string.bookshelf_manage_categories))
            }
        }
    }
}
