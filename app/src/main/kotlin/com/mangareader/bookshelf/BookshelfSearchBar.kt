package com.mangareader.bookshelf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mangareader.R
import com.mangareader.bookshelf.components.SearchHistoryPanel

/**
 * 搜索栏与搜索历史面板：包含输入框、清空/清除历史按钮，
 * 以及搜索历史/空历史提示。
 */
@Composable
internal fun BookshelfSearchBar(
    isSearching: Boolean,
    searchQuery: String,
    searchHistory: List<String>,
    onSearchQueryChange: (String) -> Unit,
    onClearSearchQuery: () -> Unit,
    onClearSearchHistory: () -> Unit,
    onSearchHistoryItemClick: (String) -> Unit,
    onSearchSubmit: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    AnimatedVisibility(visible = isSearching) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text(stringResource(R.string.bookshelf_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = onClearSearchQuery) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear))
                    }
                } else if (searchHistory.isNotEmpty()) {
                    IconButton(onClick = onClearSearchHistory) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.bookshelf_search_history_clear))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    val q = searchQuery.trim()
                    if (q.isNotEmpty()) {
                        onSearchSubmit(q)
                    }
                }
            )
        )
    }
    AnimatedVisibility(
        visible = isSearching && searchQuery.isBlank() && searchHistory.isNotEmpty()
    ) {
        SearchHistoryPanel(
            history = searchHistory,
            onItemClick = onSearchHistoryItemClick,
            onClear = onClearSearchHistory,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
    AnimatedVisibility(
        visible = isSearching && searchQuery.isBlank() && searchHistory.isEmpty()
    ) {
        Text(
            text = stringResource(R.string.bookshelf_search_history_empty),
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
