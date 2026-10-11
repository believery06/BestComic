package com.mangareader.reader

import android.app.Application
import android.net.Uri
import com.mangareader.data.ComicChapter
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicProvider
import com.mangareader.data.ComicType
import com.mangareader.parser.ComicParser
import com.mangareader.parser.ParserFactory
import com.mangareader.parser.ParserResult
import com.mangareader.provider.ParserBasedComicProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 漫画加载器：负责漫画解析、页面管理与分卷列表维护。
 * 从 ReaderViewModel 中提取，使 ViewModel 专注于状态聚合与事件转发。
 */
class PageLoader(
    private val application: Application,
    private val externalScope: CoroutineScope
) {
    var pages: List<ComicPage> = emptyList()
        private set
    var allChapters: List<ComicChapter>? = null
        private set
    var currentChapterIndex: Int = -1
        private set

    private var parserRef: ComicParser? = null
    private var provider: ComicProvider? = null

    fun getProvider(): ComicProvider? = provider

    fun updatePages(newPages: List<ComicPage>, newProvider: ComicProvider?) {
        pages = newPages
        provider = newProvider
    }

    fun updateChapters(chapters: List<ComicChapter>?, chapterIndex: Int) {
        allChapters = chapters
        currentChapterIndex = chapterIndex
    }

    fun setParser(parser: ComicParser?) {
        parserRef = parser
    }

    fun getParser(): ComicParser? = parserRef

    suspend fun resolvePages(
        entry: ComicEntry,
        chapterPages: List<ComicPage>?
    ): List<ComicPage> {
        return if (chapterPages != null) {
            provider = ParserBasedComicProvider(
                entry.title,
                chapterPages,
                parser = null,
                cacheId = entry.uri.toString()
            )
            chapterPages
        } else {
            val parser = when {
                entry.isDirectory || entry.type == ComicType.FOLDER ||
                        entry.type == ComicType.COMIC_BOOK -> {
                    ParserFactory.getParser(ComicType.COMIC_BOOK)
                }
                else -> ParserFactory.getParser(entry.type)
            }
            if (parser == null) return emptyList()
            parserRef = parser
            // PDF 解析器根据屏幕尺寸设置渲染预算
            if (parser is com.mangareader.parser.PdfParser) {
                val metrics = application.resources.displayMetrics
                val width = metrics.widthPixels
                val height = metrics.heightPixels
                val tablet = kotlin.math.min(width, height) >= (600 * metrics.density).toInt()
                val runtime = Runtime.getRuntime()
                val maxMemoryMb = runtime.maxMemory() / (1024 * 1024)
                val lowEnd = maxMemoryMb < 256L
                parser.setScreenSize(width, height, tablet, lowEnd)
            }
            val result = parser.parse(application, entry.uri)
            if (result is ParserResult.Error) return emptyList()
            val p = (result as ParserResult.Success).pages
            provider = ParserBasedComicProvider(entry.title, p, parser, entry.uri.toString())
            p
        }
    }

    fun releaseResources() {
        parserRef?.close()
        parserRef = null
        provider?.close()
        provider = null
    }

    /**
     * 计算整本阅读进度。
     * 新模型下所有分卷共享同一条全局页面列表，[page] 始终是全局页码，
     * 不再需要按 currentChapterIndex 累加（旧实现会把分卷前页数重复计算一遍）。
     */
    fun computeOverallProgress(page: Int): Pair<Int, Int> {
        val totalPages = allChapters?.sumOf { it.pages.size } ?: pages.size
        val safeTotal = totalPages.coerceAtLeast(0)
        return Pair(page.coerceIn(0, safeTotal), safeTotal)
    }

    fun setCurrentChapterIndex(index: Int) {
        currentChapterIndex = index
    }
}
