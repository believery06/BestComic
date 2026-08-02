@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)

package com.mangareader.bookshelf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangareader.R
import com.mangareader.bookshelf.components.ComicCallbacks
import com.mangareader.bookshelf.components.ComicListItem
import com.mangareader.bookshelf.components.ComicCard
import com.mangareader.bookshelf.components.DefaultCoverIcon
import com.mangareader.bookshelf.components.EmptyBookshelf
import com.mangareader.bookshelf.components.EmptyBookshelfState
import com.mangareader.bookshelf.components.NewBadge
import com.mangareader.bookshelf.components.SearchHistoryPanel
import com.mangareader.data.BookshelfScanState
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicType
import com.mangareader.data.ComicExtras
import com.mangareader.data.ReaderSettings
import com.mangareader.data.ReadingStats
import com.mangareader.data.SettingsRepository
import com.mangareader.ui.components.CoverAsyncImage
import com.mangareader.utils.MetadataScraper
import com.mangareader.utils.ComicMetadata
import com.mangareader.utils.WindowSizeClass
import com.mangareader.utils.rememberWindowSizeClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
                TopAppBar(
                    title = {
                        Column {
                            if (isAtRoot) {
                                val currentShelfName = bookshelfUris.getOrNull(currentBookshelfIndex)?.let { uri ->
                                    runCatching { android.net.Uri.parse(uri).lastPathSegment?.substringAfterLast('/') }.getOrNull()
                                } ?: stringResource(R.string.bookshelf_default_shelf_name)
                                TextButton(
                                    onClick = { showBookshelfSwitcher = true },
                                    contentPadding = PaddingValues(horizontal = 0.dp)
                                ) {
                                    Text(currentShelfName, color = MaterialTheme.colorScheme.onSurface)
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = stringResource(R.string.bookshelf_switch_bookshelf),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                val scanHint = formatBookshelfScanTime(context, scanState.lastScanAt)
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
                                    text = stringResource(R.string.bookshelf_path_level, uiState.pathStack.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (!isAtRoot) {
                            IconButton(onClick = { viewModel.navigateUp() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        }
                    },
                actions = {
                    IconButton(onClick = { isSearching = !isSearching; if (!isSearching) searchQuery = "" }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search))
                    }
                    IconButton(onClick = { viewMode = if (viewMode == BookshelfViewMode.GRID) BookshelfViewMode.LIST else BookshelfViewMode.GRID }) {
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
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.bookshelf_more_options))
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
                        .horizontalScroll(rememberScrollState())
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.bookshelf_selected_count, selectedUris.size), modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val uris = selectedUris.mapNotNull { str -> runCatching { Uri.parse(str) }.getOrNull() }
                        viewModel.markAsRead(uris)
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.bookshelf_marked_read_snackbar, uris.size)) }
                        selectionMode = false
                        selectedUris = emptySet()
                    }) { Text(stringResource(R.string.bookshelf_mark_read_label)) }
                    TextButton(onClick = {
                        val uris = selectedUris.mapNotNull { str -> runCatching { Uri.parse(str) }.getOrNull() }
                        viewModel.markAsUnread(uris)
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.bookshelf_marked_unread_snackbar, uris.size)) }
                        selectionMode = false
                        selectedUris = emptySet()
                    }) { Text(stringResource(R.string.bookshelf_mark_unread_label)) }
                    TextButton(onClick = {
                        val uris = selectedUris.mapNotNull { str -> runCatching { Uri.parse(str) }.getOrNull() }
                        scope.launch(Dispatchers.IO) {
                            for (uri in uris) {
                                settings.toggleFavorite(uri)
                            }
                            snackbarHostState.showSnackbar(context.getString(R.string.bookshelf_toggled_favorite_snackbar, uris.size))
                        }
                        selectionMode = false
                        selectedUris = emptySet()
                    }) { Text(stringResource(R.string.bookshelf_favorite)) }
                    TextButton(onClick = {
                        val selectedEntries = uiState.comics.filter { selectedUris.contains(it.uri.toString()) }
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
                    }) { Text(stringResource(R.string.bookshelf_share_label)) }
                    if (categories.isNotEmpty()) {
                        TextButton(onClick = {
                            comicToCategorize = null
                            showComicCategoryDialog = true
                        }) { Text(stringResource(R.string.bookshelf_categorize_label)) }
                    }
                    if (selectedUris.size == 1) {
                        TextButton(onClick = {
                            val uriString = selectedUris.firstOrNull()
                            renameTarget = uiState.comics.find { it.uri.toString() == uriString }
                            showRenameDialog = true
                        }) { Text(stringResource(R.string.bookshelf_rename_label)) }
                    }
                    TextButton(onClick = {
                        showDeleteConfirm = true
                    }) { Text(stringResource(R.string.bookshelf_delete_label), color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = {
                        selectionMode = false
                        selectedUris = emptySet()
                    }) { Text(stringResource(R.string.bookshelf_cancel_label)) }
                }
            }
            // 搜索栏
            val focusManager = LocalFocusManager.current
            AnimatedVisibility(visible = isSearching) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    placeholder = { Text(stringResource(R.string.bookshelf_search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear))
                            }
                        } else if (searchHistory.isNotEmpty()) {
                            IconButton(onClick = { scope.launch { settings.clearBookshelfSearchHistory() } }) {
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
                                scope.launch { settings.addBookshelfSearchHistory(q) }
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
                    onItemClick = { item ->
                        searchQuery = item
                        scope.launch { settings.addBookshelfSearchHistory(item) }
                    },
                    onClear = { scope.launch { settings.clearBookshelfSearchHistory() } },
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
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            val hasActiveFilters = filter != BookshelfFilter.ALL
                    || sortBy != BookshelfSort.NAME
                    || selectedCategory != null
                    || searchQuery.isNotBlank()
                    || isSearching
            // Filter / sort row
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
                            onClick = {
                                filter = BookshelfFilter.ALL
                                sortBy = BookshelfSort.NAME
                                selectedCategory = null
                                searchQuery = ""
                                isSearching = false
                            },
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
                        onClick = { filter = BookshelfFilter.ALL },
                        label = { Text(stringResource(R.string.bookshelf_all)) }
                    )
                    FilterChip(
                        selected = filter == BookshelfFilter.FAVORITES,
                        onClick = { filter = BookshelfFilter.FAVORITES },
                        label = { Text(stringResource(R.string.bookshelf_favorites)) }
                    )
                    FilterChip(
                        selected = filter == BookshelfFilter.UNREAD,
                        onClick = { filter = BookshelfFilter.UNREAD },
                        label = { Text(stringResource(R.string.bookshelf_unread)) }
                    )
                    FilterChip(
                        selected = filter == BookshelfFilter.READING,
                        onClick = { filter = BookshelfFilter.READING },
                        label = { Text(stringResource(R.string.bookshelf_reading)) }
                    )
                    FilterChip(
                        selected = filter == BookshelfFilter.READ,
                        onClick = { filter = BookshelfFilter.READ },
                        label = { Text(stringResource(R.string.bookshelf_read)) }
                    )
                    FilterChip(
                        selected = filter == BookshelfFilter.NEW,
                        onClick = { filter = BookshelfFilter.NEW },
                        label = {
                            Text(
                                if (newComicUris.isNotEmpty()) stringResource(R.string.bookshelf_new_added_count, newComicUris.size)
                                else stringResource(R.string.bookshelf_new_added)
                            )
                        },
                        leadingIcon = if (newComicUris.isNotEmpty()) {
                            { NewBadge() }
                        } else null
                    )
                }
                IconButton(onClick = {
                    sortBy = when (sortBy) {
                        BookshelfSort.NAME -> BookshelfSort.RECENT
                        BookshelfSort.RECENT -> BookshelfSort.PROGRESS
                        BookshelfSort.PROGRESS -> BookshelfSort.NAME
                    }
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
                        label = { Text(stringResource(R.string.bookshelf_all_categories)) }
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
                        Icon(Icons.Default.Category, contentDescription = stringResource(R.string.bookshelf_manage_categories))
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = { showCategoryDialog = true }) {
                        Icon(Icons.Default.Category, contentDescription = stringResource(R.string.bookshelf_manage_categories))
                    }
                }
            }


            val pullRefreshState = rememberPullRefreshState(
                refreshing = uiState.isLoading,
                onRefresh = { viewModel.refresh() }
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pullRefresh(pullRefreshState)
            ) {
                when {
                    uiState.error != null -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.bookshelf_error_prefix, uiState.error ?: ""),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(onClick = { viewModel.refresh() }) {
                                Text(stringResource(R.string.bookshelf_error_retry))
                            }
                        }
                    }
                        uiState.rootUri == null -> {
                        EmptyBookshelf(
                            onSelectFolder = { folderLauncher.launch(null) },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    filteredComics.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.bookshelf_no_comics_in_folder),
                            modifier = Modifier.align(Alignment.Center),
                            textAlign = TextAlign.Center
                        )
                    }
                    else -> {
                        Column(modifier = Modifier.fillMaxSize()) {
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
                                            } else if (comic.isDirectory) {
                                                viewModel.openDirectory(comic.uri)
                                            } else {
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
                                            scrapedMetadata = null
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

                            if (filteredComics.isEmpty() && !uiState.isLoading) {
                                EmptyBookshelfState(
                                    hasSearch = debouncedSearchQuery.isNotBlank(),
                                    hasFilter = filter != BookshelfFilter.ALL || selectedCategory != null,
                                    isRoot = isAtRoot,
                                    onClearFilter = {
                                        filter = BookshelfFilter.ALL
                                        selectedCategory = null
                                        searchQuery = ""
                                    },
                                    onOpenBrowser = onOpenBrowser
                                )
                            } else if (viewMode == BookshelfViewMode.GRID) {
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
                                            readingStatus = viewModel.getReadingStatus(comic.uri),
                                            progress = progress,
                                            total = total,
                                            isSelected = selectedUris.contains(comic.uri.toString()),
                                            selectionMode = selectionMode,
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
                                            readingStatus = viewModel.getReadingStatus(comic.uri),
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
                    refreshing = uiState.isLoading,
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }
}

    // 分类管理对话框
    if (showCategoryDialog) {
        var newCategoryName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text(stringResource(R.string.bookshelf_manage_categories_title)) },
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
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = newCategoryName,
                        onValueChange = { newCategoryName = it },
                        label = { Text(stringResource(R.string.bookshelf_new_category_name)) },
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
                            withContext(Dispatchers.Main) {
                                snackbarHostState.showSnackbar(context.getString(R.string.bookshelf_category_added_snackbar, trimmed))
                            }
                        }
                        newCategoryName = ""
                        showCategoryDialog = false
                    }
                }) { Text(stringResource(R.string.bookshelf_add_label)) }
            },
            dismissButton = {
                TextButton(onClick = { showCategoryDialog = false }) { Text(stringResource(R.string.bookshelf_close_label)) }
            }
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
        val title = if (singleComic != null) {
            stringResource(R.string.bookshelf_categorize_comic_title, singleComic.title)
        } else {
            stringResource(R.string.bookshelf_categorize_multi_title, urisToCategorize.size)
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
                    items(categories, key = { it }) { cat ->
                        val currentCats = if (singleComic != null) {
                            comicCategories[singleComic.uri.toString()] ?: emptySet()
                        } else {
                            urisToCategorize.mapNotNull { comicCategories[it.toString()] }.flatten().toSet()
                        }
                        val isInCategory = currentCats.contains(cat)
                        val allInCategory = if (singleComic != null) isInCategory else {
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
                }) { Text(stringResource(R.string.bookshelf_done_label)) }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.bookshelf_delete_confirm_title)) },
            text = { Text(stringResource(R.string.bookshelf_delete_confirm_message, selectedUris.size)) },
            confirmButton = {
                TextButton(onClick = {
                    val uris = selectedUris.mapNotNull { str -> runCatching { Uri.parse(str) }.getOrNull() }
                    viewModel.removeComics(uris)
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.bookshelf_deleted_snackbar, uris.size)) }
                    showDeleteConfirm = false
                    selectionMode = false
                    selectedUris = emptySet()
                }) { Text(stringResource(R.string.bookshelf_delete_label), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.bookshelf_cancel_label)) }

            }
        )
    }

    if (showRenameDialog && renameTarget != null) {
        var newName by remember(renameTarget) { mutableStateOf(renameTarget?.title?.substringBeforeLast('.') ?: "") }
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = false
                renameTarget = null
            },
            title = { Text(stringResource(R.string.bookshelf_rename_title)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.bookshelf_new_file_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = renameTarget ?: return@TextButton
                        val ext = target.title.substringAfterLast('.', "")
                        val finalName = if (ext.isNotBlank()) "$newName.$ext" else newName
                        if (finalName.isNotBlank() && finalName != target.title) {
                            viewModel.renameComic(target.uri, finalName) { success ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (success) context.getString(R.string.bookshelf_renamed_snackbar, finalName) else context.getString(R.string.bookshelf_rename_failed_snackbar)
                                    )
                                }
                            }
                        }
                        showRenameDialog = false
                        renameTarget = null
                        selectionMode = false
                        selectedUris = emptySet()
                    },
                    enabled = newName.isNotBlank()
                ) { Text(stringResource(R.string.bookshelf_ok_label)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    renameTarget = null
                }) { Text(stringResource(R.string.bookshelf_cancel_label)) }
            }
        )
    }

    // 多书架切换对话框
    if (showBookshelfSwitcher) {
        AlertDialog(
            onDismissRequest = { showBookshelfSwitcher = false },
            title = { Text(stringResource(R.string.bookshelf_switch_bookshelf_title)) },
            text = {
                Column {
                    if (bookshelfUris.isEmpty()) {
                        Text(stringResource(R.string.bookshelf_no_bookshelf_added), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        bookshelfUris.forEachIndexed { index, uriString ->
                            val name = runCatching {
                                android.net.Uri.parse(uriString).lastPathSegment?.substringAfterLast('/')
                            }.getOrNull() ?: uriString
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showBookshelfSwitcher = false
                                        viewModel.switchBookshelf(index)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = index == currentBookshelfIndex,
                                    onClick = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(onClick = {
                                    viewModel.removeBookshelf(index)
                                    if (bookshelfUris.size <= 1) showBookshelfSwitcher = false
                                }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.bookshelf_remove_bookshelf),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showBookshelfSwitcher = false
                    val initialUri = savedRootUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    folderLauncher.launch(initialUri)
                }) { Text(stringResource(R.string.bookshelf_add_bookshelf_label)) }
            },
            dismissButton = {
                TextButton(onClick = { showBookshelfSwitcher = false }) { Text(stringResource(R.string.bookshelf_close_label)) }
            }
        )
    }

    // 元数据刮削对话框
    if (showMetadataDialog) {
        metadataTarget?.let { target ->
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
                    } catch (_: CancellationException) {
                        // 对话框关闭时协程被取消，无需处理
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
                title = { Text(stringResource(R.string.bookshelf_metadata_scrape_title)) },
                text = {
                    if (isScraping) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.bookshelf_metadata_querying))
                        }
                    } else if (meta != null) {
                        Column {
                            Text(stringResource(R.string.bookshelf_metadata_file_label, target.title), style = MaterialTheme.typography.bodySmall)
                            if (meta.title.isNotEmpty()) Text(stringResource(R.string.bookshelf_metadata_title_label, meta.title))
                            if (meta.author.isNotEmpty()) Text(stringResource(R.string.bookshelf_metadata_author_label, meta.author))
                            if (meta.volume > 0) Text(stringResource(R.string.bookshelf_metadata_volume_label, meta.volume))
                            if (meta.chapter.isNotEmpty()) Text(stringResource(R.string.bookshelf_metadata_chapter_label, meta.chapter))
                            if (meta.year > 0) Text(stringResource(R.string.bookshelf_metadata_year_label, meta.year))
                            if (meta.description.isNotEmpty()) Text(stringResource(R.string.bookshelf_metadata_description_label, meta.description.take(200)))
                            if (meta.tags.isNotEmpty()) Text(stringResource(R.string.bookshelf_metadata_tags_label, meta.tags.take(5).joinToString(", ")))
                            Text(stringResource(R.string.bookshelf_metadata_source_label, meta.source), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(stringResource(R.string.bookshelf_metadata_not_found), color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showMetadataDialog = false
                        scrapedMetadata = null
                    }) {
                        Text(stringResource(R.string.bookshelf_close_label))
                    }
                }
            )
        }
    }

    // 顶部更多菜单：浏览、添加书架、阅读历史、帮助、联系作者、阅读统计、我的书单、WebDAV、主题
    DropdownMenu(
        expanded = showMoreMenu,
        onDismissRequest = { showMoreMenu = false }
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_browser)) },
            leadingIcon = { Icon(Icons.Default.Explore, contentDescription = stringResource(R.string.bookshelf_browser)) },
            onClick = {
                showMoreMenu = false
                onOpenBrowser()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_add_directory)) },
            leadingIcon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.bookshelf_add_directory)) },
            onClick = {
                showMoreMenu = false
                val initialUri = savedRootUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
                folderLauncher.launch(initialUri)
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_read_history)) },
            leadingIcon = { Icon(Icons.Default.History, contentDescription = stringResource(R.string.bookshelf_read_history)) },
            onClick = {
                showMoreMenu = false
                onShowHistory()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_help)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Help, contentDescription = stringResource(R.string.bookshelf_help)) },
            onClick = {
                showMoreMenu = false
                onShowHelpCallback()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_contact_author)) },
            leadingIcon = { Icon(Icons.Default.ContactMail, contentDescription = stringResource(R.string.bookshelf_contact_author)) },
            onClick = {
                showMoreMenu = false
                showContact = true
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_reading_stats)) },
            leadingIcon = { Icon(Icons.Default.BarChart, contentDescription = stringResource(R.string.bookshelf_reading_stats)) },
            onClick = {
                showMoreMenu = false
                showStats = true
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_reading_lists)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = stringResource(R.string.bookshelf_reading_lists)) },
            onClick = {
                showMoreMenu = false
                showLists = true
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_webdav_sync)) },
            leadingIcon = { Icon(Icons.Default.CloudSync, contentDescription = stringResource(R.string.bookshelf_webdav_sync)) },
            onClick = {
                showMoreMenu = false
                showWebDav = true
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_smb_nas)) },
            leadingIcon = { Icon(Icons.Default.Storage, contentDescription = stringResource(R.string.bookshelf_smb_nas)) },
            onClick = {
                showMoreMenu = false
                Toast.makeText(context, context.getString(R.string.bookshelf_smb_nas_toast), Toast.LENGTH_SHORT).show()
                showSmbNas = true
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_theme)) },
            leadingIcon = { Icon(Icons.Default.Palette, contentDescription = stringResource(R.string.bookshelf_theme)) },
            onClick = {
                showMoreMenu = false
                showTheme = true
            }
        )
        if (newComicUris.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bookshelf_clear_new_badges)) },
                leadingIcon = { Icon(Icons.Default.NewReleases, contentDescription = stringResource(R.string.bookshelf_clear_new_badges)) },
                onClick = {
                    showMoreMenu = false
                    viewModel.clearNewBadges()
                    if (filter == BookshelfFilter.NEW) {
                        filter = BookshelfFilter.ALL
                    }
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.bookshelf_cleared_new_badges_snackbar, newComicUris.size)) }
                }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_settings)) },
            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.bookshelf_settings)) },
            onClick = {
                showMoreMenu = false
                onOpenSettings()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_github_star)) },
            leadingIcon = { Icon(Icons.Default.Star, contentDescription = stringResource(R.string.bookshelf_github_star)) },
            onClick = {
                showMoreMenu = false
                showGitHubStar = true
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_donation)) },
            leadingIcon = { Icon(Icons.Default.Star, contentDescription = stringResource(R.string.bookshelf_donation)) },
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
