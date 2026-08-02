package com.mangareader.parser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.mangareader.R
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import com.mangareader.utils.BitmapDecoderUtils
import com.mangareader.utils.readBytesLimited
import java.io.ByteArrayInputStream
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

    companion object {
        private const val TAG = "CbzParser"
        /** 单条目最大 100MB，防止恶意或损坏归档中的超大文件导致 OOM。 */
        private const val MAX_ENTRY_BYTES = 100L * 1024 * 1024
    }

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
            synchronized(zipLock) {
                close()

                openZipFile(context, uri)
                val zf = zipFile ?: throw IllegalStateException("ZipFile not opened")

                val imageEntries = zf.entries.toList()
                    .filter { !it.isDirectory && ParserFactory.isImageFile(it.name) }
                    .mapNotNull { entry ->
                        normalizeArchiveEntryName(entry.name)?.let { it to entry }
                    }

                if (imageEntries.isEmpty()) {
                    close()
                    return@withContext ParserResult.Error(context.getString(R.string.parser_cbz_no_images))
                }

                // 使用规范化后的名称作为键，建立到原始 ZipEntry 的映射。
                entries.clear()
                imageEntries.forEach { (normalized, entry) ->
                    entries[normalized] = entry
                }

                val imageNames = imageEntries.map { it.first }
                    .sortedWith { a, b -> FolderParser.compareNames(a, b) }

                val (screenW, screenH) = BitmapDecoderUtils.getScreenSize(context)

                val pages = imageNames.mapIndexed { idx, name ->
                    ComicPage(
                        index = idx,
                        name = name,
                        load = { decodePage(name, screenW, screenH) },
                        loadStream = { getPageInputStream(name) }
                    )
                }
                ParserResult.Success(pages, ComicType.CBZ)
            }
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            Log.e(TAG, "CBZ 解析失败", e)
            ParserResult.Error(context.getString(R.string.parser_cbz_error, e.message ?: context.getString(R.string.parser_unknown_error)))
        }
    }

    private suspend fun decodePage(entryName: String, screenW: Int, screenH: Int): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            val bytes = getPageBytes(entryName) ?: return@withContext null
            BitmapDecoderUtils.decodeSampled(bytes, screenW, screenH, tag = TAG)
        }

    /** 直接从压缩包读取单页原始字节，避免 decodePage 中 InputStream -> ByteArray 的二次拷贝。 */
    private fun getPageBytes(entryName: String): ByteArray? {
        val normalized = normalizeArchiveEntryName(entryName) ?: return null
        return try {
            synchronized(zipLock) {
                val zf = zipFile ?: return null
                val entry = entries[normalized] ?: zf.getEntry(normalized) ?: return null
                zf.getInputStream(entry)?.use { it.readBytesLimited(MAX_ENTRY_BYTES) }
            }
        } catch (e: java.io.IOException) {
            Log.e(TAG, "读取 CBZ 页面字节失败: 条目超过大小上限", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "读取 CBZ 页面字节失败", e)
            null
        }
    }

    private fun getPageInputStream(entryName: String): InputStream? {
        val normalized = normalizeArchiveEntryName(entryName) ?: return null
        return try {
            synchronized(zipLock) {
                val zf = zipFile ?: return null
                val entry = entries[normalized] ?: zf.getEntry(normalized) ?: return null
                zf.getInputStream(entry)?.use { it.readBytesLimited(MAX_ENTRY_BYTES) }
                    ?.let { ByteArrayInputStream(it) }
            }
        } catch (e: java.io.IOException) {
            Log.e(TAG, "读取 CBZ 页面流失败: 条目超过大小上限", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "读取 CBZ 页面流失败", e)
            null
        }
    }

    private fun openZipFile(context: Context, uri: Uri) {
        synchronized(zipLock) {
            if (uri.scheme == "file") {
                val path = uri.path?.let { File(it) }
                if (path != null && path.exists()) {
                    zipFile = ZipFile.Builder().setFile(path).get()
                    return
                }
            }

            val openedPfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Cannot open file descriptor")
            var openedFis: FileInputStream? = null
            try {
                openedFis = FileInputStream(openedPfd.fileDescriptor)
                val openedZip = ZipFile.Builder().setSeekableByteChannel(openedFis.channel).get()
                pfd = openedPfd
                fis = openedFis
                zipFile = openedZip
            } catch (e: Exception) {
                runCatching { openedFis?.close() }
                runCatching { openedPfd.close() }
                throw e
            }
        }
    }
}
