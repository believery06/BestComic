package com.mangareader.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.max

/**
 * 位图采样解码工具。
 *
 * 提供基于目标尺寸的 inSampleSize 计算与 inBitmap 复用解码，
 * 避免单张大图 OOM 并减少 Bitmap 分配/回收开销。
 * 原位于 parser 包，现上提到 utils 以打破 parser↔utils 循环依赖。
 */
object BitmapDecoderUtils {

    private const val TAG = "BitmapDecoderUtils"

    /**
     * 计算 inSampleSize，使解码后图片长边不超过请求长边。
     * 结果一定是 2 的幂，符合 BitmapFactory 要求。
     */
    fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        if (width <= 0 || height <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1
        val maxReq = max(reqWidth, reqHeight)
        var inSampleSize = 1
        while (max(width / inSampleSize, height / inSampleSize) > maxReq) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    /**
     * 从字节数组采样解码，自动使用 [BitmapPool] 复用内存。
     *
     * @param bytes 原始图片数据
     * @param reqWidth 目标宽度，通常传屏幕宽度
     * @param reqHeight 目标高度，通常传屏幕高度
     * @param config 首选像素格式，默认 RGB_565
     * @param tag 日志标签
     * @return 解码后的 Bitmap，失败返回 null
     */
    fun decodeSampled(
        bytes: ByteArray,
        reqWidth: Int,
        reqHeight: Int,
        config: Bitmap.Config = Bitmap.Config.RGB_565,
        tag: String = TAG
    ): Bitmap? {
        if (bytes.isEmpty()) return null
        var reusableBitmap: Bitmap? = null
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

            val sample = calculateInSampleSize(
                boundsOptions.outWidth, boundsOptions.outHeight, reqWidth, reqHeight
            )
            val expectedW = boundsOptions.outWidth / sample
            val expectedH = boundsOptions.outHeight / sample

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = config
                reusableBitmap = BitmapPool.get(expectedW, expectedH, config)
                reusableBitmap?.let { inBitmap = it }
            }

            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            // 若平台未使用提供的 inBitmap，归还池中避免泄漏
            val pooled = reusableBitmap
            if (decoded !== decodeOptions.inBitmap && pooled != null && !pooled.isRecycled) {
                BitmapPool.put(pooled)
            }
            decoded
        } catch (e: CancellationException) {
            reusableBitmap?.takeIf { !it.isRecycled }?.let { BitmapPool.put(it) }
            throw e
        } catch (e: OutOfMemoryError) {
            reusableBitmap?.takeIf { !it.isRecycled }?.let { BitmapPool.put(it) }
            Log.e(tag, "采样解码 OOM", e)
            null
        } catch (e: Exception) {
            reusableBitmap?.takeIf { !it.isRecycled }?.let { BitmapPool.put(it) }
            Log.e(tag, "采样解码失败", e)
            null
        }
    }

    /**
     * 便捷方法：获取设备屏幕宽高，用于传给 [decodeSampled]。
     */
    fun getScreenSize(context: Context): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }
}

/**
 * 带大小上限的字节流读取。超过 [maxBytes] 时抛出 [IOException]，
 * 用于防止恶意或损坏归档中的超大单文件导致 OOM。
 */
fun InputStream.readBytesLimited(maxBytes: Long): ByteArray {
    val buffer = ByteArrayOutputStreamLimited(maxBytes)
    val temp = ByteArray(8192)
    var total = 0L
    while (true) {
        val read = this.read(temp)
        if (read <= 0) break
        total += read
        if (total > maxBytes) {
            throw IOException("Single entry exceeds max allowed size $maxBytes bytes")
        }
        buffer.write(temp, 0, read)
    }
    return buffer.toByteArray()
}

/**
 * 内部使用：带总大小上限的 ByteArrayOutputStream，避免异常数据撑爆内存。
 */
private class ByteArrayOutputStreamLimited(private val maxBytes: Long) : ByteArrayOutputStream() {
    override fun write(b: Int) {
        if (size() + 1 > maxBytes) throw IOException("Buffer limit exceeded")
        super.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (size() + len > maxBytes) throw IOException("Buffer limit exceeded")
        super.write(b, off, len)
    }
}
