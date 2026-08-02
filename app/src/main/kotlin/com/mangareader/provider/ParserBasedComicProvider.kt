package com.mangareader.provider

import android.graphics.Bitmap
import android.util.Log
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicProvider
import com.mangareader.parser.ComicParser
import com.mangareader.utils.BitmapDecoderUtils
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** * 基于现有 [ComicParser] 实现的 [ComicProvider] 适配器。
 *
 * 在完全迁移到原生 Provider 之前，它把 parser 解析出的 [ComicPage] 列表
 * 包装成统一接口，使阅读器可以按页按需读取并复用缓存层。
 */
class ParserBasedComicProvider(
    override val title: String,
    private val pages: List<ComicPage>,
    private var parser: ComicParser? = null,
    override val cacheId: String = title
) : ComicProvider {

    override val pageCount: Int get() = pages.size

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        private const val TAG = "ParserBasedComicProvider"
    }

    override suspend fun getPage(index: Int): Bitmap? {
        return try {
            pages.getOrNull(index)?.load?.let { loader ->
                loader.invoke()?.takeIf { !it.isRecycled }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "加载页面失败 index=$index", e)
            null
        }
    }

    override suspend fun getPageStream(index: Int): InputStream? {
        return pages.getOrNull(index)?.loadStream?.let { it.invoke() }
    }

    override fun getPageName(index: Int): String {
        return pages.getOrNull(index)?.name ?: ""
    }

    override fun getChapterTitle(index: Int): String {
        return pages.getOrNull(index)?.chapterTitle ?: ""
    }

    override suspend fun getPageText(index: Int): String? {
        return pages.getOrNull(index)?.textContent?.takeIf { it.isNotBlank() }
    }

    override suspend fun getThumbnail(index: Int, maxDimension: Int): Bitmap? {
        val page = pages.getOrNull(index) ?: return null
        // 优先从原始字节流解码缩略图，避免全分辨率位图进入内存
        page.loadStream.let { provider ->
            try {
                provider.invoke()?.use { stream ->
                    return decodeSampled(stream, maxDimension, maxDimension)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "加载缩略图流失败", e)
            }
        }
        // fallback：按旧路径加载完整位图再缩放（主要用于 PDF 等无法提供流的格式）。
        // 注意：此路径仍可能加载大图，仅作为兜底；建议各 Provider 都实现 loadStream。
        return try {
            val raw = page.load.let { it.invoke() } ?: return null
            if (raw.isRecycled) return null
            val scale = (maxDimension.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
            val w = (raw.width * scale).toInt().coerceAtLeast(1)
            val h = (raw.height * scale).toInt().coerceAtLeast(1)
            // 不回收 raw：该位图可能来自 PageCacheManager 内存缓存或 ComicPage 共享对象，
            // 回收它会导致其他位置访问已回收位图。创建缩略图副本后让原图由缓存/GC 管理。
            Bitmap.createScaledBitmap(raw, w, h, true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "缩略图 fallback OOM", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "加载缩略图失败", e)
            null
        }
    }

    override fun close() {
        // 幂等关闭：避免重复调用导致底层解析器异常或资源二次释放。
        if (!closed.compareAndSet(false, true)) return
        parser?.close()
        parser = null
    }

    private fun decodeSampled(stream: InputStream, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val bytes = stream.use { it.readBytes() }
            BitmapDecoderUtils.decodeSampled(bytes, reqWidth, reqHeight, tag = TAG)
        } catch (e: Exception) {
            Log.e(TAG, "采样解码缩略图失败", e)
            null
        }
    }
}
