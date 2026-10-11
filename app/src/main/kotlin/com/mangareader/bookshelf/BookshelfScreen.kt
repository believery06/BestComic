@file:OptIn(ExperimentalMaterial3Api::class)

package com.mangareader.bookshelf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mangareader.R
import com.mangareader.bookshelf.components.ComicCallbacks
import com.mangareader.bookshelf.components.DefaultCoverIcon
import com.mangareader.data.BookshelfScanState
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicExtras
import com.mangareader.data.ComicType
import com.mangareader.data.SettingsRepository
import com.mangareader.ui.components.CoverAsyncImage
import com.mangareader.utils.WindowSizeClass
import com.mangareader.utils.rememberWindowSizeClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onOpenComic: (ComicEntry) -> Unit,
    onOpenReader: (ComicEntry) -> Unit,
    onOpenBrowser: () -> Unit,
    onShowHistory: () -> Unit = {},
    onShowHelpCallback: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: BookshelfViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = remember { SettingsRepository.getInstance(context) }
    val settingsState by viewModel.settingsState.collectAsStateWithLifecycle()
    val favorites = settingsState.favorites
    val categories = settingsState.categories
    val comicCategories = settingsState.comicCategories
    val bookmarksMap = settingsState.bookmarksMap
    val stats = settingsState.stats
    val extrasMap = settingsState.extrasMap
    val readingLists = settingsState.readingLists
    val readerSettings = settingsState.readerSettings
    val progressMap by viewModel.progressMap.collectAsStateWithLifecycle()
    val recentReads by viewModel.recentReads.collectAsStateWithLifecycle()
    val newComicUris = uiState.newComicUris
    var filter by rememberSaveable(stateSaver = BookshelfFilterSaver) { mutableStateOf(BookshelfFilter.ALL) }
    var sortBy by rememberSaveable(stateSaver = BookshelfSortSaver) { mutableStateOf(BookshelfSort.NAME) }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    val searchHistory by settings.getBookshelfSearchHistory()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // 搜索防抖：输入停止 500ms 后再执行过滤，避免大图书馆频繁重算
    LaunchedEffect(searchQuery) {
        delay(500)
        debouncedSearchQuery = searchQuery
    }
    LaunchedEffect(searchQuery) {
        settings.saveBookshelfSearchQuery(searchQuery)
    }
    LaunchedEffect(isSearching) {
        settings.saveBookshelfSearching(isSearching)
    }
    var viewMode by rememberSaveable(stateSaver = BookshelfViewModeSaver) { mutableStateOf(BookshelfViewMode.GRID) }
    // 书架状态持久化：rememberSaveable 处理配置变更，DataStore 处理进程重启。
    LaunchedEffect(Unit) {
        runCatching {
            filter = BookshelfFilter.valueOf(settings.getBookshelfFilter().first())
        }
        runCatching {
            sortBy = BookshelfSort.valueOf(settings.getBookshelfSort().first())
        }
        runCatching {
            selectedCategory = settings.getBookshelfSelectedCategory().first()
        }
        runCatching {
            val saved = settings.getBookshelfViewMode().first()
            viewMode = runCatching { BookshelfViewMode.valueOf(saved) }.getOrDefault(viewMode)
        }
        runCatching {
            searchQuery = settings.getBookshelfSearchQuery().first()
        }
        runCatching {
            isSearching = settings.isBookshelfSearching().first()
        }
    }
    LaunchedEffect(viewMode) {
        settings.saveBookshelfViewMode(viewMode.name)
    }
    LaunchedEffect(filter) {
        settings.saveBookshelfFilter(filter.name)
    }
    LaunchedEffect(sortBy) {
        settings.saveBookshelfSort(sortBy.name)
    }
    LaunchedEffect(selectedCategory) {
        settings.saveBookshelfSelectedCategory(selectedCategory)
    }
    // 若已保存的分类被删除，则自动清除，避免书架显示为空；
    // 分类列表尚未加载时（为空）不执行清除，防止启动时丢失已保存的分类。
    LaunchedEffect(categories, selectedCategory) {
        if (selectedCategory != null && categories.isNotEmpty() && !categories.contains(selectedCategory)) {
            selectedCategory = null
        }
    }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var showComicCategoryDialog by remember { mutableStateOf(false) }
    var comicToCategorize by remember { mutableStateOf<ComicEntry?>(null) }
    var showMetadataDialog by remember { mutableStateOf(false) }
    var metadataTarget by remember { mutableStateOf<ComicEntry?>(null) }
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

    // 多书架状态
    val bookshelfUris = settingsState.bookshelfUris
    val currentBookshelfIndex = settingsState.currentBookshelfIndex
    val currentRootUriString = remember(uiState.rootUri) { uiState.rootUri?.toString() }
    val scanState by produceState(initialValue = BookshelfScanState(), currentRootUriString) {
        value = BookshelfScanState()
        currentRootUriString?.let { root ->
            settings.getBookshelfScanState(root).collect { value = it }
        }
    }
    var showBookshelfSwitcher by remember { mutableStateOf(false) }

    // 批量选择模式
    var selectionMode by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ComicEntry?>(null) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // 书架需要持久读/写权限：后续支持批量重命名、删除等操作。
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, flags)
            viewModel.addBookshelf(it)
        }
    }

    // 同步读取上次保存的书架目录 URI，用于 launcher 启动时定位
    var savedRootUriString by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        savedRootUriString = settings.getBookshelfRoot().first()
    }

    // 应用回到前台时自动刷新书架，检测新加入的漫画文件
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.maybeAutoRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val filteredComics by remember {
        derivedStateOf {
            when (filter) {
                BookshelfFilter.ALL -> uiState.comics
                BookshelfFilter.FAVORITES -> uiState.comics.filter { favorites.contains(it.uri.toString()) }
                BookshelfFilter.UNREAD -> uiState.comics.filter { viewModel.getReadingStatus(it.uri) == com.mangareader.data.ReadingStatus.UNREAD }
                BookshelfFilter.READING -> uiState.comics.filter { viewModel.getReadingStatus(it.uri) == com.mangareader.data.ReadingStatus.READING }
                BookshelfFilter.READ -> uiState.comics.filter { viewModel.getReadingStatus(it.uri) == com.mangareader.data.ReadingStatus.READ }
                BookshelfFilter.NEW -> uiState.comics.filter { newComicUris.contains(it.uri.toString()) }
            }.let { comics ->
                if (selectedCategory != null) {
                    comics.filter { comic ->
                        comicCategories[comic.uri.toString()]?.contains(selectedCategory) == true
                    }
                } else comics
            }.let { comics ->
                if (debouncedSearchQuery.isNotBlank()) {
                    val q = debouncedSearchQuery.trim().lowercase()
                    comics.filter { it.title.lowercase().contains(q) }
                } else comics
            }.let { comics ->
                when (sortBy) {
                    BookshelfSort.NAME -> comics.sortedBy { it.title.lowercase() }
                    BookshelfSort.RECENT -> comics.sortedByDescending { recentReads[it.uri.toString()] ?: 0L }
                    BookshelfSort.PROGRESS -> comics.sortedByDescending {
                        val (progress, total) = progressMap[it.uri.toString()] ?: (0 to 0)
                        if (total > 0) progress.toFloat() / total else -1f
                    }
                }
            }.distinctBy { it.uri.toString() } // 防御性去重，防止 LazyGrid 键重复崩溃
        }
    }

    val recentComics by remember {
        derivedStateOf {
            uiState.comics
                .filter { recentReads.containsKey(it.uri.toString()) }
                .sortedByDescending { recentReads[it.uri.toString()] ?: 0L }
                .take(10)
        }
    }

    val isAtRoot = uiState.rootUri == null || uiState.currentUri == uiState.rootUri
    val currentName = remember(uiState.currentUri) {
        uiState.currentUri?.let { uri ->
            when (uri.scheme) {
                "file" -> java.io.File(uri.path ?: "").name
                else -> uri.lastPathSegment?.substringAfterLast('/')
            }
        } ?: ""
    }

    val currentShelfName = bookshelfUris.getOrNull(currentBookshelfIndex)?.let { uri ->
        runCatching { android.net.Uri.parse(uri).lastPathSegment?.substringAfterLast('/') }.getOrNull()
    } ?: stringResource(R.string.bookshelf_default_shelf_name)
    val scanHint = formatBookshelfScanTime(context, scanState.lastScanAt)

    // 构建漫画回调集合（供 BookshelfContent 使用）
    val comicCallbacks = remember(
        selectedUris, selectionMode, viewModel, onOpenComic, onOpenReader,
        settings, scope, context, categories
    ) {
        { comic: ComicEntry ->
            val uriString = comic.uri.toString()
            ComicCallbacks(
                onClick = {
                    val isSelected = selectedUris.contains(uriString)
                    if (selectionMode) {
                        selectedUris = if (isSelected) {
                            selectedUris - uriString
                        } else {
                            selectedUris + uriString
                        }
                        if (selectedUris.isEmpty()) selectionMode = false
                    } else {
                        // 目录类漫画（分卷合集）直接进入分卷浏览，不再多压一层冗余的文件夹列表，
                        // 与项目硬约束「点击漫画先进分卷浏览」及 continueReading 行为保持一致。
                        viewModel.markComicSeen(uriString)
                        onOpenComic(comic)
                    }
                },
                onLongClick = {
                    if (!selectionMode) {
                        selectionMode = true
                    }
                    val isSelected = selectedUris.contains(uriString)
                    selectedUris = if (isSelected) {
                        selectedUris - uriString
                    } else {
                        selectedUris + uriString
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
                    showMetadataDialog = true
                },
                onShowExtras = { comicForExtras = comic },
                onClearCache = {
                    scope.launch(Dispatchers.IO) {
                        val cache = com.mangareader.cache.PageCacheManager.getInstance(context)
                        val size = cache.getCacheSizeFor(comic.uri.toString())
                        cache.clearByCacheId(comic.uri.toString())
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val sizeText = when {
                                size <= 0 -> context.getString(R.string.bookshelf_cache_size_b, 0)
                                size < 1024 * 1024 -> context.getString(R.string.bookshelf_cache_size_kb, size / 1024)
                                else -> context.getString(R.string.bookshelf_cache_size_mb, size / (1024.0 * 1024.0))
                            }
                            val msg = if (size > 0) context.getString(R.string.bookshelf_cache_cleared_snackbar, sizeText) else context.getString(R.string.bookshelf_no_cache_to_clear)
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }

    // 拦截系统返回手势/按键：多选模式先清空选择，子目录返回上级书架，根目录时交给系统处理（退出应用）
    BackHandler(enabled = selectionMode || !isAtRoot) {
        if (selectionMode) {
            selectedUris = emptySet()
            selectionMode = false
        } else {
            viewModel.navigateUp()
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                BookshelfTopBar(
                    currentShelfName = currentShelfName,
                    scanHint = scanHint,
                    isAtRoot = isAtRoot,
                    currentName = currentName,
                    pathLevel = uiState.pathStack.size,
                    isSearching = isSearching,
                    viewMode = viewMode,
                    onNavigateUp = { viewModel.navigateUp() },
                    onToggleSearch = { isSearching = !isSearching; if (!isSearching) searchQuery = "" },
                    onToggleViewMode = { viewMode = if (viewMode == BookshelfViewMode.GRID) BookshelfViewMode.LIST else BookshelfViewMode.GRID },
                    onShowMoreMenu = { showMoreMenu = true },
                    onShowBookshelfSwitcher = { showBookshelfSwitcher = true }
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
                    BookshelfBatchToolbar(
                        selectedUris = selectedUris,
                        comics = uiState.comics,
                        categories = categories,
                        snackbarHostState = snackbarHostState,
                        onMarkAsRead = { uris ->
                            viewModel.markAsRead(uris)
                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.bookshelf_marked_read_snackbar, uris.size)) }
                            selectionMode = false
                            selectedUris = emptySet()
                        },
                        onMarkAsUnread = { uris ->
                            viewModel.markAsUnread(uris)
                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.bookshelf_marked_unread_snackbar, uris.size)) }
                            selectionMode = false
                            selectedUris = emptySet()
                        },
                        onToggleFavorite = { uris ->
                            scope.launch(Dispatchers.IO) {
                                for (uri in uris) {
                                    settings.toggleFavorite(uri)
                                }
                                snackbarHostState.showSnackbar(context.getString(R.string.bookshelf_toggled_favorite_snackbar, uris.size))
                            }
                            selectionMode = false
                            selectedUris = emptySet()
                        },
                        onShare = { selectedEntries ->
                            scope.launch(Dispatchers.IO) {
                                val skipped = selectedUris.size - selectedEntries.count { !it.isDirectory }
                                val shareUris = viewModel.prepareShareUris(selectedEntries)
                                withContext(Dispatchers.Main) {
                                    if (shareUris.isNotEmpty()) {
                                        shareComicFiles(context, shareUris)
                                        if (skipped > 0) {
                                            snackbarHostState.showSnackbar(context.getString(R.string.bookshelf_share_skipped_snackbar, skipped))
                                        }
                                    } else {
                                        snackbarHostState.showSnackbar(context.getString(R.string.bookshelf_share_empty))
                                    }
                                    selectionMode = false
                                    selectedUris = emptySet()
                                }
                            }
                        },
                        onCategorize = {
                            comicToCategorize = null
                            showComicCategoryDialog = true
                        },
                        onRename = { comic ->
                            renameTarget = comic
                            showRenameDialog = true
                        },
                        onDelete = { showDeleteConfirm = true },
                        onCancel = {
                            selectionMode = false
                            selectedUris = emptySet()
                        }
                    )
                }
                // 搜索栏 + 搜索历史面板
                BookshelfSearchBar(
                    isSearching = isSearching,
                    searchQuery = searchQuery,
                    searchHistory = searchHistory,
                    onSearchQueryChange = { searchQuery = it },
                    onClearSearchQuery = { searchQuery = "" },
                    onClearSearchHistory = { scope.launch { settings.clearBookshelfSearchHistory() } },
                    onSearchHistoryItemClick = { item ->
                        searchQuery = item
                        scope.launch { settings.addBookshelfSearchHistory(item) }
                    },
                    onSearchSubmit = { q ->
                        scope.launch { settings.addBookshelfSearchHistory(q) }
                    }
                )
                // 继续阅读横幅 + 最近阅读区
                if (isAtRoot && recentComics.isNotEmpty()) {
                    val lastComic = recentComics.first()
                    val onContinueLast = remember(lastComic, onOpenReader, onOpenComic) {
                        { continueReading(lastComic, onOpenReader, onOpenComic) }
                    }
                    ContinueReadingBanner(
                        comic = lastComic,
                        progress = progressMap[lastComic.uri.toString()]?.first ?: 0,
                        total = progressMap[lastComic.uri.toString()]?.second
                            ?: lastComic.totalPages.coerceAtLeast(1),
                        onContinue = onContinueLast
                    )
                    val onContinueRecent = remember(onOpenReader, onOpenComic) {
                        { comic: ComicEntry -> continueReading(comic, onOpenReader, onOpenComic) }
                    }
                    RecentReadsSection(
                        comics = recentComics,
                        progressMap = progressMap,
                        onContinue = onContinueRecent
                    )
                }
                // 筛选/排序栏
                BookshelfFilterBar(
                    filter = filter,
                    sortBy = sortBy,
                    selectedCategory = selectedCategory,
                    categories = categories,
                    searchQuery = searchQuery,
                    isSearching = isSearching,
                    newComicUrisSize = newComicUris.size,
                    newComicUrisNotEmpty = newComicUris.isNotEmpty(),
                    onFilterChange = { filter = it },
                    onSortChange = { sortBy = it },
                    onSelectedCategoryChange = { selectedCategory = it },
                    onClearFilters = {
                        filter = BookshelfFilter.ALL
                        sortBy = BookshelfSort.NAME
                        selectedCategory = null
                        searchQuery = ""
                        isSearching = false
                    },
                    onShowCategoryDialog = { showCategoryDialog = true }
                )
                // 内容区（下拉刷新 + 错误/空/网格/列表）
                BookshelfContent(
                    error = uiState.error,
                    rootUri = uiState.rootUri,
                    filteredComics = filteredComics,
                    viewMode = viewMode,
                    isLoading = uiState.isLoading,
                    windowSize = windowSize,
                    isAtRoot = isAtRoot,
                    debouncedSearchQuery = debouncedSearchQuery,
                    filter = filter,
                    selectedCategory = selectedCategory,
                    comicCallbacks = comicCallbacks,
                    favorites = favorites,
                    comicCategories = comicCategories,
                    progressMap = progressMap,
                    getReadingStatus = { uri -> viewModel.getReadingStatus(uri) },
                    selectedUris = selectedUris,
                    newComicUris = newComicUris,
                    categories = categories,
                    onSelectFolder = { folderLauncher.launch(null) },
                    onRefresh = { viewModel.refresh() },
                    onClearFilter = {
                        filter = BookshelfFilter.ALL
                        selectedCategory = null
                        searchQuery = ""
                    },
                    onOpenBrowser = onOpenBrowser
                )
            }
        }
    }

    // 分类管理对话框
    if (showCategoryDialog) {
        CategoryDialog(
            categories = categories,
            selectedCategory = selectedCategory,
            onAddCategory = { name ->
                scope.launch(Dispatchers.IO) {
                    settings.addCategory(name)
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(context.getString(R.string.bookshelf_category_added_snackbar, name))
                    }
                }
            },
            onRemoveCategory = { cat ->
                scope.launch(Dispatchers.IO) { settings.removeCategory(cat) }
            },
            onSelectedCategoryChange = { selectedCategory = it },
            onDismiss = { showCategoryDialog = false }
        )
    }

    // 漫画分类选择对话框
    if (showComicCategoryDialog) {
        val singleComic = comicToCategorize
        val urisToCategorize = if (singleComic != null) {
            listOf(singleComic.uri)
        } else {
            selectedUris.mapNotNull { str -> runCatching { Uri.parse(str) }.getOrNull() }
        }
        ComicCategoryDialog(
            singleComic = singleComic,
            uris = urisToCategorize,
            categories = categories,
            comicCategories = comicCategories,
            onToggleComicCategory = { uri, cat ->
                scope.launch(Dispatchers.IO) {
                    settings.toggleComicCategory(uri, cat)
                }
            },
            onDismiss = {
                showComicCategoryDialog = false
                comicToCategorize = null
                if (selectionMode && selectedUris.isNotEmpty()) {
                    selectionMode = false
                    selectedUris = emptySet()
                }
            }
        )
    }

    // 删除确认对话框
    if (showDeleteConfirm) {
        DeleteConfirmDialog(
            selectedCount = selectedUris.size,
            onConfirm = {
                val uris = selectedUris.mapNotNull { str -> runCatching { Uri.parse(str) }.getOrNull() }
                viewModel.removeComics(uris)
                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.bookshelf_deleted_snackbar, uris.size)) }
                showDeleteConfirm = false
                selectionMode = false
                selectedUris = emptySet()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    // 重命名对话框
    if (showRenameDialog && renameTarget != null) {
        RenameDialog(
            renameTarget = renameTarget!!,
            onRename = { target, newName, callback ->
                if (newName.isNotBlank() && newName != target.title) {
                    viewModel.renameComic(target.uri, newName) { success ->
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (success) context.getString(R.string.bookshelf_renamed_snackbar, newName)
                                else context.getString(R.string.bookshelf_rename_failed_snackbar)
                            )
                        }
                        callback(success)
                    }
                }
            },
            onDismiss = {
                showRenameDialog = false
                renameTarget = null
                selectionMode = false
                selectedUris = emptySet()
            }
        )
    }

    // 多书架切换对话框
    if (showBookshelfSwitcher) {
        BookshelfSwitcherDialog(
            bookshelfUris = bookshelfUris,
            currentBookshelfIndex = currentBookshelfIndex,
            onSwitchBookshelf = { index ->
                showBookshelfSwitcher = false
                viewModel.switchBookshelf(index)
            },
            onRemoveBookshelf = { index ->
                viewModel.removeBookshelf(index)
                if (bookshelfUris.size <= 1) showBookshelfSwitcher = false
            },
            onAddBookshelf = {
                showBookshelfSwitcher = false
                val initialUri = savedRootUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
                folderLauncher.launch(initialUri)
            },
            onDismiss = { showBookshelfSwitcher = false }
        )
    }

    // 元数据刮削对话框
    if (showMetadataDialog) {
        metadataTarget?.let { target ->
            MetadataScrapeDialog(
                target = target,
                onDismiss = {
                    showMetadataDialog = false
                }
            )
        }
    }

    // 顶部更多菜单
    BookshelfMoreMenu(
        showMoreMenu = showMoreMenu,
        newComicUris = newComicUris,
        onOpenBrowser = {
            showMoreMenu = false
            onOpenBrowser()
        },
        onAddDirectory = {
            showMoreMenu = false
            val initialUri = savedRootUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
            folderLauncher.launch(initialUri)
        },
        onShowHistory = {
            showMoreMenu = false
            onShowHistory()
        },
        onShowHelp = {
            showMoreMenu = false
            onShowHelpCallback()
        },
        onShowContact = {
            showMoreMenu = false
            showContact = true
        },
        onShowStats = {
            showMoreMenu = false
            showStats = true
        },
        onShowLists = {
            showMoreMenu = false
            showLists = true
        },
        onShowWebDav = {
            showMoreMenu = false
            showWebDav = true
        },
        onShowSmbNas = {
            showMoreMenu = false
            Toast.makeText(context, context.getString(R.string.bookshelf_smb_nas_toast), Toast.LENGTH_SHORT).show()
            showSmbNas = true
        },
        onShowTheme = {
            showMoreMenu = false
            showTheme = true
        },
        onClearNewBadges = {
            showMoreMenu = false
            viewModel.clearNewBadges()
            if (filter == BookshelfFilter.NEW) {
                filter = BookshelfFilter.ALL
            }
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.bookshelf_cleared_new_badges_snackbar, newComicUris.size)) }
        },
        onOpenSettings = {
            showMoreMenu = false
            onOpenSettings()
        },
        onShowGitHubStar = {
            showMoreMenu = false
            showGitHubStar = true
        },
        onShowDonation = {
            showMoreMenu = false
            showDonation = true
        },
        onDismiss = { showMoreMenu = false }
    )

    if (showContact) {
        ContactAuthorDialog(onDismiss = { showContact = false })
    }

    if (showStats) {
        ReadingStatsDialog(
            comics = uiState.comics,
            progressMap = progressMap,
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
            progressMap = progressMap,
            bookmarks = bookmarksMap,
            favorites = favorites,
            onDismiss = { showWebDav = false }
        )
    }

    if (showGitHubStar) {
        val ctx = LocalContext.current
        GitHubStarDialog(
            onDismiss = { showGitHubStar = false },
            onOpenGitHub = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(com.mangareader.Constants.GITHUB_REPO_URL))
                    ctx.startActivity(intent)
                    showGitHubStar = false
                } catch (e: Exception) {
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.bookshelf_cannot_open_browser_toast), android.widget.Toast.LENGTH_SHORT).show()
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
        ComicExtrasDialogWrapper(
            comic = comic,
            settings = settings,
            onDismiss = { comicForExtras = null }
        )
    }
}

