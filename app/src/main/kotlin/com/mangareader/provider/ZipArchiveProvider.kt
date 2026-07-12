package com.mangareader.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mangareader.parser.FolderParser
import com.mangareader.parser.ParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * ZIP/CBZ 压缩包的原生 [ComicProvider] 实现。
 *
 * 解析阶段只读取压缩包目录索引；阅读时通过 [ZipFile.getEntry] 按页随机读取，
 * 不解压全量文件到磁盘，也不把整包图片字节载入内存。
 */
class ZipArchiveProvider(
    private val context: Context,
    override val title: String,
    private val uri: Uri
) : ComicProvider {

    private var zipFile: ZipFile? = null
    private var tempFile: File? = null
    private val entryNames = mutableListOf<String>()

    override val pageCount: Int get() = entryNames.size

    suspend fun open(): Boolean = withContext(Dispatchers.IO) {
        try {
            close()
            val tmp = copyUriToTemp(uri)
            tempFile = tmp
            val zf = ZipFile(tmp)
            zipFile = zf
            entryNames.clear()
            entryNames.addAll(
                zf.entries().toList()
                    .filter { !it.isDirectory && ParserFactory.isImageFile(it.name) }
                    .map { it.name }
                    .sortedWith { a, b -> FolderParser.compareNames(a, b) }
            )
            entryNames.isNotEmpty()
        } catch (e: Exception) {
            e.printStackTrace()
            close()
            false
        }
    }

    override suspend fun getPage(index: Int): Bitmap? = withContext(Dispatchers.IO) {
        decodePage(index)
    }

    override suspend fun getThumbnail(index: Int, maxDimension: Int): Bitmap? = withContext(Dispatchers.IO) {
        val raw = decodePage(index) ?: return@withContext null
        if (raw.isRecycled) return@withContext null
        val scale = (maxDimension.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
        val w = (raw.width * scale).toInt().coerceAtLeast(1)
        val h = (raw.height * scale).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(raw, w, h, true)
    }

    override fun getPageName(index: Int): String = entryNames.getOrNull(index) ?: ""

    override fun close() {
        runCatching { zipFile?.close() }
        zipFile = null
        runCatching { tempFile?.delete() }
        tempFile = null
        entryNames.clear()
    }

    private fun decodePage(index: Int): Bitmap? {
        val name = entryNames.getOrNull(index) ?: return null
        val zf = zipFile ?: return null
        return try {
            val entry = zf.getEntry(name) ?: return null
            zf.getInputStream(entry).use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun copyUriToTemp(uri: Uri): File {
        val temp = File.createTempFile("zip_prov_", ".zip", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output, 131072)
            }
        } ?: throw IllegalStateException("Cannot open zip input stream")
        return temp
    }
}
