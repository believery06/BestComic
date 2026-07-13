@file:OptIn(ExperimentalFoundationApi::class)

package com.mangareader.bookshelf

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicType
import com.mangareader.data.ComicExtras
import com.mangareader.data.ReaderSettings
import com.mangareader.data.ReadingStats
import com.mangareader.data.SettingsRepository
import com.mangareader.utils.MetadataScraper
import com.mangareader.utils.ComicMetadata
import com.mangareader.utils.WindowSizeClass
import com.mangareader.utils.rememberWindowSizeClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(
    onOpenComic: (ComicEntry) -> Unit,
    onOpenBrowser: () -> Unit,
    onShowHelpCallback: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: BookshelfViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = remember { SettingsRepository(context) }
    val favorites by settings.getFavorites().collectAsStateWithLifecycle(initialValue = emptySet())
    val categories by settings.getCategories().collectAsStateWithLifecycle(initialValue = emptyList())
    val comicCategories by settings.getComicCategories().collectAsStateWithLifecycle(initialValue = emptyMap())
    val bookmarksMap by settings.getBookmarks().collectAsStateWithLifecycle(initialValue = emptyMap())
    val stats by settings.getReadingStats().collectAsStateWithLifecycle(initialValue = ReadingStats())
    val extrasMap by settings.getAllComicExtras().collectAsStateWithLifecycle(initialValue = emptyMap())
    val readingLists by settings.getReadingLists().collectAsStateWithLifecycle(initialValue = emptyList())
    val readerSettings by settings.readerSettings.collectAsStateWithLifecycle(initialValue = ReaderSettings())
    var filter by remember { mutableStateOf(BookshelfFilter.ALL) }
    var sortBy by remember { mutableStateOf(BookshelfSort.NAME) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var showComicCategoryDialog by remember { mutableStateOf(false) }
    var comicToCategorize by remember { mutableStateOf<ComicEntry?>(null) }
    var showMetadataDialog by remember { mutableStateOf(false) }
    var metadataTarget by remember { mutableStateOf<ComicEntry?>(null) }
    var scrapedMetadata by remember { mutableStateOf<ComicMetadata?>(null) }
    var isScraping by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val windowSize = rememberWindowSizeClass()

    // 顶部更多菜单与功能对话框
    var showMoreMenu by remember { mutableStateOf(false) }
    var showContact by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showLists by remember { mutableStateOf(false) }
    var showWebDav by remember { mutableStateOf(false) }
    var showGitHubStar by remember { mutableStateOf(false) }
    var showDonation by remember { mutableStateOf(false) }
    var showSmbNas by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var comicForExtras by remember { mutableStateOf<ComicEntry?>(null) }

    // 批量选择模式
    var selectionMode by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.setRoot(it)
        }
    }

    // 同步读取上次保存的书架目录 URI，用于 launcher 启动时定位
    var savedRootUriString by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        savedRootUriString = settings.getBookshelfRoot().first()
    }

    val filteredComics = remember(uiState.comics, filter, favorites, uiState.progressMap, uiState.recentReads, selectedCategory, comicCategories, sortBy) {
        when (filter) {
            BookshelfFilter.ALL -> uiState.comics
            BookshelfFilter.FAVORITES -> uiState.comics.filter { favorites.contains(it.uri.toString()) }
            BookshelfFilter.UNREAD -> uiState.comics.filter { viewModel.getReadingStatus(it.uri) == com.mangareader.data.ReadingStatus.UNREAD }
            BookshelfFilter.READING -> uiState.comics.filter { viewModel.getReadingStatus(it.uri) == com.mangareader.data.ReadingStatus.READING }
            BookshelfFilter.READ -> uiState.comics.filter { viewModel.getReadingStatus(it.uri) == com.mangareader.data.ReadingStatus.READ }
        }
    }.let { comics ->
        // 按分类筛选
        if (selectedCategory != null) {
            comics.filter { comic ->
                comicCategories[comic.uri.toString()]?.contains(selectedCategory) == true
            }
        } else comics
    }.let { comics ->
        when (sortBy) {
            BookshelfSort.NAME -> comics.sortedBy { it.title.lowercase() }
            BookshelfSort.RECENT -> comics.sortedByDescending { uiState.recentReads[it.uri.toString()] ?: 0L }
        }
    }

    val recentComics = remember(filteredComics, uiState.recentReads) {
        filteredComics
            .filter { uiState.recentReads.containsKey(it.uri.toString()) }
            .sortedByDescending { uiState.recentReads[it.uri.toString()] ?: 0L }
            .take(10)
    }

    val isAtRoot = uiState.rootUri == null || uiState.currentUri == uiState.rootUri
    val currentName = remember(uiState.currentUri) {
        uiState.currentUri?.let { uri ->
            when (uri.scheme) {
                "file" -> java.io.File(uri.path ?: "").name
                else -> uri.lastPathSegment?.substringAfterLast('/')
            }
        } ?: "书架"
    }

    // 拦截系统返回手势/按键：从子目录返回到上级书架，根目录时交给系统处理（退出应用）
    BackHandler(enabled = !isAtRoot) {
        viewModel.navigateUp()
    }

    // 根据 RTL 设置应用全局布局方向（仅书架界面）
    val layoutDirection = if (readerSettings.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(if (isAtRoot) "书架" else currentName)
                            if (!isAtRoot) {
                                Text(
                                    text = uiState.pathStack.size.toString() + " 级",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (!isAtRoot) {
                            IconButton(onClick = { viewModel.navigateUp() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级")
                            }
                        }
                    },
                actions = {
                    TextButton(onClick = onOpenBrowser) {
                        Text("浏览器", color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onShowHelpCallback) {
                        Icon(Icons.AutoMirrored.Filled.Help, contentDescription = "使用说明")
                    }
                    IconButton(onClick = {
                        val initialUri = savedRootUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
                        folderLauncher.launch(initialUri)
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "添加书架目录")
                    }
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多选项")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 批量操作栏
            if (selectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("已选 ${selectedUris.size} 项", modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val uris = selectedUris.mapNotNull { str -> runCatching { Uri.parse(str) }.getOrNull() }
                        viewModel.markAsRead(uris)
                        scope.launch { snackbarHostState.showSnackbar("已将 ${uris.size} 本漫画标为已读") }
                        selectionMode = false
                        selectedUris = emptySet()
                    }) { Text("标已读") }
                    TextButton(onClick = {
                        val uris = selectedUris.mapNotNull { str -> runCatching { Uri.parse(str) }.getOrNull() }
                        viewModel.markAsUnread(uris)
                        scope.launch { snackbarHostState.showSnackbar("已将 ${uris.size} 本漫画标为未读") }
                        selectionMode = false
                        selectedUris = emptySet()
                    }) { Text("标未读") }
                    TextButton(onClick = {
                        val uris = selectedUris.mapNotNull { str -> runCatching { Uri.parse(str) }.getOrNull() }
                        scope.launch(Dispatchers.IO) {
                            for (uri in uris) {
                                settings.toggleFavorite(uri)
                            }
                            snackbarHostState.showSnackbar("已切换 ${uris.size} 本漫画的收藏状态")
                        }
                        selectionMode = false
                        selectedUris = emptySet()
                    }) { Text("收藏") }
                    if (categories.isNotEmpty()) {
                        TextButton(onClick = {
                            comicToCategorize = null
                            showComicCategoryDialog = true
                        }) { Text("分类") }
                    }
                    TextButton(onClick = {
                        showDeleteConfirm = true
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = {
                        selectionMode = false
                        selectedUris = emptySet()
                    }) { Text("取消") }
                }
            }
            // Filter / sort row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = filter == BookshelfFilter.ALL,
                    onClick = { filter = BookshelfFilter.ALL },
                    label = { Text("全部") }
                )
                FilterChip(
                    selected = filter == BookshelfFilter.FAVORITES,
                    onClick = { filter = BookshelfFilter.FAVORITES },
                    label = { Text("收藏") }
                )
                FilterChip(
                    selected = filter == BookshelfFilter.UNREAD,
                    onClick = { filter = BookshelfFilter.UNREAD },
                    label = { Text("未读") }
                )
                FilterChip(
                    selected = filter == BookshelfFilter.READING,
                    onClick = { filter = BookshelfFilter.READING },
                    label = { Text("阅读中") }
                )
                FilterChip(
                    selected = filter == BookshelfFilter.READ,
                    onClick = { filter = BookshelfFilter.READ },
                    label = { Text("已读") }
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    sortBy = if (sortBy == BookshelfSort.NAME) BookshelfSort.RECENT else BookshelfSort.NAME
                }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                }
            }
            // 分类筛选行
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
                        onClick = { selectedCategory = null },
                        label = { Text("全部分类") }
                    )
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                            label = { Text(cat) }
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { showCategoryDialog = true }) {
                        Icon(Icons.Default.Category, contentDescription = "管理分类")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = { showCategoryDialog = true }) {
                        Icon(Icons.Default.Category, contentDescription = "管理分类")
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    uiState.error != null -> {
                        Text(
                            text = "错误：${uiState.error}",
                            modifier = Modifier.align(Alignment.Center),
                            textAlign = TextAlign.Center
                        )
                    }
                    uiState.rootUri == null -> {
                        EmptyBookshelf(
                            onSelectFolder = { folderLauncher.launch(null) },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    filteredComics.isEmpty() -> {
                        Text(
                            text = "该目录下没有漫画",
                            modifier = Modifier.align(Alignment.Center),
                            textAlign = TextAlign.Center
                        )
                    }
                    else -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (isAtRoot && recentComics.isNotEmpty()) {
                                RecentReadsSection(
                                    comics = recentComics,
                                    progressMap = uiState.progressMap,
                                    onOpenComic = onOpenComic
                                )
                            }
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
                                    val isSelected = selectedUris.contains(comic.uri.toString())
                                    val comicCats = comicCategories[comic.uri.toString()] ?: emptySet()
                                    ComicCard(
                                        comic = comic,
                                        isFavorite = favorites.contains(comic.uri.toString()),
                                        readingStatus = viewModel.getReadingStatus(comic.uri),
                                        isSelected = isSelected,
                                        selectionMode = selectionMode,
                                        categories = comicCats,
                                        hasCategories = categories.isNotEmpty(),
                                        onClick = {
                                            if (selectionMode) {
                                                selectedUris = if (isSelected) {
                                                    selectedUris - comic.uri.toString()
                                                } else {
                                                    selectedUris + comic.uri.toString()
                                                }
                                                if (selectedUris.isEmpty()) selectionMode = false
                                            } else if (comic.isDirectory) {
                                                viewModel.openDirectory(comic.uri)
                                            } else {
                                                onOpenComic(comic)
                                            }
                                        },
                                        onLongClick = {
                                            if (!selectionMode) {
                                                selectionMode = true
                                            }
                                            selectedUris = if (isSelected) {
                                                selectedUris - comic.uri.toString()
                                            } else {
                                                selectedUris + comic.uri.toString()
                                            }
                                        },
                                        onToggleFavorite = {
                                            scope.launch(Dispatchers.IO) {
                                                settings.toggleFavorite(comic.uri)
                                            }
                                        },
                                        onCategorize = {
                                            comicToCategorize = comic
                                            showComicCategoryDialog = true
                                        },
                                        onScrape = {
                                            metadataTarget = comic
                                            scrapedMetadata = null
                                            showMetadataDialog = true
                                        },
                                        onShowExtras = {
                                            comicForExtras = comic
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 分类管理对话框
    if (showCategoryDialog) {
        var newCategoryName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text("管理分类") },
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
                                    scope.launch(Dispatchers.IO) { settings.removeCategory(cat) }
                                    if (selectedCategory == cat) selectedCategory = null
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = newCategoryName,
                        onValueChange = { newCategoryName = it },
                        label = { Text("新分类名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = newCategoryName.trim()
                    if (trimmed.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            settings.addCategory(trimmed)
                            snackbarHostState.showSnackbar("已添加分类：$trimmed")
                        }
                        newCategoryName = ""
                        showCategoryDialog = false
                    }
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showCategoryDialog = false }) { Text("关闭") }
            }
        )
    }

    // 漫画分类选择对话框
    if (showComicCategoryDialog) {
        val urisToCategorize = if (comicToCategorize != null) {
            listOf(comicToCategorize!!.uri)
        } else {
            selectedUris.mapNotNull { str -> runCatching { Uri.parse(str) }.getOrNull() }
        }
        val title = if (comicToCategorize != null) {
            "分类: ${comicToCategorize!!.title}"
        } else {
            "为 ${urisToCategorize.size} 项选择分类"
        }
        AlertDialog(
            onDismissRequest = {
                showComicCategoryDialog = false
                comicToCategorize = null
                if (selectionMode && selectedUris.isNotEmpty()) {
                    selectionMode = false
                    selectedUris = emptySet()
                }
            },
            title = { Text(title) },
            text = {
                LazyColumn {
                    items(categories) { cat ->
                        val currentCats = if (comicToCategorize != null) {
                            comicCategories[comicToCategorize!!.uri.toString()] ?: emptySet()
                        } else {
                            urisToCategorize.mapNotNull { comicCategories[it.toString()] }.flatten().toSet()
                        }
                        val isInCategory = currentCats.contains(cat)
                        val allInCategory = if (comicToCategorize != null) isInCategory else {
                            urisToCategorize.all { (comicCategories[it.toString()] ?: emptySet()).contains(cat) }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch(Dispatchers.IO) {
                                        for (uri in urisToCategorize) {
                                            settings.toggleComicCategory(uri, cat)
                                        }
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
                TextButton(onClick = {
                    showComicCategoryDialog = false
                    comicToCategorize = null
                    if (selectionMode) {
                        selectionMode = false
                        selectedUris = emptySet()
                    }
                }) { Text("完成") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除选中的 ${selectedUris.size} 个漫画吗？此操作将同时删除文件本身，无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val uris = selectedUris.mapNotNull { str -> runCatching { Uri.parse(str) }.getOrNull() }
                    viewModel.removeComics(uris)
                    scope.launch { snackbarHostState.showSnackbar("已删除 ${uris.size} 个漫画") }
                    showDeleteConfirm = false
                    selectionMode = false
                    selectedUris = emptySet()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }

            }
        )
    }

    // 元数据刮削对话框
    if (showMetadataDialog && metadataTarget != null) {
        val target = metadataTarget!!
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
                } catch (_: Exception) {
                } finally {
                    isScraping = false
                }
            }
        }

        val meta = scrapedMetadata
        AlertDialog(
            onDismissRequest = {
                showMetadataDialog = false
                scrapedMetadata = null
            },
            title = { Text("元数据刮削") },
            text = {
                if (isScraping) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在查询...")
                    }
                } else if (meta != null) {
                    Column {
                        Text("文件: ${target.title}", style = MaterialTheme.typography.bodySmall)
                        if (meta.title.isNotEmpty()) Text("标题: ${meta.title}")
                        if (meta.author.isNotEmpty()) Text("作者: ${meta.author}")
                        if (meta.volume > 0) Text("卷: ${meta.volume}")
                        if (meta.chapter.isNotEmpty()) Text("话: ${meta.chapter}")
                        if (meta.year > 0) Text("年份: ${meta.year}")
                        if (meta.description.isNotEmpty()) Text("简介: ${meta.description.take(200)}")
                        if (meta.tags.isNotEmpty()) Text("标签: ${meta.tags.take(5).joinToString(", ")}")
                        Text("来源: ${meta.source}", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("未能获取到元数据", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showMetadataDialog = false
                    scrapedMetadata = null
                }) {
                    Text("关闭")
                }
            }
        )
    }

    // 顶部更多菜单：联系作者、阅读统计、我的书单、WebDAV、主题
    DropdownMenu(
        expanded = showMoreMenu,
        onDismissRequest = { showMoreMenu = false }
    ) {
        DropdownMenuItem(
            text = { Text("联系作者") },
            leadingIcon = { Icon(Icons.Default.ContactMail, contentDescription = null) },
            onClick = {
                showMoreMenu = false
                showContact = true
            }
        )
        DropdownMenuItem(
            text = { Text("阅读统计") },
            leadingIcon = { Icon(Icons.Default.BarChart, contentDescription = null) },
            onClick = {
                showMoreMenu = false
                showStats = true
            }
        )
        DropdownMenuItem(
            text = { Text("我的书单") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
            onClick = {
                showMoreMenu = false
                showLists = true
            }
        )
        DropdownMenuItem(
            text = { Text("WebDAV 同步") },
            leadingIcon = { Icon(Icons.Default.CloudSync, contentDescription = null) },
            onClick = {
                showMoreMenu = false
                Toast.makeText(context, "WebDAV 同步暂未完全开发，仅可配置和测试连接", Toast.LENGTH_SHORT).show()
                showWebDav = true
            }
        )
        DropdownMenuItem(
            text = { Text("SMB/NAS 流式阅读") },
            leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null) },
            onClick = {
                showMoreMenu = false
                Toast.makeText(context, "SMB/NAS 流式阅读暂未完全开发，仅可浏览远程目录", Toast.LENGTH_SHORT).show()
                showSmbNas = true
            }
        )
        DropdownMenuItem(
            text = { Text("主题与阅读背景") },
            leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
            onClick = {
                showMoreMenu = false
                showTheme = true
            }
        )
        DropdownMenuItem(
            text = { Text("设置") },
            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
            onClick = {
                showMoreMenu = false
                onOpenSettings()
            }
        )
        DropdownMenuItem(
            text = { Text("GitHub Star") },
            leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
            onClick = {
                showMoreMenu = false
                showGitHubStar = true
            }
        )
        DropdownMenuItem(
            text = { Text("打赏支持") },
            leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
            onClick = {
                showMoreMenu = false
                showDonation = true
            }
        )
    }

    if (showContact) {
        ContactAuthorDialog(onDismiss = { showContact = false })
    }

    if (showStats) {
        ReadingStatsDialog(
            comics = uiState.comics,
            progressMap = uiState.progressMap,
            stats = stats,
            extras = extrasMap,
            lists = readingLists,
            onDismiss = { showStats = false }
        )
    }

    if (showLists) {
        ReadingListsDialog(
            comics = uiState.comics,
            onDismiss = { showLists = false },
            repository = settings,
            scope = scope
        )
    }

    if (showWebDav) {
        WebDavSyncDialog(
            progressMap = uiState.progressMap,
            bookmarks = bookmarksMap,
            favorites = favorites,
            onDismiss = { showWebDav = false }
        )
    }

    if (showGitHubStar) {
        val context = LocalContext.current
        GitHubStarDialog(
            onDismiss = { showGitHubStar = false },
            onOpenGitHub = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(com.mangareader.Constants.GITHUB_REPO_URL))
                    context.startActivity(intent)
                    showGitHubStar = false
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "无法打开浏览器", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showDonation) {
        DonationDialog(onDismiss = { showDonation = false })
    }

    if (showSmbNas) {
        SmbNasDialog(onDismiss = { showSmbNas = false })
    }

    if (showTheme) {
        ThemePickerDialog(
            currentBackground = readerSettings.backgroundColor,
            currentTexture = readerSettings.backgroundTexture,
            onApply = { color, texture ->
                scope.launch(Dispatchers.IO) {
                    val newSettings = readerSettings.copy(
                        backgroundColor = color,
                        backgroundTexture = texture
                    )
                    settings.saveReaderSettings(newSettings)
                }
                showTheme = false
            },
            onDismiss = { showTheme = false }
        )
    }

    // 漫画评分与短评对话框
    comicForExtras?.let { comic ->
        val extras by settings.getComicExtras(comic.uri).collectAsStateWithLifecycle(initialValue = ComicExtras())
        val allTags = remember(extrasMap) {
            extrasMap.values.flatMap { it.tags }.distinct().sorted()
        }
        ComicExtrasDialog(
            comic = comic,
            initialExtras = extras,
            allTags = allTags,
            onSave = { newExtras ->
                scope.launch(Dispatchers.IO) {
                    settings.saveComicExtras(comic.uri, newExtras)
                }
                comicForExtras = null
            },
            onDismiss = { comicForExtras = null }
        )
    }
    } // end CompositionLocalProvider
}

