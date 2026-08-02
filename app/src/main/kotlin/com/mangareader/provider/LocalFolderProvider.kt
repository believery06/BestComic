package com.mangareader.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.mangareader.data.ComicProvider
import com.mangareader.parser.FolderParser
import com.mangareader.parser.ParserFactory
import com.mangareader.utils.FileListHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 本地图片文件夹的原生 [ComicProvider] 实现。
 *
 * 直接按页从文件夹中解码图片，是数据源统一抽象的最简示例。
 */
class LocalFolderProvider(
    private val context: Context,
    override val title: String,
    private val uri: Uri
) : ComicProvider {

    private val entries = mutableListOf<FileListHelper.DirEntry>()

    override val pageCount: Int get() = entries.size

    suspend fun scan(): Boolean = withContext(Dispatchers.IO) {
        entries.clear()
        val images = FileListHelper.listChildren(context, uri)
            .filter { !it.isDirectory && ParserFactory.isImageFile(it.name) }
            .sortedWith { a, b -> FolderParser.compareNames(a.name, b.name) }
        entries.addAll(images)
        entries.isNotEmpty()
    }

    override suspend fun getPage(index: Int): Bitmap? = withContext(Dispatchers.IO) {
        decodeSampledBitmap(index, DEFAULT_MAX_DIMENSION, DEFAULT_MAX_DIMENSION)
    }

    override suspend fun getPageStream(index: Int): InputStream? = withContext(Dispatchers.IO) {
        entries.getOrNull(index)?.uri?.let { context.contentResolver.openInputStream(it) }
    }

    override suspend fun getThumbnail(index: Int, maxDimension: Int): Bitmap? = withContext(Dispatchers.IO) {
        decodeSampledBitmap(index, maxDimension, maxDimension)
    }

    override fun getPageName(index: Int): String = entries.getOrNull(index)?.name ?: ""

    private fun decodeSampledBitmap(index: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        val entry = entries.getOrNull(index) ?: return null
        return try {
            context.contentResolver.openInputStream(entry.uri)?.use { input ->
                decodeSampled(input, reqWidth, reqHeight)
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载本地图片失败", e)
            null
        }
    }

    private fun decodeSampled(input: InputStream, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val bytes = input.use { it.readBytes() }
            if (bytes.isEmpty()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            Log.e(TAG, "采样解码失败", e)
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

    companion object {
        private const val TAG = "LocalFolderProvider"
        /** 默认按屏幕长边 1920px 采样，避免大图直接全分辨率加载导致 OOM。 */
        private const val DEFAULT_MAX_DIMENSION = 1920
    }
}
