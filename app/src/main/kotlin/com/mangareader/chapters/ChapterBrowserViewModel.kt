package com.mangareader.chapters

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Immutable
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
import com.mangareader.R
import com.mangareader.parser.ParserResult
import com.mangareader.parser.VolumeSortHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

class ChapterBrowserViewModel(application: Application) : AndroidViewModel(application) {

    @Immutable
    data class UiState(
        val isLoading: Boolean = false,
        val title: String = "",
        val chapters: List<ComicChapter> = emptyList(),
        val error: String? = null,
        val continueAvailable: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val settingsRepository by lazy { com.mangareader.data.SettingsRepository.getInstance(getApplication()) }

    private var parserRef: ComicParser? = null

    companion object {
        private const val TAG = "ChapterBrowserViewModel"
        /** 缩略图源数据最大 20MB，防止异常大文件导致 OOM。 */
        private const val MAX_THUMBNAIL_BYTES = 20 * 1024 * 1024L
    }

    fun load(entry: ComicEntry) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, title = entry.title)
            try {
                parserRef?.close()
                parserRef = null

                val (pages, parser) = withContext(Dispatchers.IO) { resolvePages(entry) }
                parserRef = parser

                if (pages.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = getApplication<Application>().getString(R.string.chapter_browser_no_pages_error)
                    )
                    return@launch
                }

                val chapters = withContext(Dispatchers.IO) { groupPagesIntoChapters(entry, pages) }
                val continueAvailable = settingsRepository.getProgress(entry.uri).first() > 0
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    chapters = chapters,
                    continueAvailable = continueAvailable
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "加载分卷失败", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = getApplication<Application>().getString(
                        R.string.chapter_browser_parse_error,
                        e.message ?: e.javaClass.simpleName
                    )
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
                    // 限制读取字节数，避免单页过大导致 OOM
                    val bytes = stream.readBytesLimited(MAX_THUMBNAIL_BYTES)
                    if (bytes == null || bytes.isEmpty()) return@use null
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "加载分卷缩略图失败", e)
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
                    title = entry.title.ifEmpty { getApplication<Application>().getString(R.string.chapter_browser_whole_volume) },
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
                    title = currentTitle.ifEmpty { getApplication<Application>().getString(R.string.chapter_browser_unnamed) },
                    uri = entry.uri,
                    type = entry.type,
                    pages = currentPages.mapIndexed { idx, p -> p.copy(index = idx) }
                )
            )
        }

        // 按分卷名称智能排序，识别卷号/Vol/番外等语义，避免文件顺序导致分卷列表顺序混乱
        return result.sortedWith { a, b -> VolumeSortHelper.compareNames(a.title, b.title) }
    }

    override fun onCleared() {
        super.onCleared()
        parserRef?.close()
        parserRef = null
    }
}

/**
 * 读取 InputStream 内容并限制最大字节数，超过限制或协程取消时返回 null。
 */
private suspend fun InputStream.readBytesLimited(limit: Long): ByteArray? {
    val buffer = ByteArrayOutputStream()
    val temp = ByteArray(8192)
    var total = 0L
    while (currentCoroutineContext().isActive) {
        val read = this.read(temp)
        if (read <= 0) break
        total += read
        if (total > limit) return null
        buffer.write(temp, 0, read)
    }
    return buffer.toByteArray()
}