private enum class BookshelfFilter { ALL, FAVORITES, UNREAD, READING, READ }
private enum class BookshelfSort { NAME, RECENT }

@Composable
private fun ComicCard(
    comic: ComicEntry,
    isFavorite: Boolean,
    readingStatus: com.mangareader.data.ReadingStatus,
    isSelected: Boolean,
    selectionMode: Boolean,
    categories: Set<String> = emptySet(),
    hasCategories: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCategorize: () -> Unit = {},
    onScrape: () -> Unit = {},
    onShowExtras: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
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
            .widthIn(min = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val coverUri = comic.coverUri
        val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent
        Box {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .border(3.dp, borderColor, RoundedCornerShape(8.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                if (coverUri != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(coverUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = comic.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = null,
                        fallback = null,
                        error = null
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }
            // 阅读状态角标
            val statusText = when (readingStatus) {
                com.mangareader.data.ReadingStatus.READ -> "已读"
                com.mangareader.data.ReadingStatus.READING -> "阅读中"
                com.mangareader.data.ReadingStatus.UNREAD -> ""
            }
            val statusColor = when (readingStatus) {
                com.mangareader.data.ReadingStatus.READ -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
                com.mangareader.data.ReadingStatus.READING -> androidx.compose.ui.graphics.Color(0xFFFF9800)
                com.mangareader.data.ReadingStatus.UNREAD -> androidx.compose.ui.graphics.Color.Transparent
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
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            // 收藏和分类按钮（选择模式下隐藏）
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
                        contentDescription = if (isFavorite) "取消收藏" else "收藏",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White,
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
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.Category,
                            contentDescription = "分类",
                            tint = if (categories.isNotEmpty()) MaterialTheme.colorScheme.secondary else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            // 选中标记
            if (selectionMode && isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已选",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(24.dp)
                )
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

    androidx.compose.material3.DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false }
    ) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(if (isFavorite) "取消收藏" else "收藏") },
            leadingIcon = {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null
                )
            },
            onClick = {
                onToggleFavorite()
                showMenu = false
            }
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("分类") },
            leadingIcon = {
                Icon(imageVector = Icons.Default.Category, contentDescription = null)
            },
            onClick = {
                onCategorize()
                showMenu = false
            }
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(if (readingStatus == com.mangareader.data.ReadingStatus.READ) "标记未读" else "标记已读") },
            leadingIcon = {
                Icon(imageVector = Icons.Default.DoneAll, contentDescription = null)
            },
            onClick = {
                onLongClick()
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text("刮削元数据") },
            leadingIcon = {
                Icon(imageVector = Icons.Default.Search, contentDescription = null)
            },
            onClick = {
                onScrape()
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text("评分与短评") },
            leadingIcon = {
                Icon(imageVector = Icons.Default.RateReview, contentDescription = null)
            },
            onClick = {
                onShowExtras()
                showMenu = false
            }
        )
    }
}

@Composable
private fun RecentReadsSection(
    comics: List<ComicEntry>,
    progressMap: Map<String, Pair<Int, Int>>,
    onOpenComic: (ComicEntry) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "最近阅读",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(comics) { comic ->
                val progress = progressMap[comic.uri.toString()]?.second ?: 0
                val total = comic.totalPages.coerceAtLeast(1)
                RecentReadCard(
                    comic = comic,
                    progressText = "${progress}/${total}P",
                    onClick = { onOpenComic(comic) }
                )
            }
        }
    }
}

@Composable
private fun RecentReadCard(
    comic: ComicEntry,
    progressText: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(100.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val coverUri = comic.coverUri
            if (coverUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(coverUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = comic.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = comic.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = progressText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EmptyBookshelf(
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
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text("还没有漫画哦", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "选择一个文件夹作为书架，里面的漫画就会出现在这里～",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onSelectFolder,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("添加书架目录")
        }
    }
}
