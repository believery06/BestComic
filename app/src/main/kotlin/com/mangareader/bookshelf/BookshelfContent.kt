@file:OptIn(ExperimentalMaterialApi::class)

package com.mangareader.bookshelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mangareader.R
import com.mangareader.bookshelf.components.ComicCallbacks
import com.mangareader.bookshelf.components.ComicCard
import com.mangareader.bookshelf.components.ComicListItem
import com.mangareader.bookshelf.components.EmptyBookshelf
import com.mangareader.bookshelf.components.EmptyBookshelfState
import com.mangareader.data.ComicEntry
import com.mangareader.utils.WindowSizeClass

/**
 * 书架内容区：包裹下拉刷新，根据当前状态展示错误/空书架/无结果/网格或列表。
 * 继续阅读横幅与最近阅读区保留在主文件中渲染。
 */
@Composable
internal fun BookshelfContent(
    error: String?,
    rootUri: android.net.Uri?,
    filteredComics: List<ComicEntry>,
    isBookshelfEmpty: Boolean,
    viewMode: BookshelfViewMode,
    isLoading: Boolean,
    windowSize: WindowSizeClass,
    isAtRoot: Boolean,
    debouncedSearchQuery: String,
    filter: BookshelfFilter,
    selectedCategory: String?,
    comicCallbacks: (ComicEntry) -> ComicCallbacks,
    favorites: Set<String>,
    comicCategories: Map<String, Set<String>>,
    progressMap: Map<String, Pair<Int, Int>>,
    getReadingStatus: (android.net.Uri) -> com.mangareader.data.ReadingStatus,
    selectedUris: Set<String>,
    newComicUris: Set<String>,
    categories: List<String>,
    onSelectFolder: () -> Unit,
    onRefresh: () -> Unit,
    onClearFilter: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isLoading,
        onRefresh = onRefresh
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullRefresh(pullRefreshState)
    ) {
        when {
            error != null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.bookshelf_error_prefix, error),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = onRefresh) {
                        Text(stringResource(R.string.bookshelf_error_retry))
                    }
                }
            }
            rootUri == null -> {
                EmptyBookshelf(
                    onSelectFolder = onSelectFolder,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            // 书架/当前目录本身没有任何漫画：显示「该目录下没有漫画」。
            isBookshelfEmpty -> {
                Text(
                    text = stringResource(R.string.bookshelf_no_comics_in_folder),
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = TextAlign.Center
                )
            }
            // 目录里有漫画，但被筛选/搜索过滤后为空：显示无结果提示并提供「清除筛选」入口。
            filteredComics.isEmpty() -> {
                EmptyBookshelfState(
                    hasSearch = debouncedSearchQuery.isNotBlank(),
                    hasFilter = filter != BookshelfFilter.ALL || selectedCategory != null,
                    isRoot = isAtRoot,
                    onClearFilter = onClearFilter,
                    onOpenBrowser = onOpenBrowser,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (viewMode == BookshelfViewMode.GRID) {
                        val minCell = when (windowSize) {
                            WindowSizeClass.TABLET -> 160.dp
                            WindowSizeClass.PHONE_LANDSCAPE -> 140.dp
                            WindowSizeClass.PHONE_PORTRAIT -> 120.dp
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = minCell),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            gridItems(filteredComics, key = { it.uri.toString() }) { comic ->
                                val callbacks = remember(comic.uri.toString()) { comicCallbacks(comic) }
                                val comicCats = comicCategories[comic.uri.toString()] ?: emptySet()
                                val (progress, total) = progressMap[comic.uri.toString()] ?: (0 to 0)
                                ComicCard(
                                    comic = comic,
                                    isFavorite = favorites.contains(comic.uri.toString()),
                                    readingStatus = getReadingStatus(comic.uri),
                                    progress = progress,
                                    total = total,
                                    isSelected = selectedUris.contains(comic.uri.toString()),
                                    selectionMode = selectedUris.isNotEmpty(),
                                    isNew = newComicUris.contains(comic.uri.toString()),
                                    categories = comicCats,
                                    hasCategories = categories.isNotEmpty(),
                                    onClick = callbacks.onClick,
                                    onLongClick = callbacks.onLongClick,
                                    onToggleFavorite = callbacks.onToggleFavorite,
                                    onCategorize = callbacks.onCategorize,
                                    onScrape = callbacks.onScrape,
                                    onShowExtras = callbacks.onShowExtras,
                                    onClearCache = callbacks.onClearCache
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(filteredComics, key = { it.uri.toString() }) { comic ->
                                val callbacks = remember(comic.uri.toString()) { comicCallbacks(comic) }
                                val comicCats = comicCategories[comic.uri.toString()] ?: emptySet()
                                val (progress, total) = progressMap[comic.uri.toString()] ?: (0 to 0)
                                ComicListItem(
                                    comic = comic,
                                    isFavorite = favorites.contains(comic.uri.toString()),
                                    readingStatus = getReadingStatus(comic.uri),
                                    progress = progress,
                                    total = total,
                                    isSelected = selectedUris.contains(comic.uri.toString()),
                                    isNew = newComicUris.contains(comic.uri.toString()),
                                    categories = comicCats,
                                    hasCategories = categories.isNotEmpty(),
                                    onClick = callbacks.onClick,
                                    onLongClick = callbacks.onLongClick,
                                    onToggleFavorite = callbacks.onToggleFavorite,
                                    onCategorize = callbacks.onCategorize,
                                    onScrape = callbacks.onScrape,
                                    onShowExtras = callbacks.onShowExtras,
                                    onClearCache = callbacks.onClearCache
                                )
                            }
                        }
                    }
                }
            }
        }
        PullRefreshIndicator(
            refreshing = isLoading,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
            // Material2 指示器在仅提供 Material3 主题时会回退到默认 lightColors()，
            // 其 surface 为纯白，在深色书架上会出现白色圆块，故显式指定主题色。
            backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary
        )
    }
}