@Composable
private fun RecentReadsSection(
    comics: List<ComicEntry>,
    progressMap: Map<String, Pair<Int, Int>>,
    onContinue: (ComicEntry) -> Unit
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
                contentDescription = stringResource(R.string.bookshelf_recent_reads),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.bookshelf_recent_reads),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(comics, key = { it.uri.toString() }) { comic ->
                val progress = progressMap[comic.uri.toString()]?.first ?: 0
                val total = progressMap[comic.uri.toString()]?.second ?: comic.totalPages.coerceAtLeast(1)
                RecentReadCard(
                    comic = comic,
                    progressText = "${progress}/${total}P",
                    onClick = { onContinue(comic) }
                )
            }
        }
    }
}

@Composable
private fun RecentReadCard(
    comic: ComicEntry,
    progressText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(100.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // 封面加载失败或尚未提取时，默认显示文件夹占位图标
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = stringResource(R.string.bookshelf_default_cover),
                    modifier = Modifier.size(32.dp)
                )
                CoverAsyncImage(
                    uri = comic.coverUri,
                    contentDescription = comic.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
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

/**
 * 书架顶部「继续阅读」横幅：展示最近一本漫画的封面、标题与阅读进度，
 * 点击后直接回到阅读器（单文件漫画）或分卷浏览（多卷漫画）。
 */
@Composable
private fun ContinueReadingBanner(
    comic: ComicEntry,
    progress: Int,
    total: Int,
    onContinue: () -> Unit
) {
    val progressRatio = (progress.toFloat() / total).coerceIn(0f, 1f)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onContinue),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 90.dp)
                    .clip(RoundedCornerShape(12.dp))
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
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.bookshelf_continue_reading),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = comic.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progressRatio },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.bookshelf_progress_format, progress, total, (progressRatio * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = stringResource(R.string.bookshelf_continue_reading),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * 根据漫画类型决定直接打开阅读器或先进入分卷浏览。
 * 单文件漫画直接续读；多卷/目录漫画保留进入分卷列表的入口。
 */
private fun continueReading(
    comic: ComicEntry,
    onOpenReader: (ComicEntry) -> Unit,
    onOpenComic: (ComicEntry) -> Unit
) {
    if (comic.isDirectory || comic.type == ComicType.COMIC_BOOK) {
        onOpenComic(comic)
    } else {
        onOpenReader(comic)
    }
}

/**
 * 将书架最近一次扫描时间格式化为相对时间提示。
 */
private fun formatBookshelfScanTime(context: Context, lastScanAt: Long): String {
    if (lastScanAt <= 0L) return ""
    val diff = System.currentTimeMillis() - lastScanAt
    return when {
        diff < 60_000L -> context.getString(R.string.bookshelf_scan_just_now)
        diff < 3_600_000L -> context.getString(R.string.bookshelf_scan_minutes_ago, diff / 60_000)
        diff < 86_400_000L -> context.getString(R.string.bookshelf_scan_hours_ago, diff / 3_600_000)
        diff < 7 * 86_400_000L -> context.getString(R.string.bookshelf_scan_days_ago, diff / 86_400_000)
        else -> {
            val fmt = DateTimeFormatter.ofPattern(context.getString(R.string.bookshelf_scan_date_format), Locale.getDefault())
            val dt = Instant.ofEpochMilli(lastScanAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
            fmt.format(dt) + context.getString(R.string.bookshelf_scan_date_suffix)
        }
    }
}

/**
 * 批量分享漫画源文件。通过 FileProvider 将缓存副本以 ACTION_SEND_MULTIPLE 发送，
 * 授予接收应用临时读取权限。
 */
private fun shareComicFiles(context: Context, uris: List<Uri>) {
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.bookshelf_share_chooser)))
    }.onFailure {
        Toast.makeText(context, R.string.bookshelf_share_failed, Toast.LENGTH_SHORT).show()
    }
}
