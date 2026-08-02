package com.mangareader.chapters

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mangareader.R
import com.mangareader.data.ComicChapter
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterBrowserScreen(
    comicUri: Uri,
    onOpenReader: (Uri) -> Unit,
    onBack: () -> Unit,
    viewModel: ChapterBrowserViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 从 URI 重建 ComicEntry，用于加载漫画
    val comicEntry = remember(comicUri) {
        val uriString = comicUri.toString()
        val title = comicUri.lastPathSegment ?: context.getString(R.string.external_intent_default_title)
        val type = com.mangareader.parser.ParserFactory.detectType(title)
        val isDirectory = type == ComicType.FOLDER || type == ComicType.COMIC_BOOK
        ComicEntry(
            uri = comicUri,
            title = title,
            type = type,
            isDirectory = isDirectory,
            path = uriString
        )
    }

    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val filteredChapters = remember(uiState.chapters, searchQuery) {
        if (searchQuery.isBlank()) uiState.chapters
        else uiState.chapters.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    LaunchedEffect(comicEntry) {
        viewModel.load(comicEntry)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearching) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.chapter_browser_search_hint)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = stringResource(R.string.action_search)
                                )
                            },
                            trailingIcon = if (searchQuery.isNotEmpty()) {
                                {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            Icons.Default.Clear,
                                            contentDescription = stringResource(R.string.action_clear)
                                        )
                                    }
                                }
                            } else null
                        )
                        LaunchedEffect(isSearching) {
                            if (isSearching) focusRequester.requestFocus()
                        }
                    } else {
                        Column {
                            Text(
                                text = comicEntry.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(
                                    R.string.chapter_browser_volumes_count,
                                    uiState.chapters.size
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSearching) {
                            isSearching = false
                            searchQuery = ""
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (!isSearching && uiState.continueAvailable) {
                        TextButton(
                            onClick = {
                                onOpenReader(comicUri)
                            }
                        ) {
                            Text(stringResource(R.string.chapter_browser_continue_reading))
                        }
                    }
                    if (!isSearching && uiState.chapters.size > 1) {
                        IconButton(onClick = { isSearching = true }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.action_search)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.error != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = uiState.error ?: "",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = { viewModel.load(comicEntry) }) {
                            Text(stringResource(R.string.bookshelf_error_retry))
                        }
                    }
                }
                uiState.chapters.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.chapter_browser_no_chapters),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                filteredChapters.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.chapter_browser_no_search_match),
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 使用 URI+标题+索引作为复合键：同一漫画的所有章节共享 entry.uri，
                        // 仅用 URI 会导致 LazyGrid 键重复崩溃。
                        items(
                            count = filteredChapters.size,
                            key = { index ->
                                val ch = filteredChapters[index]
                                "${ch.uri}#${ch.title}#$index"
                            }
                        ) { index ->
                            val chapter = filteredChapters[index]
                            ChapterCard(
                                chapter = chapter,
                                viewModel = viewModel,
                                onClick = {
                                    onOpenReader(chapter.uri)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterCard(
    chapter: ComicChapter,
    viewModel: ChapterBrowserViewModel,
    onClick: () -> Unit
) {
    // 使用章节 URI 作为键，避免不同章节标题相同时共享缩略图状态。
    var thumb by remember(chapter.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(chapter.uri) {
        chapter.pages.firstOrNull()?.let { firstPage ->
            thumb = viewModel.loadThumbnail(firstPage)
        }
    }
    // 卡片离开组合或加载新缩略图时回收旧位图，避免分卷浏览页面积累大量内存。
    DisposableEffect(thumb) {
        val currentThumb = thumb
        onDispose {
            currentThumb?.takeIf { !it.isRecycled }?.let { runCatching { it.recycle() } }
        }
    }

    Column(
        modifier = Modifier
            .widthIn(min = 100.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(3f / 4f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            thumb?.let { b ->
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = chapter.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } ?: Icon(
                imageVector = if (chapter.pages.isEmpty()) Icons.Default.Book else Icons.Default.Image,
                contentDescription = stringResource(R.string.chapter_browser_default_cover),
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 页数角标
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = stringResource(R.string.chapter_browser_pages_count, chapter.pages.size),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = chapter.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
