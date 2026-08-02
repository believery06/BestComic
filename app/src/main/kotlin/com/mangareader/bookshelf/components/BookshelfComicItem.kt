@file:OptIn(ExperimentalFoundationApi::class)

package com.mangareader.bookshelf.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangareader.R
import com.mangareader.data.ComicEntry
import com.mangareader.data.ReadingStatus
import com.mangareader.ui.components.CoverAsyncImage

/**
 * 漫画条目回调集合，用于将点击/长按/菜单等操作从列表/网格项统一向上传递。
 */
internal data class ComicCallbacks(
    val onClick: () -> Unit,
    val onLongClick: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onCategorize: () -> Unit,
    val onScrape: () -> Unit,
    val onShowExtras: () -> Unit,
    val onClearCache: () -> Unit
)

/**
 * 书架列表视图下的漫画条目。
 */
@Composable
internal fun ComicListItem(
    comic: ComicEntry,
    isFavorite: Boolean,
    readingStatus: ReadingStatus,
    progress: Int,
    total: Int,
    isSelected: Boolean,
    isNew: Boolean,
    categories: Set<String>,
    hasCategories: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCategorize: () -> Unit,
    onScrape: () -> Unit,
    onShowExtras: () -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, shape)
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 90.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            DefaultCoverIcon(comic = comic, modifier = Modifier.fillMaxSize())
            CoverAsyncImage(
                uri = comic.coverUri,
                contentDescription = comic.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = comic.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (readingStatus) {
                        ReadingStatus.UNREAD -> stringResource(R.string.bookshelf_status_unread)
                        ReadingStatus.READING -> stringResource(R.string.bookshelf_status_reading)
                        ReadingStatus.READ -> stringResource(R.string.bookshelf_status_read)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isNew) {
                    Spacer(modifier = Modifier.width(6.dp))
                    NewBadge()
                }
            }
            if (readingStatus == ReadingStatus.READING && total > 0) {
                val ratio = (progress.toFloat() / total).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            if (categories.isNotEmpty()) {
                Text(
                    text = categories.take(3).joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = stringResource(R.string.bookshelf_favorite),
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.bookshelf_more_options))
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bookshelf_rate_review)) },
                    onClick = { showMenu = false; onShowExtras() }
                )
                if (hasCategories) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookshelf_categorize)) },
                        onClick = { showMenu = false; onCategorize() }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bookshelf_scrape_metadata)) },
                    onClick = { showMenu = false; onScrape() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bookshelf_clear_cache)) },
                    onClick = { showMenu = false; onClearCache() }
                )
            }
        }
    }
}

/**
 * 书架网格视图下的漫画卡片。
 */
@Composable
internal fun ComicCard(
    comic: ComicEntry,
    isFavorite: Boolean,
    readingStatus: ReadingStatus,
    progress: Int,
    total: Int,
    isSelected: Boolean,
    selectionMode: Boolean,
    isNew: Boolean,
    categories: Set<String> = emptySet(),
    hasCategories: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCategorize: () -> Unit = {},
    onScrape: () -> Unit = {},
    onShowExtras: () -> Unit = {},
    onClearCache: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val moreOptionsLabel = stringResource(R.string.bookshelf_more_options)

    Column(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (selectionMode) {
                        onLongClick()
                    } else {
                        showMenu = true
                    }
                }
            )
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(moreOptionsLabel) { showMenu = true; true }
                )
            }
            .widthIn(min = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val coverUri = comic.coverUri
        val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
        Box {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .border(3.dp, borderColor, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = stringResource(R.string.bookshelf_default_cover),
                        modifier = Modifier.size(48.dp)
                    )
                    CoverAsyncImage(
                        uri = coverUri,
                        contentDescription = comic.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            val statusText = when (readingStatus) {
                ReadingStatus.READ -> stringResource(R.string.bookshelf_status_read)
                ReadingStatus.READING -> stringResource(R.string.bookshelf_status_reading)
                ReadingStatus.UNREAD -> ""
            }
            val statusColor = when (readingStatus) {
                ReadingStatus.READ -> MaterialTheme.colorScheme.primary
                ReadingStatus.READING -> MaterialTheme.colorScheme.secondary
                ReadingStatus.UNREAD -> Color.Transparent
            }
            if (statusText.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(statusColor)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = statusText,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (!selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(40.dp)
                        .clickable(onClick = onToggleFavorite)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (isFavorite) stringResource(R.string.bookshelf_unfavorite) else stringResource(R.string.bookshelf_favorite),
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                if (hasCategories) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(40.dp)
                            .clickable(onClick = onCategorize)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Category,
                            contentDescription = stringResource(R.string.bookshelf_categorize),
                            tint = if (categories.isNotEmpty()) MaterialTheme.colorScheme.secondary else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            if (selectionMode && isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.bookshelf_selected),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(24.dp)
                )
            }
            if (readingStatus == ReadingStatus.READING && total > 0) {
                val ratio = (progress.toFloat() / total).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    strokeCap = StrokeCap.Butt
                )
            }
            if (isNew && !selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                ) {
                    NewBadge()
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = comic.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (categories.isNotEmpty()) {
            Text(
                text = categories.take(2).joinToString(", ") + if (categories.size > 2) "..." else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false }
    ) {
        DropdownMenuItem(
            text = { Text(if (isFavorite) stringResource(R.string.bookshelf_unfavorite) else stringResource(R.string.bookshelf_favorite)) },
            leadingIcon = {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(R.string.bookshelf_favorites)
                )
            },
            onClick = {
                onToggleFavorite()
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_categorize)) },
            leadingIcon = {
                Icon(imageVector = Icons.Default.Category, contentDescription = stringResource(R.string.bookshelf_categorize))
            },
            onClick = {
                onCategorize()
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text(if (readingStatus == ReadingStatus.READ) stringResource(R.string.bookshelf_mark_unread) else stringResource(R.string.bookshelf_mark_read)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = if (readingStatus == ReadingStatus.READ) stringResource(R.string.bookshelf_mark_unread) else stringResource(R.string.bookshelf_mark_read)
                )
            },
            onClick = {
                onLongClick()
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_scrape_metadata)) },
            leadingIcon = {
                Icon(imageVector = Icons.Default.Search, contentDescription = stringResource(R.string.bookshelf_scrape_metadata))
            },
            onClick = {
                onScrape()
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_rate_review)) },
            leadingIcon = {
                Icon(imageVector = Icons.Default.RateReview, contentDescription = stringResource(R.string.bookshelf_rate_review))
            },
            onClick = {
                onShowExtras()
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_clear_cache)) },
            leadingIcon = {
                Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.bookshelf_clear_cache))
            },
            onClick = {
                onClearCache()
                showMenu = false
            }
        )
    }
}

/**
 * 新加入漫画角标。
 */
@Composable
internal fun NewBadge() {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(12.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = "NEW",
            color = MaterialTheme.colorScheme.onError,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * 默认封面占位图标：文件夹或书籍。
 */
@Composable
internal fun DefaultCoverIcon(comic: ComicEntry, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (comic.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = stringResource(R.string.bookshelf_default_cover),
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
