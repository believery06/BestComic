package com.mangareader.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mangareader.data.ComicPage
import com.mangareader.parser.ComicParser
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 基于现有 [ComicParser] 实现的 [ComicProvider] 适配器。
 *
 * 在完全迁移到原生 Provider 之前，它把 parser 解析出的 [ComicPage] 列表
 * 包装成统一接口，使阅读器可以按页按需读取并复用缓存层。
 */
class ParserBasedComicProvider(
    override val title: String,
    private val pages: List<ComicPage>,
    private val parser: ComicParser? = null,
    override val cacheId: String = title
) : ComicProvider {

    override val pageCount: Int get() = pages.size

    override suspend fun getPage(index: Int): Bitmap? {
        return pages.getOrNull(index)?.load?.let { it.invoke() }
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

    override suspend fun getThumbnail(index: Int, maxDimension: Int): Bitmap? {
        val page = pages.getOrNull(index) ?: return null
        // 优先从原始字节流解码缩略图，避免全分辨率位图进入内存
        page.loadStream.let { provider ->
            try {
                provider.invoke()?.use { stream ->
                    return decodeSampled(stream, maxDimension, maxDimension)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // fallback：按旧路径加载完整位图再缩放（主要用于 PDF 等无法提供流的格式）
        return try {
            val raw = page.load.let { it.invoke() } ?: return null
            if (raw.isRecycled) return null
            val scale = (maxDimension.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
            val w = (raw.width * scale).toInt().coerceAtLeast(1)
            val h = (raw.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(raw, w, h, true)
            if (scaled !== raw && !raw.isRecycled) {
                runCatching { raw.recycle() }
            }
            scaled
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun close() {
        parser?.close()
    }

    private fun decodeSampled(stream: InputStream, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val bytes = stream.use { it.readBytes() }
            if (bytes.isEmpty()) return null
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null
            val sample = calculateInSampleSize(boundsOptions.outWidth, boundsOptions.outHeight, reqWidth, reqHeight)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        if (width <= 0 || height <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1
        var inSampleSize = 1
        var halfWidth = width
        var halfHeight = height
        while (halfWidth / 2 >= reqWidth && halfHeight / 2 >= reqHeight) {
            halfWidth /= 2
            halfHeight /= 2
            inSampleSize *= 2
        }
        return inSampleSize
    }
}
