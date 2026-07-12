package com.mangareader.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mangareader.parser.FolderParser
import com.mangareader.parser.ParserFactory
import com.mangareader.utils.FileListHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        decodeBitmap(index)
    }

    override suspend fun getThumbnail(index: Int, maxDimension: Int): Bitmap? = withContext(Dispatchers.IO) {
        val raw = decodeBitmap(index) ?: return@withContext null
        if (raw.isRecycled) return@withContext null
        val scale = (maxDimension.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
        val w = (raw.width * scale).toInt().coerceAtLeast(1)
        val h = (raw.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(raw, w, h, true)
        if (scaled !== raw && !raw.isRecycled) {
            runCatching { raw.recycle() }
        }
        scaled
    }

    override fun getPageName(index: Int): String = entries.getOrNull(index)?.name ?: ""

    private fun decodeBitmap(index: Int): Bitmap? {
        val entry = entries.getOrNull(index) ?: return null
        return try {
            context.contentResolver.openInputStream(entry.uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
