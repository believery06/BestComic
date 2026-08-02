package com.mangareader.parser

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.mangareader.R
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mangareader.utils.BitmapDecoderUtils
import com.mangareader.utils.readBytesLimited
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * CB7 解析器：基于 7z 的随机访问能力直接读取单条目，不整体解压。
 * 对 content:// URI 使用 ParcelFileDescriptor 保持可寻址通道打开，按页读取。
 */
class Cb7Parser : ComicParser {
    private val stateLock = Any()
    private var handle: ArchiveHandle? = null
    private var entries: List<PageEntry> = emptyList()

    companion object {
        /** 单条目最大 100MB，防止恶意或损坏归档中的超大文件导致 OOM。 */
        private const val MAX_ENTRY_BYTES = 100L * 1024 * 1024
    }

    private data class PageEntry(
        val index: Int,
        val sevenZEntry: SevenZArchiveEntry
    )

    override fun close() {
        synchronized(stateLock) {
            runCatching { handle?.close() }
            handle = null
            entries = emptyList()
        }
    }

    override suspend fun parse(context: Context, uri: Uri): ParserResult = withContext(Dispatchers.IO) {
        try {
            close()

            val newHandle = openArchive(context, uri)
            val imageEntries = mutableListOf<SevenZArchiveEntry>()
            newHandle.archive.entries?.forEach { entry ->
                if (!entry.isDirectory && ParserFactory.isImageFile(entry.name)) {
                    imageEntries.add(entry)
                }
            }

            if (imageEntries.isEmpty()) {
                newHandle.close()
                return@withContext ParserResult.Error(context.getString(R.string.parser_cb7_no_images))
            }

            val sortedEntries = imageEntries
                .sortedWith { a, b -> FolderParser.compareNames(a.name, b.name) }
                .mapIndexed { idx, entry -> PageEntry(idx, entry) }

            // 根据屏幕分辨率计算下采样目标尺寸，避免大图直接原尺寸解码导致 OOM。
            val (reqWidth, reqHeight) = BitmapDecoderUtils.getScreenSize(context)

            synchronized(stateLock) {
                handle = newHandle
                entries = sortedEntries
            }

            ParserResult.Success(
                sortedEntries.map { pageEntry ->
                    ComicPage(
                        index = pageEntry.index,
                        name = pageEntry.sevenZEntry.name,
                        load = { decodePage(pageEntry.sevenZEntry, reqWidth, reqHeight) },
                        loadStream = { openEntry(pageEntry.sevenZEntry) }
                    )
                },
                ComicType.CB7
            )
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            ParserResult.Error(context.getString(R.string.parser_cb7_error, e.message ?: context.getString(R.string.parser_unknown_error)))
        }
    }

    private suspend fun decodePage(
        entry: SevenZArchiveEntry,
        reqWidth: Int,
        reqHeight: Int
    ): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        val bytes = getEntryBytes(entry) ?: return@withContext null
        BitmapDecoderUtils.decodeSampled(bytes, reqWidth, reqHeight, tag = "Cb7Parser")
    }

    private fun openEntry(entry: SevenZArchiveEntry): InputStream? {
        return getEntryBytes(entry)?.let { ByteArrayInputStream(it) }
    }

    /** 直接从 7z 归档读取单页原始字节，供 decodePage 与 loadStream 复用，避免二次拷贝。 */
    private fun getEntryBytes(entry: SevenZArchiveEntry): ByteArray? {
        return synchronized(stateLock) {
            val h = handle ?: return@synchronized null
            runCatching {
                h.archive.getInputStream(entry)?.use { it.readBytesLimited(MAX_ENTRY_BYTES) }
            }.getOrNull()
        }
    }

    private fun openArchive(context: Context, uri: Uri): ArchiveHandle {
        if (uri.scheme == "file") {
            uri.path?.let(::File)?.takeIf(File::exists)?.let {
                val szf = SevenZFile.Builder().setFile(it).get()
                return ArchiveHandle(szf, null, null)
            }
        }
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Cannot open file descriptor")
        var input: FileInputStream? = null
        return try {
            input = FileInputStream(pfd.fileDescriptor)
            val szf = SevenZFile.Builder().setSeekableByteChannel(input.channel).get()
            ArchiveHandle(szf, input, pfd)
        } catch (e: Exception) {
            runCatching { input?.close() }
            runCatching { pfd.close() }
            throw e
        }
    }

    private class ArchiveHandle(
        val archive: SevenZFile,
        private val input: FileInputStream? = null,
        private val pfd: ParcelFileDescriptor? = null
    ) : AutoCloseable {
        override fun close() {
            runCatching { archive.close() }
            runCatching { input?.close() }
            runCatching { pfd?.close() }
        }
    }
}
