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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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

    /**
     * 阅读器一次性 UI 事件（如卷末提示弹窗）。
     * 使用 BUFFERED Channel 保证配置切换/快速翻页时事件不丢失。
     */
    sealed interface ReaderEvent {
        data object ShowChapterEnd : ReaderEvent
    }

    // CONFLATED：卷末连发（用户连点）只保留一个待处理事件，
    // 避免事件堆积在 Channel 中导致关闭对话框后又被旧事件立即重新弹出。
    private val _readerEvents = Channel<ReaderEvent>(Channel.CONFLATED)
    val readerEvents: Flow<ReaderEvent> = _readerEvents.receiveAsFlow()

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
                    nextPage(autoContinue = true)
                }
            },
            onNeedNextPage = { nextPage(autoContinue = true) }
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
     * 从 URI 加载漫画。
     *
     * 统一采用「整本解析 + 按页内分卷标记分组」的模型：所有分卷共享同一条页面列表与同一套
     * 全局页码，分卷切换只是页码跳转，避免按分卷切片加载导致的页码错乱/资源重建。
     *
     * @param chapterIndex 指定打开的分卷在「按名称排序后的分卷列表」中的下标（与分卷浏览页一致）；
     *                    -1 表示整本/续读模式，按已保存的整体阅读进度恢复页码。
     */
    fun loadComicFromUri(comicUri: Uri, chapterIndex: Int = -1) {
        val title = comicUri.lastPathSegment ?: ""
        viewModelScope.launch {
            // 打开前恢复整体阅读进度（修复此前每次都从第 0 页开始的问题）。
            val savedPage = try {
                settingsRepository.getProgress(comicUri).first().coerceAtLeast(0)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0
            }
            val detectedType = ParserFactory.detectType(title)
            val entry = ComicEntry(
                uri = comicUri,
                title = title,
                type = detectedType,
                // SAF tree URI（如 primary:Comics/xx）无扩展名，detectType 会返回 UNKNOWN，
                // 这类 URI 实际是目录，需要走 ComicBookParser（其内部会回退 FolderParser）。
                isDirectory = detectedType == com.mangareader.data.ComicType.FOLDER ||
                        detectedType == com.mangareader.data.ComicType.COMIC_BOOK ||
                        detectedType == com.mangareader.data.ComicType.UNKNOWN,
                lastReadPage = savedPage
            )
            loadComic(entry, chapterIndex)
        }
    }

    /**
     * 按页面在列表中的实际顺序（连续同名页片）聚合分卷，分卷的 [ComicChapter.pages]
     * 直接引用整本页面列表的子区间，保证全局页码连续、与 Provider 下标一致。
     * 所有页面均无分卷标记（单文件漫画/纯散页文件夹）时，合成一个与分卷浏览页
     * 「整本」卡片一一对应的单卷，使用户点击该卡片能从第 0 页打开（而非续读）。
     */
    private fun groupPagesInPageOrder(entry: ComicEntry, pages: List<ComicPage>): List<ComicChapter> {
        if (pages.isEmpty()) return emptyList()
        val defaultTitle = entry.title.ifEmpty { entry.uri.lastPathSegment ?: "" }
        if (pages.none { it.chapterTitle.isNotEmpty() }) {
            return listOf(
                ComicChapter(
                    title = defaultTitle,
                    uri = entry.uri,
                    type = entry.type,
                    pages = pages
                )
            )
        }
        val result = mutableListOf<ComicChapter>()
        var runStart = 0
        while (runStart < pages.size) {
            val rawTitle = pages[runStart].chapterTitle
            var runEnd = runStart + 1
            while (runEnd < pages.size && pages[runEnd].chapterTitle == rawTitle) {
                runEnd++
            }
            result.add(
                ComicChapter(
                    title = rawTitle.ifEmpty { defaultTitle },
                    uri = entry.uri,
                    type = entry.type,
                    pages = pages.subList(runStart, runEnd)
                )
            )
            runStart = runEnd
        }
        return result
    }

    /** 返回全局页码 [page] 所在分卷在 [chapters]（页面顺序）中的下标，无分卷时返回 -1。 */
    private fun chapterIndexForPage(chapters: List<ComicChapter>?, page: Int): Int {
        if (chapters.isNullOrEmpty()) return -1
        if (page < 0) return -1
        var acc = 0
        for (i in chapters.indices) {
            val size = chapters[i].pages.size
            if (page < acc + size) return i
            acc += size
        }
        return chapters.lastIndex
    }

    fun loadComic(
        entry: ComicEntry,
        initialChapterIndex: Int = -1
    ) {
        // 取消旧的加载任务
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                loadComicInternal(entry, initialChapterIndex)
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
        initialChapterIndex: Int
    ) {
        // 显示加载状态
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        // 关闭旧的 parser 和 provider，避免资源泄漏
        pageLoader.releaseResources()

        // 整本解析：始终加载完整页面列表，分卷通过 chapterTitle 在页内标记区分
        val pages = pageLoader.resolvePages(entry, null)

        if (pages.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = getApplication<Application>().getString(R.string.reader_no_pages_error)
            )
            return
        }

        // 页面顺序的分卷列表（页码计算以此为准）
        val chaptersInPageOrder = groupPagesInPageOrder(entry, pages)
        val chapters = chaptersInPageOrder.takeIf { it.isNotEmpty() }

        // 分卷浏览页传的是「按名称排序」后的下标，这里通过标题映射回页面顺序的分卷下标。
        // 混合（散页+章节文件+子目录）等极端场景下顺序可能不同，标题映射更稳健。
        val targetPageOrderIndex = if (initialChapterIndex >= 0 && chapters != null) {
            val sortedChapters = chapters.sortedWith { a, b ->
                VolumeSortHelper.compareNames(a.title, b.title)
            }
            sortedChapters.getOrNull(initialChapterIndex)?.let { target ->
                chapters.indexOfFirst { it.title == target.title }
            } ?: -1
        } else {
            -1
        }

        // 更新 pageLoader 状态
        pageLoader.updatePages(pages, pageLoader.getProvider())

        val totalPages = pages.size
        // 指定分卷打开：定位到该分卷首页；续读/整本：使用已保存的整体进度。
        val startPage = if (targetPageOrderIndex >= 0 && chapters != null) {
            chapters.take(targetPageOrderIndex).sumOf { it.pages.size }
        } else {
            entry.lastReadPage
        }
        val clampedStart = startPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        val startChapterIndex = chapterIndexForPage(chapters, clampedStart)

        pageLoader.updateChapters(chapters, startChapterIndex)

        comicUri = entry.uri
        currentComicEntry = entry

        // 绑定书签上下文（修复此前未调用导致书签增删/持久化全部失效的问题）
        bookmarkManager.setComicUri(entry.uri)

        // 更新 UI 状态
        _uiState.value = _uiState.value.copy(
            title = entry.title,
            currentPage = clampedStart,
            totalPages = totalPages,
            isLoading = false,
            error = null,
            currentChapter = chapters?.getOrNull(startChapterIndex)?.title
                ?: (pages.getOrNull(clampedStart)?.chapterTitle ?: "")
        )

        // 开始新的阅读会话（持久化开始时间，供阅读时长统计与进程被杀后恢复）
        statsTracker.startSession(entry.uri)

        // 翻页统计基线设为起始页，避免续读时把已读页全部重复计入本次翻页数
        progressSaver.setBaseline(clampedStart)

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

    /**
     * 双页模式下，从当前页翻到下一对页需要前进/后退的页数。
     * 提取为公共方法，保证点击、自动翻页与卷末判断逻辑一致。
     */
    private fun dualPageStep(currentPage: Int, settings: com.mangareader.data.ReaderSettings): Int {
        if (!settings.dualPage) return 1
        val offset = settings.dualPageOffset.coerceIn(0, 5)
        val shifted = (currentPage - offset).coerceAtLeast(0)
        return if (settings.dualPageStartOne) {
            if (shifted % 2 == 0) 2 else 1
        } else {
            when (shifted) {
                0 -> 1
                else -> if (shifted % 2 == 0) 1 else 2
            }
        }
    }

    /**
     * 下一页。
     * @param autoContinue 自动翻页/TTS 连续朗读时为 true：跨卷不弹窗，直接进入下一卷；
     *                     手动翻页为 false：到达卷末时发送事件弹出跳转确认对话框。
     * @return 是否发生了实际翻页（自动模式下用于判断是否已到全书末尾）
     */
    fun nextPage(autoContinue: Boolean = false): Boolean {
        val state = _uiState.value
        if (state.totalPages <= 0) return false
        val step = dualPageStep(state.currentPage, state.settings)
        val next = state.currentPage + step
        val chapters = pageLoader.allChapters
        val currentIdx = chapterIndexForPage(chapters, state.currentPage)

        if (next >= state.totalPages) {
            // 已到全书末尾
            val hasNextChapter = chapters != null && currentIdx in 0 until chapters.size - 1
            if (hasNextChapter) {
                return if (autoContinue) {
                    jumpToNextChapter()
                } else {
                    _readerEvents.trySend(ReaderEvent.ShowChapterEnd)
                    false
                }
            } else {
                if (!autoContinue) {
                    // 最后一卷末尾：弹出"已是最后一卷"提示
                    _readerEvents.trySend(ReaderEvent.ShowChapterEnd)
                }
                return false
            }
        }

        val nextIdx = chapterIndexForPage(chapters, next)
        val crossedChapter = chapters != null && currentIdx in 0 until chapters.size - 1 &&
                nextIdx > currentIdx
        return if (crossedChapter) {
            if (autoContinue) {
                // 自动模式：进入下一卷首页，保证双页模式下不会把两卷拼成一对页或跳过新卷首页
                jumpToNextChapter()
            } else {
                _readerEvents.trySend(ReaderEvent.ShowChapterEnd)
                false
            }
        } else {
            setCurrentPage(next)
            true
        }
    }

    fun previousPage() {
        val state = _uiState.value
        if (state.totalPages <= 0) return
        val step = dualPageStep(state.currentPage, state.settings)
        val prev = state.currentPage - step
        val chapters = pageLoader.allChapters
        val currentIdx = chapterIndexForPage(chapters, state.currentPage)

        if (prev < 0) {
            if (chapters != null && currentIdx > 0) {
                // 第一卷之前还有上一卷，跳到上一卷末尾
                jumpToPrevChapter()
            }
            // 否则停在第一页
            return
        }

        val prevChapterIdx = chapterIndexForPage(chapters, prev)
        if (chapters != null && currentIdx >= 0 && prevChapterIdx < currentIdx) {
            // 跨入上一卷：直接跳到上一卷末尾
            jumpToPrevChapter()
        } else {
            setCurrentPage(prev)
        }
    }

    /** 跳到下一卷的首页（全局页码跳转，无需重新解析漫画）。@return 是否成功跳转 */
    fun jumpToNextChapter(): Boolean {
        val chapters = pageLoader.allChapters ?: return false
        val currentIdx = chapterIndexForPage(chapters, _uiState.value.currentPage)
        val nextIdx = currentIdx + 1
        if (nextIdx in chapters.indices) {
            val start = chapters.take(nextIdx).sumOf { it.pages.size }
            if (start == _uiState.value.currentPage) return false
            setCurrentPage(start)
            return true
        }
        return false
    }

    /** 跳到上一卷的最后一页（全局页码跳转，无需重新解析漫画）。@return 是否成功跳转 */
    fun jumpToPrevChapter(): Boolean {
        val chapters = pageLoader.allChapters ?: return false
        val currentIdx = chapterIndexForPage(chapters, _uiState.value.currentPage)
        if (currentIdx <= 0) return false
        val prevChapter = chapters[currentIdx - 1]
        val prevStart = chapters.take(currentIdx - 1).sumOf { it.pages.size }
        val target = prevStart + prevChapter.pages.size - 1
        if (target == _uiState.value.currentPage) return false
        setCurrentPage(target)
        return true
    }

    /**
     * 检查是否到达章节末尾。
     * @return Pair(是否在当前卷末尾, 是否已是最后一卷)
     */
    fun checkChapterEnd(): Pair<Boolean, Boolean> {
        val state = _uiState.value
        val chapters = pageLoader.allChapters
        val currentIdx = chapterIndexForPage(chapters, state.currentPage)

        if (chapters == null || currentIdx < 0) {
            // 整本（无分卷标记）模式
            val isAtEnd = state.currentPage >= state.totalPages - 1
            return Pair(isAtEnd, isAtEnd)
        }

        val chapterStart = chapters.take(currentIdx).sumOf { it.pages.size }
        val lastPageOfChapter = chapterStart + chapters[currentIdx].pages.size - 1
        val isAtChapterEnd = state.currentPage >= lastPageOfChapter
        val isLastChapter = currentIdx >= chapters.size - 1

        return Pair(isAtChapterEnd, isLastChapter)
    }

    /**
     * 获取下一卷的标题（用于卷末对话框显示），没有下一卷时返回 null。
     */
    fun getNextChapterTitle(): String? {
        val chapters = pageLoader.allChapters ?: return null
        val currentIdx = chapterIndexForPage(chapters, _uiState.value.currentPage)
        val nextIdx = currentIdx + 1
        return chapters.getOrNull(nextIdx)?.title
    }

    /**
     * 跳转到当前分卷内的第 [targetInChapter] 页（1 起）。
     * 基于当前全局页码实时定位分卷，不依赖标题匹配（混合散页场景下标题可能为空或重名）。
     * 无分卷信息时按整本页码跳转。
     */
    fun jumpToPageInCurrentChapter(targetInChapter: Int) {
        val chapters = pageLoader.allChapters
        val current = _uiState.value.currentPage
        if (chapters == null) {
            setCurrentPage((targetInChapter - 1).coerceAtLeast(0))
            return
        }
        val idx = chapterIndexForPage(chapters, current)
        if (idx < 0) {
            setCurrentPage((targetInChapter - 1).coerceAtLeast(0))
            return
        }
        val chapterStart = chapters.take(idx).sumOf { it.pages.size }
        val target = chapterStart + (targetInChapter - 1).coerceAtLeast(0)
        setCurrentPage(target)
    }

    fun setCurrentPage(page: Int) {
        val clamped = page.coerceIn(0, (pageLoader.pages.size - 1).coerceAtLeast(0))
        val oldPage = _uiState.value.currentPage
        if (clamped != oldPage) {
            // 用户手动翻页或成功跳转后重置 TTS 空页跳过计数
            ttsController.ttsEmptySkipCount.set(0)
            // 同步当前分卷下标，供卷末判断与上一卷/下一卷跳转使用
            val chapterIdx = chapterIndexForPage(pageLoader.allChapters, clamped)
            // 散页（页内 chapterTitle 为空）会被聚合进以漫画名命名的合成卷，
            // 标题需从分卷列表解析，否则顶部卷名/卷末提示为空。
            val chapter = pageLoader.allChapters?.getOrNull(chapterIdx)?.title
                ?: (pageLoader.pages.getOrNull(clamped)?.chapterTitle ?: "")
            pageLoader.setCurrentChapterIndex(chapterIdx)
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
            onSkipPage = { nextPage(autoContinue = true) }
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
            advancePage = { nextPage(autoContinue = true) },
            getChapterTitle = { getPageChapterTitle() },
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
        // 结束阅读会话；若 releaseCache 已结算，内部原子标志保证不会重复。
        statsTracker.endSession(sessionUri)
        MangaReaderApplication.applicationScope.launch(Dispatchers.IO) {
            // 1. 兜底保存当前阅读进度与总页数。
            try {
                withTimeoutOrNull(EXIT_SAVE_TIMEOUT_MS) {
                    if (sessionUri != null && currentPage >= 0) {
                        // 兜底只写整本整体进度（全局页码）；分卷级进度由 ProgressSaver 常规保存，
                        // 这里无法可靠换算卷内页码，写全局值反而会污染分卷键。
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


