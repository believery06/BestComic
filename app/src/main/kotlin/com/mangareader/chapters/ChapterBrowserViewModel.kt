package com.mangareader.chapters

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mangareader.data.ComicChapter
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import com.mangareader.parser.ComicParser
import com.mangareader.parser.ComicBookParser
import com.mangareader.parser.FolderParser
import com.mangareader.parser.ParserFactory
import com.mangareader.parser.ParserResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChapterBrowserViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val isLoading: Boolean = false,
        val title: String = "",
        val chapters: List<ComicChapter> = emptyList(),
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var parserRef: ComicParser? = null

    fun load(entry: ComicEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, title = entry.title)
            try {
                parserRef?.close()
                parserRef = null

                val (pages, parser) = resolvePages(entry)
                parserRef = parser

                if (pages.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "没有可识别的页面"
                    )
                    return@launch
                }

                val chapters = groupPagesIntoChapters(entry, pages)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    chapters = chapters
                )
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "解析失败：${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    /**
     * 加载指定分卷封面的缩略图（默认取该卷第一页）。
     * 优先从原始字节流按目标尺寸采样解码，避免全分辨率位图进入内存。
     */
    suspend fun loadThumbnail(page: ComicPage, maxDimension: Int = 220): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                page.loadStream()?.use { stream ->
                    val bytes = stream.readBytes()
                    if (bytes.isEmpty()) return@use null
                    val boundsOptions = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
                    if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return@use null
                    var sample = 1
                    var halfW = boundsOptions.outWidth
                    var halfH = boundsOptions.outHeight
                    while (halfW / 2 >= maxDimension && halfH / 2 >= maxDimension) {
                        halfW /= 2
                        halfH /= 2
                        sample *= 2
                    }
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                } ?: run {
                    // fallback：PDF 等无法提供字节流的格式
                    val raw = page.load() ?: return@withContext null
                    if (raw.isRecycled) return@withContext null
                    val scale = (maxDimension.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
                    val width = (raw.width * scale).toInt().coerceAtLeast(1)
                    val height = (raw.height * scale).toInt().coerceAtLeast(1)
                    val scaled = android.graphics.Bitmap.createScaledBitmap(raw, width, height, true)
                    if (scaled !== raw && !raw.isRecycled) {
                        runCatching { raw.recycle() }
                    }
                    scaled
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    private suspend fun resolvePages(entry: ComicEntry): Pair<List<ComicPage>, ComicParser?> {
        val context = getApplication<Application>()
        return when {
            entry.type == ComicType.COMIC_BOOK || entry.isDirectory || entry.type == ComicType.FOLDER -> {
                val parser = ComicBookParser()
                val result = parser.parse(context, entry.uri)
                if (result is ParserResult.Success) {
                    result.pages to parser
                } else {
                    emptyList<ComicPage>() to null
                }
            }
            else -> {
                val parser = ParserFactory.getParser(entry.type)
                if (parser == null) return emptyList<ComicPage>() to null
                val result = parser.parse(context, entry.uri)
                if (result is ParserResult.Success) {
                    result.pages to parser
                } else {
                    emptyList<ComicPage>() to null
                }
            }
        }
    }

    private fun groupPagesIntoChapters(entry: ComicEntry, pages: List<ComicPage>): List<ComicChapter> {
        val hasChapters = pages.any { it.chapterTitle.isNotEmpty() }
        if (!hasChapters) {
            return listOf(
                ComicChapter(
                    title = entry.title.ifEmpty { "全篇" },
                    uri = entry.uri,
                    type = entry.type,
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
                    result.add(
                        ComicChapter(
                            title = currentTitle,
                            uri = entry.uri,
                            type = entry.type,
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
            result.add(
                ComicChapter(
                    title = currentTitle.ifEmpty { "未命名" },
                    uri = entry.uri,
                    type = entry.type,
                    pages = currentPages.mapIndexed { idx, p -> p.copy(index = idx) }
                )
            )
        }

        // 按分卷名称自然排序，避免文件顺序导致分卷列表顺序混乱
        return result.sortedWith { a, b -> FolderParser.compareNames(a.title, b.title) }
    }

    override fun onCleared() {
        super.onCleared()
        parserRef?.close()
        parserRef = null
    }
}
