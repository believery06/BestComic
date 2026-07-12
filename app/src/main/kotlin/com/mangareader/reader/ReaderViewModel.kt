package com.mangareader.reader

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import com.mangareader.Constants
import com.mangareader.data.ReaderSettings
import com.mangareader.data.ScrollMode
import com.mangareader.data.SettingsRepository
import com.mangareader.data.ZoomMode
import com.mangareader.cache.PageCacheManager
import com.mangareader.parser.ComicParser
import com.mangareader.parser.FolderParser
import com.mangareader.parser.ParserFactory
import com.mangareader.parser.ParserResult
import com.mangareader.provider.ComicProvider
import com.mangareader.provider.ParserBasedComicProvider
import com.mangareader.utils.ImageUtils
import com.mangareader.utils.PanelDetector
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var pages: List<ComicPage> = emptyList()
    private var comicUri: Uri? = null
    private var bookmarks: List<Int> = emptyList()
    private var lastRecordedPage: Int = -1
    private var autoPageJob: kotlinx.coroutines.Job? = null
    private var loadJob: kotlinx.coroutines.Job? = null
    private var flipCount: Int = 0
    private var parserRef: ComicParser? = null
    private var provider: ComicProvider? = null

    // L1（内存，前后各 15 页窗口）+ L2（磁盘）缓存管理器
    private val cacheManager = PageCacheManager(application)
    // 处理后的位图仅保留当前页（随设置变化即失效）
    private val processedBitmapCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, Bitmap>(2, 0.75f, true))
    private val maxProcessedCacheSize = 3
    private var progressJob: kotlinx.coroutines.Job? = null
    private var cachePageJob: kotlinx.coroutines.Job? = null
    private var panelJob: kotlinx.coroutines.Job? = null
    private var bookmarkJob: kotlinx.coroutines.Job? = null
    private var settingsJob: kotlinx.coroutines.Job? = null

    fun setScreenSize(width: Int, height: Int) {
        cacheManager.setScreenSize(width, height)
    }

    init {
        viewModelScope.launch {
            settingsRepository.readerSettings.collect { settings ->
                _uiState.value = _uiState.value.copy(settings = settings)
                // restart auto-page if interval changed
                val interval = settings.autoPageInterval
                if (interval > 0 && !_uiState.value.showMenu && pages.isNotEmpty()) {
                    startAutoPage(interval)
                } else {
                    stopAutoPage()
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.getBookmarks().collect { map ->
                comicUri?.let { uri ->
                    bookmarks = map[uri.toString()] ?: emptyList()
                    _uiState.value = _uiState.value.copy(bookmarks = bookmarks)
                }
            }
        }
    }

    fun loadComic(entry: ComicEntry, chapterPages: List<ComicPage>? = null) {
        loadJob?.cancel()
        parserRef?.close()
        parserRef = null
        provider?.close()
        provider = null
        comicUri = entry.uri
        pages = emptyList()
        com.mangareader.utils.FileListHelper.clearCache()
        cacheManager.clearMemory()
        clearProcessedCache()
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            title = entry.title,
            error = null,
            bookmarks = emptyList(),
            currentChapter = "",
            totalPages = 0,
            currentPage = 0,
            settingsVersion = _uiState.value.settingsVersion + 1
        )
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolvedPages = if (chapterPages != null) {
                    // 分卷浏览直接传入该卷页面，不再重新解析整本漫画
                    provider = ParserBasedComicProvider(entry.title, chapterPages, parser = null, cacheId = entry.uri.toString())
                    chapterPages
                } else {
                    val parser = when {
                        entry.isDirectory || entry.type == ComicType.FOLDER ||
                                entry.type == ComicType.COMIC_BOOK -> {
                            ParserFactory.getParser(ComicType.COMIC_BOOK)
                        }
                        else -> ParserFactory.getParser(entry.type)
                    }
                    if (parser == null) {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = "不支持的格式")
                        return@launch
                    }
                    parserRef = parser
                    val result = parser.parse(getApplication(), entry.uri)
                    if (result is ParserResult.Error) {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                        return@launch
                    }
                    val p = (result as ParserResult.Success).pages
                    provider = ParserBasedComicProvider(entry.title, p, parser, entry.uri.toString())
                    p
                }

                if (resolvedPages.isEmpty()) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "该文件没有可显示的页面")
                    return@launch
                }
                pages = resolvedPages
                val savedPage = settingsRepository.getProgress(entry.uri).first()
                val startPage = savedPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                settingsRepository.saveTotalPages(entry.uri, pages.size)
                bookmarks = settingsRepository.getBookmarks().first()[entry.uri.toString()] ?: emptyList()
                val chapter = pages.getOrNull(startPage)?.chapterTitle ?: ""
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    totalPages = pages.size,
                    currentPage = startPage,
                    error = null,
                    bookmarks = bookmarks,
                    currentChapter = chapter
                )
                lastRecordedPage = startPage
                provider?.let { cacheManager.setCurrentPage(it, startPage) }
                val interval = _uiState.value.settings.autoPageInterval
                if (interval > 0) startAutoPage(interval)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "加载失败：${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    private fun clearProcessedCache() {
        synchronized(processedBitmapCache) {
            processedBitmapCache.values.forEach { bmp ->
                if (!bmp.isRecycled) runCatching { bmp.recycle() }
            }
            processedBitmapCache.clear()
        }
    }

    fun nextPage() {
        val state = _uiState.value
        if (state.settings.scrollMode != ScrollMode.PAGE) return
        if (pages.isEmpty()) return

        val step = when {
            !state.settings.dualPage -> 1
            state.settings.dualPageStartOne -> {
                // 1+2模式: [0,1], [2,3], [4,5]...
                // 偶数页=左页(step=2跳到下一组), 奇数页=右页(step=1跳到下一组左页)
                if (state.currentPage % 2 == 0) 2 else 1
            }
            else -> {
                // 封面单页模式: [0], [1,2], [3,4]...
                when (state.currentPage) {
                    0 -> 1  // 封面页跳到第一个双页组
                    else -> if (state.currentPage % 2 == 0) 1 else 2
                    // 偶数页(>=2)=右页(step=1跳到下一组左页), 奇数页(>=1)=左页(step=2跳到下一组)
                }
            }
        }
        val target = state.currentPage + step

        if (state.currentPage >= pages.size - 1) {
            jumpToNextChapter()
        } else {
            setCurrentPage(target.coerceAtMost(pages.size - 1))
        }
    }

    fun previousPage() {
        val state = _uiState.value
        if (state.settings.scrollMode != ScrollMode.PAGE) return
        if (pages.isEmpty()) return

        val step = when {
            !state.settings.dualPage -> 1
            state.settings.dualPageStartOne -> {
                // 1+2模式: [0,1], [2,3], [4,5]...
                // 偶数页=左页(step=2回上一组), 奇数页=右页(step=1回当前组左页)
                if (state.currentPage % 2 == 0) 2 else 1
            }
            else -> {
                // 封面单页模式: [0], [1,2], [3,4]...
                when (state.currentPage) {
                    0 -> 1  // 封面页，回退到上一章节
                    1 -> 1  // 第一个双页组的左页，回退到封面
                    else -> if (state.currentPage % 2 == 0) 1 else 2
                    // 偶数页(>=2)=右页(step=1回当前组左页), 奇数页(>=3)=左页(step=2回上一组)
                }
            }
        }
        val target = state.currentPage - step

        if (state.currentPage <= 0) {
            jumpToPrevChapter()
        } else {
            setCurrentPage(target.coerceAtLeast(0))
        }
    }

    /**
     * 跳转到下一章节的第一页（基于 chapterTitle 边界）。
     */
    fun jumpToNextChapter() {
        if (pages.isEmpty()) return
        val currentChapter = pages.getOrNull(_uiState.value.currentPage)?.chapterTitle ?: return
        for (i in (_uiState.value.currentPage + 1) until pages.size) {
            if (pages[i].chapterTitle.isNotEmpty() && pages[i].chapterTitle != currentChapter) {
                setCurrentPage(i)
                return
            }
        }
        // 没有下一章
    }

    /**
     * 跳转到上一章节的最后一页。
     */
    fun jumpToPrevChapter() {
        if (pages.isEmpty()) return
        val currentChapter = pages.getOrNull(_uiState.value.currentPage)?.chapterTitle ?: return
        for (i in (_uiState.value.currentPage - 1) downTo 0) {
            if (pages[i].chapterTitle.isNotEmpty() && pages[i].chapterTitle != currentChapter) {
                // 找到上一章的最后一页
                var lastPage = i
                while (lastPage + 1 < pages.size && pages[lastPage + 1].chapterTitle == pages[i].chapterTitle) {
                    lastPage++
                }
                setCurrentPage(lastPage)
                return
            }
        }
    }

    fun setCurrentPage(page: Int) {
        val clamped = page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        val oldPage = _uiState.value.currentPage
        if (clamped != oldPage) {
            val chapter = pages.getOrNull(clamped)?.chapterTitle ?: ""
            _uiState.value = _uiState.value.copy(currentPage = clamped, currentChapter = chapter)
            comicUri?.let { uri ->
                progressJob?.cancel()
                progressJob = viewModelScope.launch {
                    settingsRepository.saveProgress(uri, clamped)
                    // 记录阅读进度统计（仅累计向前翻页，避免重复计算）
                    val delta = (clamped - lastRecordedPage).coerceAtLeast(0)
                    val finished = clamped >= (pages.size - 1).coerceAtLeast(0)
                    if (delta > 0 || finished) {
                        settingsRepository.updateReadingStats(
                            pagesDelta = delta,
                            comicFinished = finished
                        )
                    }
                    if (clamped > lastRecordedPage) lastRecordedPage = clamped
                }
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
            provider?.let { currentProvider ->
                cachePageJob?.cancel()
                cachePageJob = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        cacheManager.setCurrentPage(currentProvider, clamped, direction)
                    } catch (e: Exception) {
                        android.util.Log.w("ReaderViewModel", "缓存加载失败: ${e.message}")
                    }
                }
            }
        }
    }

    private fun startAutoPage(seconds: Int) {
        stopAutoPage()
        autoPageJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(seconds * 1000L)
                val state = _uiState.value
                if (state.showMenu) continue
                if (state.totalPages <= 0) continue
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
                    // 到达末尾时尝试进入下一章节，没有下一章再停止
                    jumpToNextChapter()
                    if (_uiState.value.currentPage == state.currentPage) {
                        stopAutoPage()
                        break
                    }
                } else {
                    setCurrentPage(next)
                }
            }
        }
    }

    private fun stopAutoPage() {
        autoPageJob?.cancel()
        autoPageJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopAutoPage()
        progressJob?.cancel()
        cachePageJob?.cancel()
        panelJob?.cancel()
        bookmarkJob?.cancel()
        settingsJob?.cancel()
        parserRef?.close()
        parserRef = null
        provider?.close()
        provider = null
        cacheManager.clearMemory()
        synchronized(processedBitmapCache) {
            processedBitmapCache.values.forEach { bmp ->
                if (!bmp.isRecycled) runCatching { bmp.recycle() }
            }
            processedBitmapCache.clear()
        }
    }

    /**
     * 加载指定页的缩略图（最大边不超过 [maxDimension] 像素），
     * 优先走 L1/L2 缓存，避免重复解码。
     */
    suspend fun loadThumbnail(index: Int, maxDimension: Int = 180): Bitmap? = withContext(Dispatchers.IO) {
        val currentProvider = provider ?: return@withContext null
        if (index !in pages.indices) return@withContext null
        try {
            cacheManager.getThumbnail(currentProvider, index, maxDimension)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun loadPageBitmap(index: Int): Bitmap? = withContext(Dispatchers.IO) {
        val currentProvider = provider ?: return@withContext null
        if (index !in pages.indices) return@withContext null
        try {
            val state = _uiState.value
            val cacheKey = "${index}_${state.settingsVersion}"
            synchronized(processedBitmapCache) { processedBitmapCache[cacheKey] }?.let { cached ->
                if (!cached.isRecycled) return@withContext cached
            }

            // 单页加载整体超时 10 秒，避免某些损坏文件导致一直转圈
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
            result = ImageUtils.applyAdjustments(
                src = result,
                brightness = state.settings.brightness,
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
            if (croppedBmp != null && croppedBmp !== result && !croppedBmp.isRecycled) {
                runCatching { croppedBmp.recycle() }
            }
            // 仅当 result 是独立于原始位图的新对象时才缓存；
            if (result !== rawBmp) {
                synchronized(processedBitmapCache) {
                    processedBitmapCache[cacheKey] = result
                    while (processedBitmapCache.size > maxProcessedCacheSize) {
                        val firstKey = processedBitmapCache.keys.firstOrNull() ?: break
                        val evicted = processedBitmapCache.remove(firstKey)
                        if (evicted != null && evicted !== result && !evicted.isRecycled) {
                            runCatching { evicted.recycle() }
                        }
                    }
                }
            }
            result
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun toggleMenu() {
        val showing = !_uiState.value.showMenu
        _uiState.value = _uiState.value.copy(showMenu = showing)
        val interval = _uiState.value.settings.autoPageInterval
        if (interval > 0 && !showing && pages.isNotEmpty()) {
            startAutoPage(interval)
        } else if (showing) {
            stopAutoPage()
        }
    }

    fun hideMenu() {
        _uiState.value = _uiState.value.copy(showMenu = false)
        val interval = _uiState.value.settings.autoPageInterval
        if (interval > 0 && pages.isNotEmpty()) startAutoPage(interval)
    }

    fun toggleDualPage() = updateSettings { it.copy(dualPage = !it.dualPage) }
    fun toggleDualPageStartOne() = updateSettings { it.copy(dualPageStartOne = !it.dualPageStartOne) }
    fun toggleRtl() = updateSettings { it.copy(rtl = !it.rtl) }
    fun toggleImmersive() = updateSettings { it.copy(immersive = !it.immersive) }
    fun toggleAutoCrop() = updateSettings { it.copy(autoCrop = !it.autoCrop) }
    fun toggleGrayscale() = updateSettings { it.copy(grayscale = !it.grayscale) }
    fun toggleSharpen() = updateSettings { it.copy(sharpen = !it.sharpen) }
    fun toggleDenoise() = updateSettings { it.copy(denoise = !it.denoise) }
    fun toggleMirror() = updateSettings { it.copy(mirror = !it.mirror) }
    fun togglePageShadow() = updateSettings { it.copy(pageShadow = !it.pageShadow) }
    fun toggleNightMode() = updateSettings { it.copy(nightMode = !it.nightMode) }
    fun toggleEyeCare() = updateSettings { it.copy(eyeCare = !it.eyeCare) }
    fun toggleVolumeKeyNav() = updateSettings { it.copy(volumeKeyNav = !it.volumeKeyNav) }
    fun toggleEnableZoom() = updateSettings { it.copy(enableZoom = !it.enableZoom) }
    fun toggleMagnifier() = updateSettings { it.copy(magnifierEnabled = !it.magnifierEnabled) }
    fun setSaturation(value: Float) = updateSettings { it.copy(saturation = value.coerceIn(0f, 2f)) }
    fun setSharpenStrength(value: Float) = updateSettings { it.copy(sharpenStrength = value.coerceIn(0f, 3f)) }
    fun setDenoiseStrength(value: Float) = updateSettings { it.copy(denoiseStrength = value.coerceIn(0f, 3f)) }
    fun setTapZoneSize(size: com.mangareader.data.TapZoneSize) = updateSettings { it.copy(tapZoneSize = size) }
    fun setScaleFilter(filter: com.mangareader.data.ScaleFilter) = updateSettings { it.copy(scaleFilter = filter) }
    fun setScrollMode(mode: ScrollMode) = updateSettings { it.copy(scrollMode = mode) }
    fun setZoomMode(mode: ZoomMode) = updateSettings { it.copy(zoomMode = mode) }
    fun setAutoPageInterval(seconds: Int) = updateSettings {
        it.copy(autoPageInterval = seconds.coerceIn(0, 60))
    }

    fun setBrightness(value: Float) = updateSettings { it.copy(brightness = value.coerceIn(0.5f, 2f)) }
    fun setContrast(value: Float) = updateSettings { it.copy(contrast = value.coerceIn(0.5f, 2f)) }
    fun setGamma(value: Float) = updateSettings { it.copy(gamma = value.coerceIn(0.5f, 2.5f)) }
    fun setDualPageSpacing(value: Int) = updateSettings { it.copy(dualPageSpacing = value.coerceIn(0, 32)) }
    fun setDualPageOffset(value: Int) = updateSettings { it.copy(dualPageOffset = value.coerceIn(0, 5)) }

    fun resetFilters() = updateSettings {
        it.copy(brightness = 1f, contrast = 1f, rotation = 0, gamma = 1f, saturation = 1f, nightMode = false, eyeCare = false)
    }
    fun resetAll() = updateSettings {
        it.copy(
            brightness = 1f, contrast = 1f, rotation = 0,
            grayscale = false, sharpen = false, autoCrop = false,
            mirror = false, pageShadow = false, gamma = 1f,
            dualPageStartOne = false, saturation = 1f, nightMode = false, eyeCare = false
        )
    }
    fun rotate() = updateSettings { it.copy(rotation = (it.rotation + 90) % 360) }
    fun setBackgroundColor(color: String) = updateSettings { it.copy(backgroundColor = color) }
    fun setBackgroundTexture(texture: com.mangareader.data.BackgroundTexture) = updateSettings { it.copy(backgroundTexture = texture) }
    fun setPageAnimation(animation: com.mangareader.data.PageAnimation) = updateSettings { it.copy(pageAnimation = animation, randomAnimation = false) }
    fun toggleRandomAnimation() = updateSettings { it.copy(randomAnimation = !it.randomAnimation) }

    fun toggleBookmark() {
        val page = _uiState.value.currentPage
        toggleBookmarkAt(page)
    }

    /**
     * 添加或移除指定页的书签，并立即更新 UI 状态。
     */
    fun toggleBookmarkAt(page: Int) {
        val uri = comicUri ?: return
        val currentlyBookmarked = bookmarks.contains(page)
        val newBookmarks = if (currentlyBookmarked) {
            bookmarks.filter { it != page }
        } else {
            (bookmarks + page).sorted()
        }
        bookmarks = newBookmarks
        _uiState.value = _uiState.value.copy(bookmarks = newBookmarks)
        bookmarkJob?.cancel()
        bookmarkJob = viewModelScope.launch {
            if (currentlyBookmarked) {
                settingsRepository.removeBookmark(uri, page)
            } else {
                settingsRepository.addBookmark(uri, page)
            }
        }
    }

    /**
     * 删除指定页的书签。
     */
    fun removeBookmark(page: Int) {
        val uri = comicUri ?: return
        if (!bookmarks.contains(page)) return
        val newBookmarks = bookmarks.filter { it != page }
        bookmarks = newBookmarks
        _uiState.value = _uiState.value.copy(bookmarks = newBookmarks)
        bookmarkJob?.cancel()
        bookmarkJob = viewModelScope.launch { settingsRepository.removeBookmark(uri, page) }
    }

    /**
     * 生成当前页的分享卡片并保存到缓存目录，返回 PNG 文件。
     */
    suspend fun createShareCard(pageIndex: Int): File? = withContext(Dispatchers.IO) {
        try {
            val bmp = loadPageBitmap(pageIndex) ?: return@withContext null
            if (bmp.isRecycled) return@withContext null
            val card = ImageUtils.createShareCard(
                src = bmp,
                comicTitle = _uiState.value.title,
                pageNumber = pageIndex + 1,
                appName = Constants.APP_NAME
            ) ?: return@withContext null
            val file = File(getApplication<Application>().cacheDir, "share_card_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                card.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!card.isRecycled) card.recycle()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val newSettings = transform(_uiState.value.settings)
        // settingsVersion 递增触发 UI 中 LaunchedEffect 重新加载当前页位图，
        // 使亮度/对比度/旋转等调整在未关闭菜单时立即生效
        _uiState.value = _uiState.value.copy(
            settings = newSettings,
            settingsVersion = _uiState.value.settingsVersion + 1
        )
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch { settingsRepository.saveReaderSettings(newSettings) }
    }

    /**
     * 计算章节列表：每个章节的 (title, startIndex, endIndex)，并按分卷名称自然排序。
     */
    fun getChapterList(): List<Triple<String, Int, Int>> {
        if (pages.isEmpty()) return emptyList()
        val result = mutableListOf<Triple<String, Int, Int>>()
        var currentTitle = ""
        var startIndex = 0
        for (i in pages.indices) {
            val title = pages[i].chapterTitle
            if (title.isNotEmpty() && title != currentTitle) {
                if (currentTitle.isNotEmpty()) {
                    result.add(Triple(currentTitle, startIndex, i - 1))
                }
                currentTitle = title
                startIndex = i
            }
        }
        if (currentTitle.isNotEmpty()) {
            result.add(Triple(currentTitle, startIndex, pages.size - 1))
        }
        return result.sortedWith { a, b -> FolderParser.compareNames(a.first, b.first) }
    }

    /**
     * 计算当前页在当前章节内的相对位置（1-based）
     */
    fun getPageInChapter(): Int {
        if (pages.isEmpty()) return 1
        val currentChapter = pages.getOrNull(_uiState.value.currentPage)?.chapterTitle ?: return 1
        var count = 0
        for (i in 0.._uiState.value.currentPage) {
            if (pages[i].chapterTitle == currentChapter) count++
        }
        return count
    }

    /**
     * 获取指定页所在的章节标题（空字符串表示无章节信息）。
     */
    fun getPageChapterTitle(index: Int): String {
        return pages.getOrNull(index)?.chapterTitle ?: ""
    }

    /**
     * 计算当前章节的总页数
     */
    fun getChapterPageCount(): Int {
        if (pages.isEmpty()) return _uiState.value.totalPages
        val currentChapter = pages.getOrNull(_uiState.value.currentPage)?.chapterTitle ?: return _uiState.value.totalPages
        return pages.count { it.chapterTitle == currentChapter }
    }

    // 分镜模式状态
    private val _panelState = MutableStateFlow(PanelViewState())
    val panelState: StateFlow<PanelViewState> = _panelState.asStateFlow()

    /** 进入分镜模式 */
    fun enterPanelView() {
        stopAutoPage()
        panelJob?.cancel()
        panelJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _panelState.value = _panelState.value.copy(isLoading = true)
                val page = _uiState.value.currentPage
                if (page !in pages.indices) {
                    _panelState.value = PanelViewState()
                    return@launch
                }
                // 使用经过滤镜/旋转处理后的位图进行分镜检测，结果更准确
                val currentProvider = provider ?: run {
                    _panelState.value = PanelViewState()
                    return@launch
                }
                val bmp = cacheManager.getPage(currentProvider, page) ?: run {
                    _panelState.value = PanelViewState()
                    return@launch
                }
                if (bmp.isRecycled) {
                    _panelState.value = PanelViewState()
                    return@launch
                }
                val rtl = _uiState.value.settings.rtl
                val panels = PanelDetector.detectPanels(bmp, rtl)
                if (panels.isEmpty()) {
                    _panelState.value = PanelViewState()
                    return@launch
                }
                _panelState.value = PanelViewState(
                    panels = panels,
                    currentPanelIndex = 0,
                    isActive = true,
                    pageIndex = page,
                    isLoading = false
                )
            } catch (e: Exception) {
                e.printStackTrace()
                _panelState.value = PanelViewState()
            }
        }
    }

    /** 退出分镜模式 */
    fun exitPanelView() {
        _panelState.value = PanelViewState()
    }

    /** 分镜模式：下一格 */
    fun nextPanel() {
        val state = _panelState.value
        if (!state.isActive) return
        if (state.currentPanelIndex < state.panels.size - 1) {
            _panelState.value = state.copy(currentPanelIndex = state.currentPanelIndex + 1)
        } else {
            // 最后一格，翻到下一页（如果有的话）
            exitPanelView()
            nextPage()
        }
    }

    /** 分镜模式：上一格 */
    fun prevPanel() {
        val state = _panelState.value
        if (!state.isActive) return
        if (state.currentPanelIndex > 0) {
            _panelState.value = state.copy(currentPanelIndex = state.currentPanelIndex - 1)
        } else {
            exitPanelView()
            previousPage()
        }
    }

    data class PanelViewState(
        val panels: List<PanelDetector.Panel> = emptyList(),
        val currentPanelIndex: Int = 0,
        val isActive: Boolean = false,
        val pageIndex: Int = 0,
        val isLoading: Boolean = false
    )

    data class ReaderUiState(
        val isLoading: Boolean = false,
        val title: String = "",
        val currentPage: Int = 0,
        val totalPages: Int = 0,
        val showMenu: Boolean = false,
        val settings: ReaderSettings = ReaderSettings(),
        val error: String? = null,
        val bookmarks: List<Int> = emptyList(),
        val currentChapter: String = "",
        val settingsVersion: Int = 0
    )
}
