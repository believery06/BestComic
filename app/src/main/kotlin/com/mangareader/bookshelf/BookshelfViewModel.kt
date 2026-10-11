package com.mangareader.bookshelf

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.Immutable
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mangareader.R
import com.mangareader.data.BookshelfScanState
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicType
import com.mangareader.data.SettingsRepository
import com.mangareader.parser.FolderParser
import com.mangareader.parser.ParserFactory
import com.mangareader.parser.VolumeSortHelper
import com.mangareader.utils.CoverExtractor
import com.mangareader.utils.FileListHelper
import com.mangareader.utils.FileManager
import com.mangareader.utils.ParallelCoverExtractor
import com.mangareader.utils.ShareSourcePreparer
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import java.util.concurrent.atomic.AtomicInteger

class BookshelfViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository.getInstance(application)
    private val shareSourcePreparer = ShareSourcePreparer(application)

    @Immutable
    data class UiState(
        val rootUri: Uri? = null,
        val currentUri: Uri? = null,
        val pathStack: List<Uri> = emptyList(),
        val comics: List<ComicEntry> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val newComicUris: Set<String> = emptySet()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // 独立进度流：阅读进度更新不应触发 comics 列表整体重组
    private val _progressMap = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val progressMap: StateFlow<Map<String, Pair<Int, Int>>> = _progressMap.asStateFlow()

    // 独立最近阅读流：时间戳更新不应触发 comics 列表整体重组
    private val _recentReads = MutableStateFlow<Map<String, Long>>(emptyMap())
    val recentReads: StateFlow<Map<String, Long>> = _recentReads.asStateFlow()

    /**
     * 书架相关设置聚合态，将多个 DataStore Flow 合并为单一 Flow。
     * 避免 BookshelfScreen 同时收集 10+ 个独立 Flow 导致的频繁重组。
     */
    @Immutable
    data class SettingsState(
        val favorites: Set<String> = emptySet(),
        val categories: List<String> = emptyList(),
        val comicCategories: Map<String, Set<String>> = emptyMap(),
        val bookmarksMap: Map<String, List<Int>> = emptyMap(),
        val stats: com.mangareader.data.ReadingStats = com.mangareader.data.ReadingStats(),
        val extrasMap: Map<String, com.mangareader.data.ComicExtras> = emptyMap(),
        val readingLists: List<com.mangareader.data.ReadingList> = emptyList(),
        val readerSettings: com.mangareader.data.ReaderSettings = com.mangareader.data.ReaderSettings(),
        val bookshelfUris: List<String> = emptyList(),
        val currentBookshelfIndex: Int = 0
    )

    private val _settingsState = MutableStateFlow(SettingsState())
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null
    private var coverJob: kotlinx.coroutines.Job? = null
    private var lastAutoRefreshTime: Long = 0L
    /** 书架加载世代计数器，用于在快速连续切换书架时丢弃旧任务的状态更新。 */
    private val loadGeneration = AtomicInteger(0)

    companion object {
        private const val TAG = "BookshelfViewModel"
        /** 前台自动刷新最小间隔（毫秒），避免频繁刷新大书架。 */
        private const val MIN_AUTO_REFRESH_INTERVAL_MS = 60L * 1000
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            migrateLegacyBookshelf()
            val uris = settingsRepository.getBookshelfUris().first()
            val index = settingsRepository.getCurrentBookshelfIndex().first()
            val target = uris.getOrNull(index)
                ?: uris.firstOrNull()
                ?: settingsRepository.getBookshelfRoot().first()
            if (target != null) {
                runCatching { Uri.parse(target) }.getOrNull()?.let { uri ->
                    loadBookshelf(uri, resetStack = true)
                }
            }
        }
        // 收集所有漫画的阅读进度，用于计算阅读状态（独立流，避免触发 comics 重组）
        viewModelScope.launch {
            settingsRepository.getAllProgress().collect { progressMap ->
                _progressMap.value = progressMap
            }
        }
        // 收集最近阅读时间戳，用于书架「最近阅读」排序（独立流）
        viewModelScope.launch {
            settingsRepository.getRecentReads().collect { recentReads ->
                _recentReads.value = recentReads
            }
        }
        // 将书架相关的多个 DataStore Flow 聚合为单一 SettingsState，减少 UI 层重组。
        viewModelScope.launch {
            combine(
                settingsRepository.getFavorites(),
                settingsRepository.getCategories(),
                settingsRepository.getComicCategories(),
                settingsRepository.getBookmarks(),
                settingsRepository.getReadingStats(),
                settingsRepository.getAllComicExtras(),
                settingsRepository.getReadingLists(),
                settingsRepository.readerSettings,
                settingsRepository.getBookshelfUris(),
                settingsRepository.getCurrentBookshelfIndex()
            ) { arrays ->
                @Suppress("UNCHECKED_CAST")
                SettingsState(
                    favorites = arrays[0] as Set<String>,
                    categories = arrays[1] as List<String>,
                    comicCategories = arrays[2] as Map<String, Set<String>>,
                    bookmarksMap = arrays[3] as Map<String, List<Int>>,
                    stats = arrays[4] as com.mangareader.data.ReadingStats,
                    extrasMap = arrays[5] as Map<String, com.mangareader.data.ComicExtras>,
                    readingLists = arrays[6] as List<com.mangareader.data.ReadingList>,
                    readerSettings = arrays[7] as com.mangareader.data.ReaderSettings,
                    bookshelfUris = arrays[8] as List<String>,
                    currentBookshelfIndex = arrays[9] as Int
                )
            }.collect { _settingsState.value = it }
        }
    }

    /**
     * 将旧版单一书架根目录迁移到多书架列表中。
     */
    private suspend fun migrateLegacyBookshelf() {
        val legacy = settingsRepository.getBookshelfRoot().first() ?: return
        val list = settingsRepository.getBookshelfUris().first().toMutableList()
        if (!list.contains(legacy)) {
            list.add(0, legacy)
            settingsRepository.saveBookshelfUris(list)
        }
    }

    fun getReadingStatus(uri: Uri): com.mangareader.data.ReadingStatus {
        val (progress, total) = _progressMap.value[uri.toString()] ?: return com.mangareader.data.ReadingStatus.UNREAD
        return when {
            total <= 0 || progress <= 0 -> com.mangareader.data.ReadingStatus.UNREAD
            progress >= total - 1 -> com.mangareader.data.ReadingStatus.READ
            else -> com.mangareader.data.ReadingStatus.READING
        }
    }

    fun markAsRead(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (uri in uris) {
                val total = _progressMap.value[uri.toString()]?.second ?: 0
                settingsRepository.markAsRead(uri, total.coerceAtLeast(1))
            }
        }
    }

    fun markAsUnread(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (uri in uris) {
                settingsRepository.markAsUnread(uri)
            }
        }
    }

    fun removeComics(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            for (uri in uris) {
                try {
                    when (uri.scheme) {
                        "file" -> {
                            // 使用 FileManager 规范化路径，避免符号链接/.. 导致误删或失败
                            val file = FileManager.toSafeFile(uri) ?: continue
                            if (file.isDirectory) file.deleteRecursively() else file.delete()
                        }
                        "content" -> {
                            val doc = DocumentFile.fromTreeUri(ctx, uri)
                                ?: DocumentFile.fromSingleUri(ctx, uri)
                            if (doc != null && !doc.delete()) {
                                Log.w(TAG, "删除 content URI 漫画失败: $uri")
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "删除漫画失败", e)
                }
            }
            FileListHelper.clearCache()
            _uiState.value.currentUri?.let { loadBookshelf(it) }
        }
    }

    /**
     * 重命名指定漫画文件或文件夹。
     * [callback] 在主线程返回是否成功；即使协程被取消也会回调 false，避免 UI 状态悬挂。
     */
    fun renameComic(uri: Uri, newName: String, callback: (Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            var success = false
            try {
                // 统一走 FileManager.rename：内部会净化文件名，拦截 ../、路径分隔符等，
                // 避免文件被移出目录或静默覆盖；同时返回重命名后的新 URI。
                val newUri = FileManager.rename(getApplication(), uri, newName)
                if (newUri != null) {
                    // 所有用户数据均以 URI 为键，重命名后必须整体迁移，
                    // 否则进度/收藏/分类/书签/短评/历史会全部丢失。
                    settingsRepository.migrateComicUriData(uri, newUri)
                    success = true
                    FileListHelper.clearCache()
                    _uiState.value.currentUri?.let { loadBookshelf(it) }
                }
            } catch (e: CancellationException) {
                success = false
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "重命名漫画失败", e)
                success = false
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    callback(success)
                }
            }
        }
    }

    /**
     * 添加新的书架目录并切换到该书架。
     */
    fun addBookshelf(rootUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val uriString = rootUri.toString()
            settingsRepository.addBookshelfUri(uriString)
            val list = settingsRepository.getBookshelfUris().first()
            val index = list.indexOf(uriString).coerceAtLeast(0)
            settingsRepository.saveCurrentBookshelfIndex(index)
            loadBookshelf(rootUri, resetStack = true)
        }
    }

    /**
     * 切换到指定索引的书架。
     */
    fun switchBookshelf(index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = settingsRepository.getBookshelfUris().first()
            val uriString = list.getOrNull(index) ?: return@launch
            settingsRepository.saveCurrentBookshelfIndex(index)
            runCatching { Uri.parse(uriString) }.getOrNull()?.let { uri ->
                loadBookshelf(uri, resetStack = true)
            }
        }
    }

    /**
     * 移除指定索引的书架。若移除后无书架，则清空当前状态。
     */
    fun removeBookshelf(index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = settingsRepository.getBookshelfUris().first().toMutableList()
            if (index !in list.indices) return@launch
            // 以「当前选中书架」为基准计算删除后的下标：
            // 删除当前项之前的书架 → 当前项下移一位；之后的书架 → 下标不变。
            // 旧实现恒跳转到被删除位置，删除非当前书架时会切错书架。
            val current = settingsRepository.getCurrentBookshelfIndex().first()
                .coerceIn(0, (list.size - 1).coerceAtLeast(0))
            list.removeAt(index)
            settingsRepository.saveBookshelfUris(list)
            val newIndex = when {
                list.isEmpty() -> -1
                index < current -> current - 1
                index > current -> current
                else -> index.coerceIn(0, list.size - 1)
            }.coerceIn(-1, list.size - 1)
            settingsRepository.saveCurrentBookshelfIndex(newIndex)
            if (newIndex >= 0) {
                runCatching { Uri.parse(list[newIndex]) }.getOrNull()?.let { loadBookshelf(it, resetStack = true) }
            } else {
                withContext(Dispatchers.Main) { _uiState.value = UiState() }
            }
        }
    }

    fun refresh() {
        _uiState.value.currentUri?.let { loadBookshelf(it) }
    }

    /**
     * 应用回到前台时调用。超过冷却时间则自动刷新书架以检测新文件。
     */
    fun maybeAutoRefresh() {
        val now = System.currentTimeMillis()
        if (now - lastAutoRefreshTime < MIN_AUTO_REFRESH_INTERVAL_MS) return
        lastAutoRefreshTime = now
        refresh()
    }

    /**
     * 清除当前书架所有 NEW 角标。
     */
    fun clearNewBadges() {
        val root = _uiState.value.rootUri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val currentFiles = _uiState.value.comics.map { it.uri.toString() }.toSet()
            settingsRepository.markBookshelfFilesSeen(root.toString(), currentFiles)
            withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(newComicUris = emptySet()) }
        }
    }

    /**
     * 将指定漫画标记为已查看，移除 NEW 角标。
     */
    fun markComicSeen(uriString: String) {
        _uiState.value = _uiState.value.copy(
            newComicUris = _uiState.value.newComicUris - uriString
        )
    }

    /**
     * 检测当前书架中的新文件，并更新扫描状态。
     * 新文件判定：未在 knownFiles 中，或文件修改时间晚于上次扫描时间。
     */
    private suspend fun detectNewComics(rootUri: Uri, comics: List<ComicEntry>) {
        try {
            val previous = settingsRepository.getBookshelfScanState(rootUri.toString()).first()
            val now = System.currentTimeMillis()
            val currentFiles = comics.map { it.uri.toString() }.toSet()

            // 首次扫描（lastScanAt == 0）时只建立基线，不把所有文件都标成 NEW，
            // 避免首次安装或首次添加书架时满屏角标。
            val newUris = if (previous.lastScanAt <= 0L) {
                emptySet()
            } else {
                comics.filter { comic ->
                    val uriString = comic.uri.toString()
                    if (!previous.knownFiles.contains(uriString)) return@filter true
                    val lastModified = getLastModified(comic.uri)
                    lastModified > previous.lastScanAt
                }.map { it.uri.toString() }.toSet()
            }

            withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(newComicUris = newUris) }

            // 更新扫描状态：以本次实际扫描到的文件为基底，避免已删除文件的 URI
            // 永久残留在 knownFiles 中导致该条目随使用时间无限膨胀。
            settingsRepository.saveBookshelfScanState(
                rootUri.toString(),
                BookshelfScanState(lastScanAt = now, knownFiles = currentFiles)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "新文件检测失败", e)
        }
    }

    private fun getLastModified(uri: Uri): Long {
        val ctx = getApplication<Application>()
        return when (uri.scheme) {
            "file" -> {
                // 使用 FileManager 规范化路径后再读取修改时间
                FileManager.toSafeFile(uri)?.lastModified() ?: 0L
            }
            "content" -> {
                runCatching {
                    DocumentFile.fromSingleUri(ctx, uri)?.lastModified() ?: 0L
                }.getOrDefault(0L)
            }
            else -> 0L
        }
    }

    /**
     * 进入指定目录（书架文件系统层级浏览）。
     * 防止因符号链接/循环目录结构导致的无限嵌套。
     */
    fun openDirectory(dirUri: Uri) {
        val current = _uiState.value.currentUri
        val dirKey = dirUri.toString()
        // 当前目录或已访问过的目录禁止再次进入
        if (current != null && current.toString() == dirKey) return
        if (_uiState.value.pathStack.any { it.toString() == dirKey }) return
        val newStack = current?.let { _uiState.value.pathStack + it } ?: _uiState.value.pathStack
        loadBookshelf(dirUri, newStack = newStack)
    }

    /**
     * 返回上一级目录。
     */
    fun navigateUp(): Boolean {
        val stack = _uiState.value.pathStack
        if (stack.isEmpty()) return false
        val parent = stack.last()
        loadBookshelf(parent, newStack = stack.dropLast(1))
        return true
    }

    /**
     * 回到书架根目录。
     */
    fun navigateToRoot() {
        _uiState.value.rootUri?.let { loadBookshelf(it, resetStack = true) }
    }

    fun loadBookshelf(targetUri: Uri, resetStack: Boolean = false, newStack: List<Uri>? = null) {
        loadJob?.cancel()
        coverJob?.cancel()
        val currentGeneration = loadGeneration.incrementAndGet()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val root = if (resetStack) targetUri else _uiState.value.rootUri
            val stack = when {
                resetStack -> emptyList()
                newStack != null -> newStack
                else -> _uiState.value.pathStack
            }
            // 若已有更新的加载任务启动，则直接丢弃本任务，避免旧任务覆盖新状态。
            if (currentGeneration != loadGeneration.get()) return@launch
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    rootUri = root,
                    currentUri = targetUri,
                    pathStack = stack,
                    error = null,
                    newComicUris = emptySet()
                )
            }
            try {
                runCatching { ensureNoMediaFile(targetUri) }

                val comics = when (targetUri.scheme) {
                    "file", "content" -> loadDirectory(targetUri)
                    else -> emptyList()
                }
                val sorted = comics.sortedWith { a, b -> VolumeSortHelper.compareNames(a.title, b.title) }
                if (currentGeneration != loadGeneration.get()) return@launch
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        comics = sorted,
                        isLoading = false,
                        error = null
                    )
                }

                // 检测新加入的漫画，仅针对当前书架根目录
                root?.let { detectNewComics(it, sorted) }

                // Extract covers asynchronously for items that don't have one yet
                extractCovers(sorted)
            } catch (e: CancellationException) {
                // 被取消的任务不应覆盖新任务的加载状态
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "加载书架失败", e)
                if (currentGeneration != loadGeneration.get()) return@launch
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: getApplication<Application>().getString(R.string.bookshelf_unknown_error)
                    )
                }
            } finally {
                // 若当前任务仍是最新任务且被取消，确保加载状态复位，避免刷新指示器卡住。
                if (currentGeneration == loadGeneration.get() && _uiState.value.isLoading) {
                    withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(isLoading = false) }
                }
            }
        }
    }

    private suspend fun loadDirectory(rootUri: Uri): List<ComicEntry> {
        val items = mutableListOf<ComicEntry>()
        val children = FileListHelper.listChildren(getApplication(), rootUri)

        for (child in children) {
            if (child.name.startsWith(".")) continue

            if (child.isDirectory) {
                val (hasChapter, hasImage) = inspectDirectory(child.uri)
                if (hasChapter || hasImage) {
                    val type = if (hasChapter) ComicType.COMIC_BOOK else ComicType.FOLDER
                    items.add(
                        ComicEntry(
                            uri = child.uri,
                            title = child.name,
                            type = type,
                            coverUri = null,
                            isDirectory = true,
                            path = child.uri.toString()
                        )
                    )
                }
            } else {
                val type = ParserFactory.detectType(child.name)
                if (type != ComicType.UNKNOWN) {
                    items.add(
                        ComicEntry(
                            uri = child.uri,
                            title = child.name,
                            type = type,
                            coverUri = null,
                            isDirectory = false,
                            path = child.uri.toString()
                        )
                    )
                }
            }
        }

        // 如果当前目录本身包含图片文件（但没有子文件夹或子文件夹无漫画），
        // 将当前目录本身作为一个可直接打开的漫画条目（不再允许点击进入自身）
        if (items.isEmpty()) {
            val hasImages = children.any { !it.isDirectory && ParserFactory.isImageFile(it.name) }
            if (hasImages) {
                items.add(
                    ComicEntry(
                        uri = rootUri,
                        title = rootUri.lastPathSegment?.substringAfterLast('/') ?: getApplication<Application>().getString(R.string.bookshelf_current_directory),
                        type = ComicType.FOLDER,
                        coverUri = null,
                        isDirectory = false,
                        path = rootUri.toString()
                    )
                )
            }
        }

        return items
    }

    /**
     * 在后台为所有漫画提取封面，完成后批量更新 UI，避免每本漫画都触发书架重组。
     */
    private fun extractCovers(comics: List<ComicEntry>) {
        coverJob?.cancel()
        val app = getApplication<android.app.Application>()
        val needExtraction = comics.filter { it.coverUri == null }
        if (needExtraction.isEmpty()) return

        coverJob = viewModelScope.launch(Dispatchers.IO) {
            // 使用 uri -> coverUri 映射批量收集，避免每本漫画都触发一次书架重组
            val coverUpdates = mutableMapOf<android.net.Uri, android.net.Uri?>()

            // 分离目录类型和非目录类型
            val directoryComics = needExtraction.filter { it.isDirectory }
            val fileComics = needExtraction.filter { !it.isDirectory }

            // 处理非目录类型（CBZ, CBR, CB7, CBT, PDF, EPUB）
            try {
                ParallelCoverExtractor.extractCoversParallel(app, fileComics) { entry, coverUri ->
                    if (coverUri != null) {
                        coverUpdates[entry.uri] = coverUri
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "并行封面提取失败", e)
            }

            // 处理目录类型（FOLDER, COMIC_BOOK）- 使用 findCover
            for (entry in directoryComics) {
                val coverUri = findCover(entry.uri, entry.type)
                if (coverUri != null) {
                    coverUpdates[entry.uri] = coverUri
                }
            }

            // 批量应用封面更新，仅触发一次 UI 重组
            if (coverUpdates.isNotEmpty()) {
                val updated = comics.map { entry ->
                    coverUpdates[entry.uri]?.let { entry.copy(coverUri = it) } ?: entry
                }
                withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(comics = updated) }
            }
        }
    }

    private fun inspectDirectory(dirUri: Uri): Pair<Boolean, Boolean> {
        val children = FileListHelper.listChildren(getApplication(), dirUri)
        var hasChapter = false
        var hasImage = false
        for (child in children) {
            if (child.isDirectory) continue
            val type = ParserFactory.detectType(child.name)
            if (type != ComicType.UNKNOWN) hasChapter = true
            if (ParserFactory.isImageFile(child.name)) hasImage = true
            if (hasChapter) break
        }
        return Pair(hasChapter, hasImage)
    }

    private suspend fun findCover(dirUri: Uri, type: ComicType): Uri? {
        val children = FileListHelper.listChildren(getApplication(), dirUri)
            .filter { !it.isDirectory }
            .sortedWith { a, b -> VolumeSortHelper.compareNames(a.name, b.name) }

        if (type == ComicType.FOLDER) {
            // 优先取排序后的第一张图片；若失败则继续尝试后续图片
            for (child in children.filter { ParserFactory.isImageFile(it.name) }) {
                val uri = CoverExtractor.extractLocalImageThumbnail(getApplication(), child.uri)
                if (uri != null) return uri
            }
            return null
        }

        if (type == ComicType.COMIC_BOOK) {
            // 按排序逐个尝试分卷封面，避免首卷解析失败导致整本漫画无封面
            val chapters = children.filter { ParserFactory.detectType(it.name) != ComicType.UNKNOWN }
            for (chapter in chapters) {
                val chapterType = ParserFactory.detectType(chapter.name)
                val uri = CoverExtractor.extractCover(getApplication(), chapter.uri, chapterType)
                if (uri != null) return uri
            }
            return null
        }

        return null
    }

    private fun ensureNoMediaFile(rootUri: Uri) {
        val ctx = getApplication<android.app.Application>()
        when (rootUri.scheme) {
            "file" -> {
                // 使用 FileManager 规范化路径后再创建 .nomedia，避免 .. 导致写入到错误目录
                val dir = FileManager.toSafeFile(rootUri) ?: return
                val file = File(dir, ".nomedia")
                if (!file.exists()) {
                    runCatching { file.createNewFile() }
                }
            }
            "content" -> {
                val doc = DocumentFile.fromTreeUri(ctx, rootUri) ?: return
                if (!doc.listFiles().any { it.name == ".nomedia" }) {
                    runCatching {
                        DocumentsContract.createDocument(
                            ctx.contentResolver,
                            doc.uri,
                            "application/octet-stream",
                            ".nomedia"
                        )
                    }
                }
            }
        }
    }

    /**
     * 将选中的漫画源文件批量复制到应用缓存的 source_share 目录，并返回可通过 FileProvider 分享的 URI 列表。
     * 具体复制、大小检查与文件名净化逻辑统一委托给 [ShareSourcePreparer]。
     */
    suspend fun prepareShareUris(entries: List<ComicEntry>): List<Uri> {
        return shareSourcePreparer.prepareBatch(entries)
    }
}