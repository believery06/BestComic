package com.mangareader.parser

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * CBZ/ZIP 解析器。解析时只读取文件名列表，不加载图片数据；
 * 每一页通过 [ZipFile] 按需读取原始字节流，由上层按屏幕尺寸采样解码，
 * 避免一次性把整个压缩包装进内存，也避免全分辨率解码。
 */
class CbzParser : ComicParser {

    private var zipFile: ZipFile? = null
    private var pfd: ParcelFileDescriptor? = null
    private var fis: FileInputStream? = null
    private val entries = mutableMapOf<String, ZipArchiveEntry>()
    private val zipLock = Any()

    override fun close() {
        synchronized(zipLock) {
            entries.clear()
            runCatching { zipFile?.close() }
            zipFile = null
            runCatching { fis?.close() }
            fis = null
            runCatching { pfd?.close() }
            pfd = null
        }
    }

    override suspend fun parse(context: Context, uri: Uri): ParserResult = withContext(Dispatchers.IO) {
        try {
            close()

            openZipFile(context, uri)
            val zf = zipFile ?: throw IllegalStateException("ZipFile not opened")

            val imageNames = zf.entries.toList()
                .filter { !it.isDirectory && ParserFactory.isImageFile(it.name) }
                .map { it.name }
                .sortedWith { a, b -> FolderParser.compareNames(a, b) }

            if (imageNames.isEmpty()) {
                close()
                return@withContext ParserResult.Error("CBZ 文件中没有找到图片")
            }

            imageNames.forEach { name ->
                zf.getEntry(name)?.let { entries[name] = it }
            }

            val pages = imageNames.mapIndexed { idx, name ->
                ComicPage(
                    index = idx,
                    name = name,
                    load = { decodePage(name) },
                    loadStream = { getPageInputStream(name) }
                )
            }
            ParserResult.Success(pages, ComicType.CBZ)
        } catch (e: Exception) {
            close()
            e.printStackTrace()
            ParserResult.Error("CBZ 解析失败：${e.message ?: "未知错误"}")
        }
    }

    private suspend fun decodePage(entryName: String): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            getPageInputStream(entryName)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        }

    private fun getPageInputStream(entryName: String): InputStream? {
        val zf = zipFile ?: return null
        val entry = entries[entryName] ?: zf.getEntry(entryName) ?: return null
        return try {
            synchronized(zipLock) {
                zf.getInputStream(entry)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun openZipFile(context: Context, uri: Uri) {
        synchronized(zipLock) {
            if (uri.scheme == "file") {
                val path = uri.path?.let { File(it) }
                if (path != null && path.exists()) {
                    zipFile = ZipFile(path)
                    return
                }
            }

            val openedPfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Cannot open file descriptor")
            try {
                val openedFis = FileInputStream(openedPfd.fileDescriptor)
                val openedZip = ZipFile(openedFis.channel)
                pfd = openedPfd
                fis = openedFis
                zipFile = openedZip
            } catch (e: Exception) {
                runCatching { openedPfd.close() }
                throw e
            }
        }
    }
}
