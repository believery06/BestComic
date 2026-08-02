package com.mangareader.reader

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mangareader.data.ComicChapter
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import com.mangareader.data.ReadingHistoryEntry
import com.mangareader.MangaReaderApplication
import com.mangareader.R
import com.mangareader.data.ReaderSettings
import com.mangareader.data.ReadingStatsRepository
import com.mangareader.data.ScrollMode
import com.mangareader.data.SettingsRepository
import com.mangareader.data.ZoomMode
import com.mangareader.cache.PageCacheManager
import com.mangareader.parser.ComicParser
import com.mangareader.parser.ParserFactory
import com.mangareader.parser.ParserResult
import com.mangareader.parser.VolumeSortHelper
import com.mangareader.data.ComicProvider
import com.mangareader.provider.ParserBasedComicProvider
import com.mangareader.utils.BitmapPool
import com.mangareader.utils.CoverExtractor
import com.mangareader.utils.FileManager
import com.mangareader.utils.ImageUtils
import com.mangareader.utils.PanelDetector
import com.mangareader.utils.ShareSourcePreparer
import com.mangareader.utils.TtsManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ReaderViewModel"
private const val EXIT_SAVE_TIMEOUT_MS = 30_000L

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * 阅读器 UI 状态。
     */
    @Immutable
    data class ReaderUiState(
        val title: String = "",
        val currentPage: Int = 0,
        val totalPages: Int = 0,
        val currentChapter: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
        val settings: ReaderSettings = ReaderSettings(),
        val settingsVersion: Int = 0,
        val brightnessOverride: Float = Float.NaN
    )

    /**
     * 分镜模式视图状态。
     */
    @Immutable
    data class PanelViewState(
        val panels: List<PanelDetector.Panel> = emptyList(),
        val currentPanelIndex: Int = 0,
        val pageIndex: Int = -1,
        val isActive: Boolean = false,
        val isLoading: Boolean = false
    )

    private val settingsRepository = SettingsRepository.getInstance(application)
    private val shareSourcePreparer = ShareSourcePreparer(application)
    private val statsTracker = ReadingStatsTracker(application, ReadingStatsRepository.getInstance(application))
    private val bookmarkManager = BookmarkManager(viewModelScope, settingsRepository)

    // 提取的子组件
    private val pageLoader = PageLoader(application, viewModelScope)
    private val ttsController = TtsController(TtsManager(application), viewModelScope)
    private val autoPager = AutoPager(viewModelScope)
    private val progressSaver = ProgressSaver(settingsRepository, statsTracker, viewModelScope)

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    // 菜单显隐状态独立流：菜单动画/显示不应触发页面渲染重组
    private val _showMenu = MutableStateFlow(false)
    val showMenu: StateFlow<Boolean> = _showMenu.asStateFlow()

    // 分镜模式状态
    private val _panelState = MutableStateFlow(PanelViewState())
    val panelState: StateFlow<PanelViewState> = _panelState.asStateFlow()

    // 书签列表由 BookmarkManager 维护，避免 ReaderViewModel 直接持有 MutableStateFlow
    val bookmarks: StateFlow<List<Int>> = bookmarkManager.bookmarks

    // 向后兼容：保持 pages 作为 ViewModel 层的公开属性，实际由 pageLoader 管理
    val pages: List<ComicPage> get() = pageLoader.pages

    private var comicUri: Uri? = null
    private var currentComicEntry: ComicEntry? = null

    // TTS 状态委托给 ttsController
    val isSpeaking: kotlinx.coroutines.flow.StateFlow<Boolean> get() = ttsController.isSpeaking
    val ttsInitSuccess: Boolean? get() = ttsController.ttsInitSuccess

    // L1（内存，前后各 15 页窗口）+ L2（磁盘）缓存管理器
    private val cacheManager = PageCacheManager.getInstance(application)

    // 处理后的位图缓存：真正的 LRU，淘汰时自动回收旧位图。
    private fun safeBitmapSizeForLru(bmp: Bitmap?): Int {
        if (bmp == null || bmp.isRecycled) return 1
        return bmp.allocationByteCount.coerceAtLeast(1)
    }

    private fun createBitmapCopySafe(src: Bitmap?): Bitmap? {
        if (src == null || src.isRecycled) return null
        return try {
            Bitmap.createBitmap(src)
        } catch (_: OutOfMemoryError) {
            try {
                src.copy(Bitmap.Config.RGB_565, false)
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: Exception) {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private val processedBitmapCache = object : LruCache<String, Bitmap>(30 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return safeBitmapSizeForLru(value)
        }

        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            if (oldValue != null && !oldValue.isRecycled) {
                if (evicted) {
                    BitmapPool.put(oldValue)
                } else {
                    runCatching { oldValue.recycle() }
                }
            }
        }
    }

    private var loadJob: kotlinx.coroutines.Job? = null
    private var cachePageJob: kotlinx.coroutines.Job? = null
    private var panelJob: kotlinx.coroutines.Job? = null

    // 后处理设置（亮度/对比度等）变更时，对 clearProcessedCache 做防抖，
    // 避免滑条连续拖动时反复清空并重建处理后缓存。
    private var postProcessClearJob: kotlinx.coroutines.Job? = null

    fun setScreenSize(width: Int, height: Int) {
        cacheManager.setScreenSize(width, height)
    }

    /**
     * 返回当前阅读会话已持续毫秒数。
     * 会话未激活时返回 0，供 UI 按需格式化展示。
     */
    fun currentSessionDurationMs(): Long = statsTracker.currentSessionDurationMs()

    init {
        ttsController.setCallbacks(
            onPageFinished = { id ->
                // 仅当回调对应最近一次 speak 调用时才继续，避免快速翻页/取消后的过期回调引发竞态。
                if (id == ttsController.currentTtsUtteranceId.get()) {
                    nextPage()
                }
            },
            onNeedNextPage = { nextPage() }
        )
        viewModelScope.launch {
            settingsRepository.readerSettings.collect { settings ->
                val oldSettings = _uiState.value.settings
                _uiState.value = _uiState.value.copy(settings = settings)
                // 当 TTS 开关从关闭变为开启时朗读当前页
                if (settings.ttsEnabled && !oldSettings.ttsEnabled && pageLoader.pages.isNotEmpty()) {
                    speakCurrentPage()
                } else if (!settings.ttsEnabled && oldSettings.ttsEnabled) {
                    ttsController.currentTtsUtteranceId.set(null)
                    ttsController.stop()
                }
                // restart auto-page if interval changed
                val interval = settings.autoPageInterval
                if (interval > 0 && !autoPager.isRunning() && autoPager.autoPageWasRunning && pageLoader.pages.isNotEmpty()) {
                    startAutoPage(interval)
                }
            }
        }
    }

    /**
     * 从 URI 加载漫画，支持指定分卷 URI。
     */
    fun loadComicFromUri(comicUri: Uri, chapterUri: Uri? = null) {
        val entry = ComicEntry(
            uri = comicUri,
            title = comicUri.lastPathSegment ?: "",
            type = ParserFactory.detectType(comicUri.lastPathSegment ?: "")
        )
        // 分卷模式：需要先解析整本漫画获取分卷列表，再找到目标分卷
        if (chapterUri != null && chapterUri != comicUri) {
            viewModelScope.launch {
                try {
                    val parser = ParserFactory.getParser(ComicType.COMIC_BOOK)
                    if (parser != null) {
                        val result = withContext(Dispatchers.IO) {
                            parser.parse(getApplication(), comicUri)
                        }
                        if (result is ParserResult.Success) {
                            val pages = result.pages
                            // 按 chapterTitle 分组为 ComicChapter 列表
                            val chapters = groupPagesIntoChapters(pages)
                            val targetChapter = chapters.find { it.uri == chapterUri }
                                ?: chapters.find { chapterUri.toString().contains(it.title, ignoreCase = true) }
                            if (targetChapter != null) {
                                loadComic(entry, targetChapter.pages, chapters)
                                return@launch
                            }
                        }
                    }
                    // 回退：直接加载整本漫画
                    loadComic(entry)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "从 URI 加载分卷失败，回退到整本加载", e)
                    loadComic(entry)
                }
            }
        } else {
            loadComic(entry)
        }
    }

    /**
     * 将页面列表按 chapterTitle 分卷。
     */
    private fun groupPagesIntoChapters(pages: List<ComicPage>): List<ComicChapter> {
        if (pages.isEmpty()) return emptyList()
        val hasChapters = pages.any { it.chapterTitle.isNotEmpty() }
        if (!hasChapters) {
            val uri = comicUri ?: Uri.EMPTY
            return listOf(
                ComicChapter(
                    title = _uiState.value.title.ifEmpty { uri.lastPathSegment ?: "" },
                    uri = uri,
                    type = ComicType.UNKNOWN,
                    pages = pages
                )
            )
        }

        val result = mutableListOf<ComicChapter>()
        var currentTitle = ""
        var currentPages = mutableListOf<ComicPage>()
        var indexInChapter = 0

        for (page in pages) {
            val title = page.chapterTitle
            if (title.isNotEmpty() && title != currentTitle) {
                if (currentPages.isNotEmpty()) {
                    val uri = comicUri ?: Uri.EMPTY
                    result.add(
                        ComicChapter(
                            title = currentTitle,
                            uri = uri,
                            type = ComicType.UNKNOWN,
                            pages = currentPages.mapIndexed { idx, p -> p.copy(index = idx) }
                        )
                    )
                }
                currentTitle = title
                currentPages = mutableListOf()
                indexInChapter = 0
            }
            currentPages.add(page.copy(index = indexInChapter))
            indexInChapter++
        }

        if (currentPages.isNotEmpty()) {
            val uri = comicUri ?: Uri.EMPTY
            result.add(
                ComicChapter(
                    title = currentTitle.ifEmpty { uri.lastPathSegment ?: "" },
                    uri = uri,
                    type = ComicType.UNKNOWN,
                    pages = currentPages.mapIndexed { idx, p -> p.copy(index = idx) }
                )
            )
        }

        return result.sortedWith { a, b -> VolumeSortHelper.compareNames(a.title, b.title) }
    }

    fun loadComic(
        entry: ComicEntry,
        chapterPages: List<ComicPage>? = null,
        allChapters: List<ComicChapter>? = null
    ) {
        // 取消旧的加载任务
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                loadComicInternal(entry, chapterPages, allChapters)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "加载漫画失败", e)
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    private suspend fun loadComicInternal(
        entry: ComicEntry,
        chapterPages: List<ComicPage>?,
        allChapters: List<ComicChapter>?
    ) {
        // 显示加载状态
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        // 关闭旧的 parser 和 provider，避免资源泄漏
        pageLoader.releaseResources()

        // 使用 pageLoader 解析页面
        val pages = pageLoader.resolvePages(entry, chapterPages)

        if (pages.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = getApplication<Application>().getString(R.string.reader_no_pages_error)
            )
            return
        }

        // 更新 pageLoader 状态
        pageLoader.updatePages(pages, pageLoader.getProvider())
        pageLoader.updateChapters(allChapters, allChapters?.indexOfFirst {
            it.pages.firstOrNull()?.chapterTitle == pages.firstOrNull()?.chapterTitle
        }?.takeIf { it >= 0 } ?: -1)

        comicUri = entry.uri
        currentComicEntry = entry

        // 计算当前章节索引
        val currentChapterIdx = allChapters?.indexOfFirst { chapter ->
            chapter.pages.size == pages.size &&
                    chapter.pages.zip(pages).all { (a, b) -> a.name == b.name }
        } ?: -1
        pageLoader.setCurrentChapterIndex(currentChapterIdx)

        // 更新 UI 状态
        val totalPages = pages.size
        val startPage = if (currentChapterIdx >= 0 && allChapters != null) {
            // 分卷模式：计算当前章节在整本漫画中的起始页
            val prevPages = allChapters.take(currentChapterIdx).sumOf { it.pages.size }
            prevPages + entry.lastReadPage
        } else {
            entry.lastReadPage
        }
        val clampedStart = startPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))

        _uiState.value = _uiState.value.copy(
            title = entry.title,
            currentPage = clampedStart,
            totalPages = totalPages,
            isLoading = false,
            error = null,
            currentChapter = pages.getOrNull(clampedStart)?.chapterTitle ?: ""
        )

        // 开始新的阅读会话
        statsTracker.startSession()

        // 触发进度保存
        comicUri?.let { uri ->
            progressSaver.scheduleSave(
                uri = uri,
                clamped = clampedStart,
                chapter = _uiState.value.currentChapter,
                allChapters = pageLoader.allChapters,
                computeOverallProgress = { page -> pageLoader.computeOverallProgress(page) },
                totalPages = totalPages
            )
        }

        // TTS 开启时朗读当前页
        if (_uiState.value.settings.ttsEnabled) {
            speakCurrentPage()
        }
    }

    fun nextPage() {
        val state = _uiState.value
        if (state.totalPages <= 0) return
        val step = when {
            !state.settings.dualPage -> 1
            state.settings.dualPageStartOne -> {
                val offset = state.settings.dualPageOffset.coerceIn(0, 5)
                val shifted = (state.currentPage - offset).coerceAtLeast(0)
                if (shifted % 2 == 0) 2 else 1
            }
            else -> {
                val offset = state.settings.dualPageOffset.coerceIn(0, 5)
                val shifted = (state.currentPage - offset).coerceAtLeast(0)
                when (shifted) {
                    0 -> 1
                    else -> if (shifted % 2 == 0) 1 else 2
                }
            }
        }
        val next = state.currentPage + step
        if (next >= state.totalPages) {
            // 检查是否到达最后一卷末
            val chapters = pageLoader.allChapters
            val currentIdx = pageLoader.currentChapterIndex
            if (chapters != null && currentIdx < chapters.size - 1) {
                // 还有下一卷
                jumpToNextChapter()
            }
            // 否则停在最后一页（不做任何操作）
        } else {
            setCurrentPage(next)
        }
    }

    fun previousPage() {
        val state = _uiState.value
        if (state.totalPages <= 0) return
        val step = when {
            !state.settings.dualPage -> 1
            state.settings.dualPageStartOne -> {
                val offset = state.settings.dualPageOffset.coerceIn(0, 5)
                val shifted = (state.currentPage - offset).coerceAtLeast(0)
                if (shifted % 2 == 0) 2 else 1
            }
            else -> {
                val offset = state.settings.dualPageOffset.coerceIn(0, 5)
                val shifted = (state.currentPage - offset).coerceAtLeast(0)
                when (shifted) {
                    0 -> 1
                    else -> if (shifted % 2 == 0) 1 else 2
                }
            }
        }
        val prev = state.currentPage - step
        if (prev < 0) {
            // 检查是否在第一卷开头
            val chapters = pageLoader.allChapters
            val currentIdx = pageLoader.currentChapterIndex
            if (chapters != null && currentIdx > 0) {
                // 还有上一卷，跳到上一卷末尾
                jumpToPrevChapter()
            }
            // 否则停在第一页
        } else {
            setCurrentPage(prev)
        }
    }

    fun jumpToNextChapter() {
        val chapters = pageLoader.allChapters ?: return
        val currentIdx = pageLoader.currentChapterIndex
        val nextIdx = currentIdx + 1
        if (nextIdx in chapters.indices) {
            val nextChapter = chapters[nextIdx]
            // 使用 pageLoader 更新状态
            pageLoader.updatePages(nextChapter.pages, pageLoader.getProvider())
            pageLoader.setCurrentChapterIndex(nextIdx)
            // 重新加载漫画（使用新的章节页面）
            currentComicEntry?.let { entry ->
                loadComic(
                    entry = entry,
                    chapterPages = nextChapter.pages,
                    allChapters = chapters
                )
            }
        }
    }

    fun jumpToPrevChapter() {
        if (pageLoader.pages.isEmpty()) return
        val currentChapter = pageLoader.pages.getOrNull(_uiState.value.currentPage)?.chapterTitle ?: return
        for (i in (_uiState.value.currentPage - 1) downTo 0) {
            if (pageLoader.pages[i].chapterTitle.isNotEmpty() && pageLoader.pages[i].chapterTitle != currentChapter) {
                // 找到上一章的最后一页
                var lastPage = i
                while (lastPage + 1 < pageLoader.pages.size && pageLoader.pages[lastPage + 1].chapterTitle == pageLoader.pages[i].chapterTitle) {
                    lastPage++
                }
                setCurrentPage(lastPage)
                return
            }
        }
    }

    /**
     * 检查是否到达章节末尾。
     */
    fun checkChapterEnd(): Pair<Boolean, Boolean> {
        val state = _uiState.value
        val chapters = pageLoader.allChapters
        val currentIdx = pageLoader.currentChapterIndex

        if (chapters == null || currentIdx < 0) {
            // 整本模式
            val isAtEnd = state.currentPage >= state.totalPages - 1
            return Pair(isAtEnd, isAtEnd)
        }

        // 分卷模式：检查是否在当前卷末尾
        val currentChapter = chapters[currentIdx]
        val prevPages = chapters.take(currentIdx).sumOf { it.pages.size }
        val lastPageOfChapter = prevPages + currentChapter.pages.size - 1
        val isAtChapterEnd = state.currentPage >= lastPageOfChapter
        val isLastChapter = currentIdx >= chapters.size - 1

        return Pair(isAtChapterEnd, isLastChapter)
    }

    /**
     * 获取下一卷的标题（用于对话框显示）。
     */
    fun getNextChapterTitle(): String? {
        if (pageLoader.pages.isEmpty()) return null

        val currentPage = _uiState.value.currentPage
        val currentChapter = pageLoader.pages.getOrNull(currentPage)?.chapterTitle ?: ""

        // 单卷模式：从 allChapters 取下一卷标题
        val chapters = pageLoader.allChapters
        val nextIdx = pageLoader.currentChapterIndex + 1
        if (chapters != null && nextIdx in chapters.indices) {
            return chapters[nextIdx].title
        }

        // 整本漫画模式
        var lastPageOfCurrentChapter = currentPage
        while (lastPageOfCurrentChapter + 1 < pageLoader.pages.size &&
               pageLoader.pages[lastPageOfCurrentChapter + 1].chapterTitle == currentChapter) {
            lastPageOfCurrentChapter++
        }

        for (i in (lastPageOfCurrentChapter + 1) until pageLoader.pages.size) {
            if (pageLoader.pages[i].chapterTitle.isNotEmpty() && pageLoader.pages[i].chapterTitle != currentChapter) {
                return pageLoader.pages[i].chapterTitle
            }
        }

        return null
    }

    fun setCurrentPage(page: Int) {
        val clamped = page.coerceIn(0, (pageLoader.pages.size - 1).coerceAtLeast(0))
        val oldPage = _uiState.value.currentPage
        if (clamped != oldPage) {
            // 用户手动翻页或成功跳转后重置 TTS 空页跳过计数
            ttsController.ttsEmptySkipCount.set(0)
            val chapter = pageLoader.pages.getOrNull(clamped)?.chapterTitle ?: ""
            _uiState.value = _uiState.value.copy(currentPage = clamped, currentChapter = chapter)
            comicUri?.let { uri ->
                progressSaver.cancelPendingJob()
                progressSaver.scheduleSave(
                    uri = uri,
                    clamped = clamped,
                    chapter = chapter,
                    allChapters = pageLoader.allChapters,
                    computeOverallProgress = { p -> pageLoader.computeOverallProgress(p) },
                    totalPages = pageLoader.pages.size
                )
            }
            // 根据滚动模式和翻页方向决定预加载方向，解决滚动模式下不按顺序加载的问题
            val direction = when (_uiState.value.settings.scrollMode) {
                ScrollMode.PAGE -> PageCacheManager.PrefetchDirection.BOTH
                else -> if (clamped > oldPage) {
                    PageCacheManager.PrefetchDirection.FORWARD
                } else {
                    PageCacheManager.PrefetchDirection.BACKWARD
                }
            }
            // 根据翻页方向调整预加载范围：前向翻页时加大前向预加载范围
            val preloadBias = if (clamped > oldPage) 1.5f else if (clamped < oldPage) 0.5f else 1f
            pageLoader.getProvider()?.let { currentProvider ->
                cachePageJob?.cancel()
                cachePageJob = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        cacheManager.setCurrentPage(currentProvider, clamped, direction)
                        val dual = _uiState.value.settings.dualPage
                        val nextStep = if (dual) 2 else 1
                        // 根据方向加权预加载下一页
                        val nextIdx = clamped + nextStep
                        if (nextIdx in pageLoader.pages.indices) {
                            try { loadPageBitmap(nextIdx) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                        }
                        if (preloadBias > 1f) {
                            // 额外预加载更远的下一页
                            val farNextIdx = clamped + nextStep * 2
                            if (farNextIdx in pageLoader.pages.indices) {
                                try { loadPageBitmap(farNextIdx) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                            }
                        }
                        val prevIdx = clamped - nextStep
                        if (prevIdx in pageLoader.pages.indices) {
                            try { loadPageBitmap(prevIdx) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("ReaderViewModel", "缓存加载失败: ${e.message}")
                    }
                }
            }
            if (_uiState.value.settings.ttsEnabled) {
                speakCurrentPage()
            }
        }
    }

    private fun speakCurrentPage() {
        ttsController.speakCurrentPage(
            currentPage = _uiState.value.currentPage,
            getPageText = { page ->
                pageLoader.getProvider()?.getPageText(page)
            },
            onSkipPage = { nextPage() }
        )
    }

    private fun startAutoPage(seconds: Int) {
        autoPager.startAutoPage(
            seconds = seconds,
            isMenuVisible = { _showMenu.value },
            getState = { _uiState.value },
            loadPageBitmap = { page ->
                // 需要挂起函数
                withContext(Dispatchers.IO) {
                    loadPageBitmap(page)
                }
            },
            jumpToNextChapter = { jumpToNextChapter() },
            setCurrentPage = { setCurrentPage(it) },
            onStop = { /* autoPager 内部已处理 */ }
        )
    }

    private fun stopAutoPage() {
        autoPager.stopAutoPage()
    }

    override fun onCleared() {
        super.onCleared()
        autoPager.stopAutoPage()
        ttsController.currentTtsUtteranceId.set(null)
        ttsController.shutdown()
        // 取消并等待所有可能持有 parser/provider 引用的后台任务完成，
        // 避免在关闭资源后出现 use-after-close 导致崩溃。
        runCatching {
            runBlocking {
                withTimeoutOrNull(2_000L) {
                    progressSaver.cancelPendingJob()
                    cachePageJob?.cancelAndJoin()
                    panelJob?.cancelAndJoin()
                    ttsController.cancelJob()
                }
            }
        }
        pageLoader.releaseResources()
        // 后台任务结束后安全释放缓存：先清理 L1 内存缓存，再释放处理后位图缓存。
        cacheManager.clearMemory()
        processedBitmapCache.evictAll()
        // ViewModel 销毁时 viewModelScope 已取消，改用 applicationScope 在后台异步保存。
        val sessionUri = comicUri
        val currentPage = _uiState.value.currentPage
        val currentChapter = _uiState.value.currentChapter
        // 结束阅读会话；若 releaseCache 已结算，内部原子标志保证不会重复。
        statsTracker.endSession(sessionUri)
        MangaReaderApplication.applicationScope.launch(Dispatchers.IO) {
            // 1. 兜底保存当前阅读进度与总页数。
            try {
                withTimeoutOrNull(EXIT_SAVE_TIMEOUT_MS) {
                    if (sessionUri != null && currentPage >= 0) {
                        val progressChapter = if (pageLoader.allChapters != null) currentChapter else ""
                        settingsRepository.saveProgress(sessionUri, progressChapter, currentPage)
                        val (overallProgress, overallTotal) = pageLoader.computeOverallProgress(currentPage)
                        settingsRepository.saveProgress(sessionUri, overallProgress)
                        settingsRepository.saveTotalPages(sessionUri, overallTotal)
                    }
                }
            } catch (_: Exception) {
                // 退出时进度保存失败不应崩溃
            }
            // 2. 自动书签 + 把内存中的最终书签列表完整同步到磁盘。
            try {
                withTimeoutOrNull(EXIT_SAVE_TIMEOUT_MS) {
                    bookmarkManager.autoSaveBookmarkOnExit(currentPage, pageLoader.pages.size)
                    bookmarkManager.persist()
                }
            } catch (_: Exception) {
                // 退出时书签保存失败不应崩溃
            }
            // 4. 兜底保存当前阅读设置，防止 saveAsync 的延迟写入在 ViewModel 销毁前未执行而丢失。
            try {
                withTimeoutOrNull(EXIT_SAVE_TIMEOUT_MS) {
                    settingsRepository.saveReaderSettings(_uiState.value.settings)
                }
            } catch (_: Exception) {
                // 退出时设置保存失败不应崩溃
            }
        }
    }

    /**
     * 加载指定页的缩略图（最大边不超过 [maxDimension] 像素），
     * 优先走 L1/L2 缓存，避免重复解码。
     */
    suspend fun loadThumbnail(index: Int, maxDimension: Int = 180): Bitmap? = withContext(Dispatchers.IO) {
        val currentProvider = pageLoader.getProvider() ?: return@withContext null
        if (index !in pageLoader.pages.indices) return@withContext null
        try {
            cacheManager.getThumbnail(currentProvider, index, maxDimension)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "加载缩略图失败: index=$index", e)
            null
        }
    }

    fun getCachedBitmap(index: Int): Bitmap? {
        val state = _uiState.value
        if (index !in pageLoader.pages.indices) return null
        val cacheKey = "${index}_${state.settingsVersion}"
        val cached = processedBitmapCache.get(cacheKey)?.takeUnless { it.isRecycled }
        return createBitmapCopySafe(cached)
    }

    suspend fun loadPageBitmap(index: Int): Bitmap? = withContext(Dispatchers.IO) {
        val currentProvider = pageLoader.getProvider() ?: return@withContext null
        if (index !in pageLoader.pages.indices) return@withContext null
        try {
            val state = _uiState.value
            val cacheKey = "${index}_${state.settingsVersion}"
            processedBitmapCache.get(cacheKey)?.let { cached ->
                if (!cached.isRecycled) {
                    return@withContext createBitmapCopySafe(cached)
                }
            }

            val rawBmp = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                cacheManager.getPage(currentProvider, index)
            } ?: return@withContext null

            if (rawBmp.isRecycled) return@withContext null

            var result = rawBmp
            var croppedBmp: Bitmap? = null
            if (state.settings.autoCrop) {
                val cropped = ImageUtils.autoCropWhiteBorders(result)
                if (cropped !== result) {
                    croppedBmp = cropped
                    result = cropped
                }
            }
            val manualCrop = state.settings.cropMarginTop > 0 ||
                    state.settings.cropMarginBottom > 0 ||
                    state.settings.cropMarginLeft > 0 ||
                    state.settings.cropMarginRight > 0
            if (manualCrop) {
                val cropped = ImageUtils.cropMargins(
                    result,
                    topPct = state.settings.cropMarginTop,
                    bottomPct = state.settings.cropMarginBottom,
                    leftPct = state.settings.cropMarginLeft,
                    rightPct = state.settings.cropMarginRight
                )
                if (cropped !== result) {
                    val oldCropped = croppedBmp
                    if (oldCropped != null && oldCropped !== result && !oldCropped.isRecycled) {
                        runCatching { oldCropped.recycle() }
                    }
                    croppedBmp = cropped
                    result = cropped
                }
            }
            val effectiveBrightness = if (state.brightnessOverride.isNaN()) {
                state.settings.brightness
            } else {
                state.brightnessOverride
            }
            var adjustedBmp: Bitmap? = null
            result = try {
                val adjusted = ImageUtils.applyAdjustments(
                    src = result,
                    brightness = effectiveBrightness,
                    contrast = state.settings.contrast,
                    rotation = state.settings.rotation,
                    grayscale = state.settings.grayscale,
                    sharpen = state.settings.sharpen,
                    gamma = state.settings.gamma,
                    denoise = state.settings.denoise,
                    saturation = state.settings.saturation,
                    nightMode = state.settings.nightMode,
                    eyeCare = state.settings.eyeCare,
                    sharpenStrength = state.settings.sharpenStrength,
                    denoiseStrength = state.settings.denoiseStrength,
                    mirror = state.settings.mirror
                )
                if (adjusted !== result) {
                    adjustedBmp = adjusted
                }
                adjusted
            } catch (e: Throwable) {
                Log.e(TAG, "图像调整失败: index=$index", e)
                result
            }
            val finalCropped = croppedBmp
            if (finalCropped != null && finalCropped !== result && !finalCropped.isRecycled) {
                runCatching { finalCropped.recycle() }
            }

            val toReturn: Bitmap?
            if (result !== rawBmp) {
                if (rawBmp !== result && !rawBmp.isRecycled && (adjustedBmp == null || rawBmp !== adjustedBmp) && (croppedBmp == null || rawBmp !== croppedBmp)) {
                    runCatching { rawBmp.recycle() }
                }
                processedBitmapCache.put(cacheKey, result)
                toReturn = createBitmapCopySafe(result)
            } else {
                toReturn = result
            }
            toReturn
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "加载页面位图失败: index=$index", e)
            null
        }
    }

    /**
     * 安全获取已处理位图：若已回收则返回 null，避免 UI 层闪退。
     * 返回独立副本，UI 可安全回收。
     */
    fun getCachedProcessedBitmap(index: Int, version: Int): Bitmap? {
        val cacheKey = "${index}_${version}"
        val cached = processedBitmapCache.get(cacheKey)?.takeIf { !it.isRecycled }
        return createBitmapCopySafe(cached)
    }

    fun toggleMenu() {
        val showing = !_showMenu.value
        _showMenu.value = showing
        val interval = _uiState.value.settings.autoPageInterval
        if (showing) {
            // 记录菜单弹出前自动翻页是否正在运行
            autoPager.setWasRunning(autoPager.isRunning())
            stopAutoPage()
        } else {
            // 仅当菜单弹出前正在自动翻页时才恢复，避免临时呼出菜单后意外恢复。
            if (autoPager.autoPageWasRunning && interval > 0 && pageLoader.pages.isNotEmpty()) {
                startAutoPage(interval)
            }
        }
    }

    fun hideMenu() {
        _showMenu.value = false
        val interval = _uiState.value.settings.autoPageInterval
        if (autoPager.autoPageWasRunning && interval > 0 && pageLoader.pages.isNotEmpty()) {
            startAutoPage(interval)
        }
    }

    // 以下设置仅影响页面布局、手势或图像后处理，不触发原始位图重新解码。
    fun toggleDualPage() = updateSettings(needsReload = false) { it.copy(dualPage = !it.dualPage) }
    fun toggleDualPageStartOne() = updateSettings(needsReload = false) { it.copy(dualPageStartOne = !it.dualPageStartOne) }
    fun toggleRtl() = updateSettings(needsReload = false) { it.copy(rtl = !it.rtl) }
    fun toggleImmersive() = updateSettings(needsReload = false) { it.copy(immersive = !it.immersive) }
    fun toggleAutoCrop() = updateSettings(needsReload = false) { it.copy(autoCrop = !it.autoCrop) }
    fun toggleGrayscale() = updateSettings(needsReload = false) { it.copy(grayscale = !it.grayscale) }
    fun toggleSharpen() = updateSettings(needsReload = false) { it.copy(sharpen = !it.sharpen) }
    fun toggleDenoise() = updateSettings(needsReload = false) { it.copy(denoise = !it.denoise) }
    fun toggleMirror() = updateSettings(needsReload = false) { it.copy(mirror = !it.mirror) }
    fun togglePageShadow() = updateSettings(needsReload = false) { it.copy(pageShadow = !it.pageShadow) }
    fun toggleNightMode() = updateSettings(needsReload = false) { it.copy(nightMode = !it.nightMode) }
    fun toggleEyeCare() = updateSettings(needsReload = false) { it.copy(eyeCare = !it.eyeCare) }
    fun toggleVolumeKeyNav() = updateSettings(needsReload = false) { it.copy(volumeKeyNav = !it.volumeKeyNav) }
    fun toggleEnableZoom() = updateSettings(needsReload = false) { it.copy(enableZoom = !it.enableZoom) }
    fun toggleMagnifier() = updateSettings(needsReload = false) { it.copy(magnifierEnabled = !it.magnifierEnabled) }
    fun setSaturation(value: Float) = updateSettings(needsReload = false) { it.copy(saturation = value.coerceIn(0f, 2f)) }
    fun setSharpenStrength(value: Float) = updateSettings(needsReload = false) { it.copy(sharpenStrength = value.coerceIn(0f, 3f)) }
    fun setDenoiseStrength(value: Float) = updateSettings(needsReload = false) { it.copy(denoiseStrength = value.coerceIn(0f, 3f)) }
    fun setTapZoneSize(size: com.mangareader.data.TapZoneSize) = updateSettings(needsReload = false) { it.copy(tapZoneSize = size) }
    fun setScaleFilter(filter: com.mangareader.data.ScaleFilter) = updateSettings(needsReload = false) { it.copy(scaleFilter = filter) }
    fun setScrollMode(mode: ScrollMode) = updateSettings(needsReload = false) { it.copy(scrollMode = mode) }
    fun toggleTts() = updateSettings(needsReload = false) { it.copy(ttsEnabled = !it.ttsEnabled) }
    fun toggleThumbnailStrip() = updateSettings(needsReload = false) { it.copy(thumbnailStripEnabled = !it.thumbnailStripEnabled) }
    fun setTtsSpeed(speed: Float) = updateSettings(needsReload = false) { it.copy(ttsSpeed = speed.coerceIn(0.25f, 4f)) }
    fun setTtsPitch(pitch: Float) = updateSettings(needsReload = false) { it.copy(ttsPitch = pitch.coerceIn(0.25f, 4f)) }
    fun setZoomMode(mode: ZoomMode) = updateSettings(needsReload = false) { it.copy(zoomMode = mode) }
    fun setAutoPageInterval(seconds: Int) = updateSettings(needsReload = false) {
        it.copy(autoPageInterval = seconds.coerceIn(0, 60))
    }

    private var brightnessAnimJob: Job? = null

    /**
     * 设置手动亮度并持久化。
     */
    fun setBrightness(value: Float, animate: Boolean = false) {
        val target = value.coerceIn(0.5f, 2f)
        if (!animate) {
            brightnessAnimJob?.cancel()
            _uiState.value = _uiState.value.copy(brightnessOverride = Float.NaN)
            updateSettings(needsReload = false) { it.copy(brightness = target) }
            return
        }
        brightnessAnimJob?.cancel()
        brightnessAnimJob = viewModelScope.launch {
            val start = _uiState.value.settings.brightness
            val duration = 250L
            val startMs = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - startMs
                val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                val current = start + (target - start) * progress
                _uiState.value = _uiState.value.copy(brightnessOverride = current)
                if (progress >= 1f) break
                delay(16L)
            }
            _uiState.value = _uiState.value.copy(brightnessOverride = Float.NaN)
            updateSettings(needsReload = false) { it.copy(brightness = target) }
        }
    }

    /**
     * 应用自动亮度到当前页面（不持久化）。
     */
    fun applyAutoBrightness(brightness: Float) {
        _uiState.value = _uiState.value.copy(brightnessOverride = brightness)
    }

    /**
     * 清除自动亮度覆盖，恢复用户手动设置。
     */
    fun clearAutoBrightness() {
        _uiState.value = _uiState.value.copy(brightnessOverride = Float.NaN)
    }

    /**
     * 更新阅读器设置。
     * @param needsReload 是否需要重新加载页面
     * @param copy 设置副本转换函数
     */
    fun updateSettings(needsReload: Boolean = false, copy: (ReaderSettings) -> ReaderSettings) {
        val oldSettings = _uiState.value.settings
        val newSettings = copy(oldSettings)
        _uiState.value = _uiState.value.copy(settings = newSettings, settingsVersion = _uiState.value.settingsVersion + 1)
        // 异步保存到 DataStore
        viewModelScope.launch {
            settingsRepository.saveReaderSettings(newSettings)
        }
        // 清除处理后缓存（如果设置改变影响图像后处理）
        if (needsReload || settingsAffectingPostProcessChanged(oldSettings, newSettings)) {
            clearProcessedCacheDebounced()
        }
    }

    private fun settingsAffectingPostProcessChanged(old: ReaderSettings, new: ReaderSettings): Boolean {
        return old.brightness != new.brightness ||
                old.contrast != new.contrast ||
                old.rotation != new.rotation ||
                old.grayscale != new.grayscale ||
                old.sharpen != new.sharpen ||
                old.gamma != new.gamma ||
                old.denoise != new.denoise ||
                old.saturation != new.saturation ||
                old.nightMode != new.nightMode ||
                old.eyeCare != new.eyeCare ||
                old.sharpenStrength != new.sharpenStrength ||
                old.denoiseStrength != new.denoiseStrength ||
                old.mirror != new.mirror ||
                old.autoCrop != new.autoCrop ||
                old.cropMarginTop != new.cropMarginTop ||
                old.cropMarginBottom != new.cropMarginBottom ||
                old.cropMarginLeft != new.cropMarginLeft ||
                old.cropMarginRight != new.cropMarginRight
    }

    private fun clearProcessedCacheDebounced() {
        postProcessClearJob?.cancel()
        postProcessClearJob = viewModelScope.launch {
            delay(300L)
            processedBitmapCache.evictAll()
        }
    }

    /**
     * 释放阅读器缓存（退出时调用）。
     */
    fun releaseCache() {
        processedBitmapCache.evictAll()
        cacheManager.clearMemory()
    }

    /**
     * 检查当前页面是否有面板检测结果。
     */
    fun hasPanelResult(): Boolean {
        return _panelState.value.panels.isNotEmpty()
    }

    /**
     * 获取当前页面的面板列表。
     */
    fun getCurrentPanels(): List<PanelDetector.Panel> {
        return _panelState.value.panels
    }

    /**
     * 跳转到下一个面板。
     */
    fun nextPanel() {
        val panels = _panelState.value.panels
        val currentIdx = _panelState.value.currentPanelIndex
        if (currentIdx < panels.size - 1) {
            _panelState.value = _panelState.value.copy(currentPanelIndex = currentIdx + 1)
        }
    }

    /**
     * 跳转到上一个面板。
     */
    fun prevPanel() {
        val currentIdx = _panelState.value.currentPanelIndex
        if (currentIdx > 0) {
            _panelState.value = _panelState.value.copy(currentPanelIndex = currentIdx - 1)
        }
    }

    /**
     * 进入面板查看模式。
     */
    fun enterPanelView() {
        _panelState.value = _panelState.value.copy(
            isActive = true,
            pageIndex = _uiState.value.currentPage,
            currentPanelIndex = 0
        )
    }

    /**
     * 退出面板查看模式。
     */
    fun exitPanelView() {
        _panelState.value = _panelState.value.copy(isActive = false)
    }

    /**
     * 触发当前页的面板检测。
     */
    fun detectPanels() {
        panelJob?.cancel()
        panelJob = viewModelScope.launch(Dispatchers.IO) {
            val currentProvider = pageLoader.getProvider() ?: return@launch
            val currentPage = _uiState.value.currentPage
            if (currentPage !in pageLoader.pages.indices) return@launch
            try {
                val bitmap = cacheManager.getPage(currentProvider, currentPage) ?: return@launch
                val panels = PanelDetector.detectPanels(bitmap)
                _panelState.value = PanelViewState(
                    panels = panels,
                    currentPanelIndex = 0,
                    isLoading = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "面板检测失败", e)
                _panelState.value = _panelState.value.copy(isLoading = false)
            }
        }
    }

    /**
     * 准备分享源（将漫画复制到缓存目录）。
     */
    suspend fun prepareShareSource(): File? = withContext(Dispatchers.IO) {
        try {
            val entry = currentComicEntry ?: return@withContext null
            shareSourcePreparer.prepareSingle(entry.uri, entry.title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "准备分享源失败", e)
            null
        }
    }

    /**
     * 获取当前页面的文本内容（用于 TTS）。
     */
    suspend fun getCurrentPageText(): String? = withContext(Dispatchers.IO) {
        val currentPage = _uiState.value.currentPage
        pageLoader.getProvider()?.getPageText(currentPage)
    }

    /**
     * 获取章节列表（章节标题, 起始页码, 结束页码）。
     */
    fun getChapterList(): List<Triple<String, Int, Int>> {
        val chapters = pageLoader.allChapters
        if (chapters != null) {
            val result = mutableListOf<Triple<String, Int, Int>>()
            var startPage = 0
            for (chapter in chapters) {
                val endPage = startPage + chapter.pages.size - 1
                result.add(Triple(chapter.title, startPage, endPage))
                startPage += chapter.pages.size
            }
            return result
        }
        return emptyList()
    }

    /**
     * 获取漫画封面 URI（用于历史记录）。
     */
    suspend fun getCover(): android.net.Uri? = withContext(Dispatchers.IO) {
        val entry = currentComicEntry ?: return@withContext null
        try {
            CoverExtractor.extractCover(getApplication(), entry.uri, entry.type)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "提取封面失败", e)
            null
        }
    }

    /**
     * 切换当前页的书签状态。
     */
    fun toggleBookmark() {
        bookmarkManager.toggleBookmarkAt(_uiState.value.currentPage)
    }

    /**
     * 移除指定页的书签。
     */
    fun removeBookmark(page: Int = _uiState.value.currentPage) {
        bookmarkManager.removeBookmark(page)
    }

    /**
     * 旋转当前页面（顺时针 90 度）。
     */
    fun rotate() = updateSettings(needsReload = false) {
        val newRotation = (it.rotation + 90) % 360
        it.copy(rotation = newRotation)
    }

    /**
     * 获取当前页在章节内的页码（从 1 开始）。
     */
    fun getPageInChapter(): Int {
        val currentPage = _uiState.value.currentPage
        val chapters = pageLoader.allChapters
        if (chapters != null) {
            var pagesBefore = 0
            for (chapter in chapters) {
                if (currentPage < pagesBefore + chapter.pages.size) {
                    return currentPage - pagesBefore + 1
                }
                pagesBefore += chapter.pages.size
            }
        }
        return currentPage + 1
    }

    /**
     * 获取当前章节的总页数。
     */
    fun getChapterPageCount(): Int {
        val currentPage = _uiState.value.currentPage
        val chapters = pageLoader.allChapters
        if (chapters != null) {
            var pagesBefore = 0
            for (chapter in chapters) {
                if (currentPage < pagesBefore + chapter.pages.size) {
                    return chapter.pages.size
                }
                pagesBefore += chapter.pages.size
            }
        }
        return _uiState.value.totalPages
    }

    /**
     * 获取指定页的章节标题。
     */
    fun getPageChapterTitle(page: Int = _uiState.value.currentPage): String {
        return pageLoader.pages.getOrNull(page)?.chapterTitle ?: ""
    }

    /**
     * 创建分享卡片。
     */
    suspend fun createShareCard(pageIndex: Int): java.io.File? = withContext(Dispatchers.IO) {
        try {
            val entry = currentComicEntry ?: return@withContext null
            val bitmap = loadPageBitmap(pageIndex) ?: return@withContext null
            val pageLabel = "Page ${pageIndex + 1}"
            val card = ImageUtils.createShareCard(bitmap, entry.title, pageLabel, getApplication<Application>().getString(R.string.app_name))
            if (card != null) {
                val file = java.io.File(getApplication<Application>().cacheDir, "share_card_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    card.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (card != bitmap) runCatching { card.recycle() }
                file
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "创建分享卡片失败", e)
            null
        }
    }

    /**
     * 获取阅读历史条目。
     */
    suspend fun getReadingHistoryEntry(): ReadingHistoryEntry? = withContext(Dispatchers.IO) {
        val entry = currentComicEntry ?: return@withContext null
        val state = _uiState.value
        ReadingHistoryEntry(
            uriString = entry.uri.toString(),
            title = entry.title,
            lastReadAt = System.currentTimeMillis(),
            lastReadPage = state.currentPage,
            totalPages = state.totalPages,
            coverUriString = entry.coverUri?.toString()
        )
    }
}


