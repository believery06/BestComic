package com.mangareader.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import com.mangareader.R
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import com.mangareader.utils.FileManager
import com.mangareader.utils.BitmapDecoderUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * CBT (TAR) 解析器。
 *
 * TAR 本身不支持随机访问。本实现首次扫描 TAR 文件时建立「条目名 → 数据偏移/大小」索引
 * 并持久化到缓存目录；后续翻页通过 [RandomAccessFile] 直接 seek 到数据偏移读取单页。
 * content:// 等不可随机访问的源会先复制到缓存临时文件，关闭时清理。
 *
 * 该方案避免了解压全部图片到临时目录，符合「压缩档案不整体解压」的硬约束。
 */
class CbtParser : ComicParser {

    companion object {
        private const val TAG = "CbtParser"
        private const val TAR_BLOCK_SIZE = 512L
        /** 单页最大 100MB，防止 TAR 中大文件导致内存溢出。 */
        private const val MAX_ENTRY_BYTES = 100L * 1024 * 1024
        /** 索引条目数上限，防止恶意/损坏 TAR 导致长时间扫描或内存溢出。 */
        private const val MAX_INDEX_ENTRIES = 100_000
    }

    private val stateLock = Any()
    private var archiveFile: File? = null
    /** content:// 等源对应的临时归档文件，关闭时清理。 */
    private var tempArchiveFile: File? = null
    private var randomAccessFile: RandomAccessFile? = null
    private var entryIndex: Map<String, TarEntryInfo> = emptyMap()
    private var sortedNames: List<String> = emptyList()

    @Serializable
    private data class TarEntryInfo(
        val name: String,
        val dataOffset: Long,
        val size: Long
    )

    override fun close() {
        synchronized(stateLock) {
            runCatching { randomAccessFile?.close() }
            randomAccessFile = null
            entryIndex = emptyMap()
            sortedNames = emptyList()
            archiveFile = null
            tempArchiveFile?.let { runCatching { it.delete() } }
            tempArchiveFile = null
        }
    }

    override suspend fun parse(context: Context, uri: Uri): ParserResult = withContext(Dispatchers.IO) {
        try {
            close()

            val (archive, temp) = resolveArchiveFile(context, uri)
            val indexFile = File(context.cacheDir, "cbt_index_${safeIndexKey(archive.absolutePath)}.json")
            val index = loadOrBuildIndex(archive, indexFile)
            val imageEntries = index.values
                .filter { ParserFactory.isImageFile(it.name) }
                .sortedWith { a, b -> FolderParser.compareNames(a.name, b.name) }

            if (imageEntries.isEmpty()) {
                // 清理已创建的临时归档文件（若存在），再返回错误。
                runCatching { temp?.delete() }
                close()
                return@withContext ParserResult.Error(context.getString(R.string.parser_cbt_no_images))
            }

            val raf = RandomAccessFile(archive, "r")
            val (screenW, screenH) = BitmapDecoderUtils.getScreenSize(context)

            synchronized(stateLock) {
                archiveFile = archive
                tempArchiveFile = temp
                randomAccessFile = raf
                entryIndex = imageEntries.associateBy { it.name }
                sortedNames = imageEntries.map { it.name }
            }

            ParserResult.Success(
                imageEntries.mapIndexed { idx, info ->
                    ComicPage(
                        index = idx,
                        name = info.name,
                        load = { decodePage(info, screenW, screenH) },
                        loadStream = { openEntry(info) }
                    )
                },
                ComicType.CBT
            )
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "CBT 解析失败", e)
            close()
            ParserResult.Error(context.getString(R.string.parser_cbt_error, e.message ?: context.getString(R.string.parser_unknown_error)))
        }
    }

    private fun resolveArchiveFile(context: Context, uri: Uri): Pair<File, File?> {
        if (uri.scheme == "file") {
            // 使用 FileManager 规范化路径，消除符号链接与 .. 带来的不一致性。
            val file = FileManager.toSafeFile(uri)?.takeIf { it.exists() }
            if (file != null) return file to null
        }
        val temp = File(context.cacheDir, "cbt_temp_${System.currentTimeMillis()}.cbt")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException(context.getString(R.string.parser_cbt_open_failed))
        return temp to temp
    }

    private fun loadOrBuildIndex(archive: File, indexFile: File): Map<String, TarEntryInfo> {
        if (indexFile.exists() && indexFile.lastModified() >= archive.lastModified()) {
            try {
                val json = indexFile.readText()
                val entries = Json.decodeFromString<List<TarEntryInfo>>(json)
                return entries.associateBy { it.name }
            } catch (e: Exception) {
                Log.w(TAG, "CBT 索引加载失败，将重建", e)
            }
        }
        val entries = buildIndex(archive)
        try {
            indexFile.writeText(Json.encodeToString(entries))
        } catch (e: Exception) {
            Log.w(TAG, "CBT 索引保存失败", e)
        }
        return entries.associateBy { it.name }
    }

    private fun buildIndex(archive: File): List<TarEntryInfo> {
        val entries = mutableListOf<TarEntryInfo>()
        FileInputStream(archive).use { fis ->
            val counter = CountingInputStream(fis)
            TarArchiveInputStream(counter).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (entries.size >= MAX_INDEX_ENTRIES) {
                        throw IllegalStateException("TAR 条目数超过上限 $MAX_INDEX_ENTRIES，可能是损坏或恶意归档")
                    }
                    val dataOffset = counter.count
                    if (!entry.isDirectory) {
                        entries.add(TarEntryInfo(entry.name, dataOffset, entry.size))
                    }
                    // 跳过内容及其后的填充块，使下一次 nextEntry 位于下一个条目标头。
                    val padding = (TAR_BLOCK_SIZE - (entry.size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE
                    val remaining = entry.size + padding
                    var skipped = 0L
                    while (skipped < remaining) {
                        val s = tar.skip(remaining - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                    // 若因流异常未能正确跳过，则跳出避免索引混乱。
                    if (skipped < remaining && entry.size > 0) {
                        Log.w(TAG, "CBT 索引构建时跳过条目 ${entry.name} 不完整")
                        break
                    }
                }
            }
        }
        return entries
    }

    private suspend fun decodePage(info: TarEntryInfo, screenW: Int, screenH: Int) = withContext(Dispatchers.IO) {
        val bytes = readEntryBytes(info) ?: return@withContext null
        BitmapDecoderUtils.decodeSampled(bytes, screenW, screenH, tag = TAG)
    }

    /**
     * 生成更稳定的索引文件名 key，降低单纯 absolutePath.hashCode() 的碰撞概率。
     */
    private fun safeIndexKey(absolutePath: String): String {
        val suffix = absolutePath.takeLast(64).hashCode()
        return "${absolutePath.hashCode()}_${suffix}"
    }

    private fun openEntry(info: TarEntryInfo): InputStream? {
        val bytes = readEntryBytes(info) ?: return null
        return bytes.inputStream()
    }

    private fun readEntryBytes(info: TarEntryInfo): ByteArray? {
        return synchronized(stateLock) {
            val raf = randomAccessFile ?: return null
            runCatching {
                if (info.size <= 0) return@runCatching ByteArray(0)
                if (info.size > MAX_ENTRY_BYTES) {
                    Log.w(TAG, "TAR 条目过大，跳过: ${info.size} bytes")
                    return@runCatching null
                }
                val size = info.size.toInt()
                val bytes = ByteArray(size)
                raf.seek(info.dataOffset)
                raf.readFully(bytes)
                bytes
            }.getOrNull()
        }
    }

    /**
     * 统计已读取字节数的 [InputStream] 包装，用于建立 TAR 索引时精确定位条目偏移。
     */
    private class CountingInputStream(private val source: InputStream) : InputStream() {
        var count: Long = 0L
            private set

        override fun read(): Int {
            val b = source.read()
            if (b != -1) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = source.read(b, off, len)
            if (read > 0) count += read
            return read
        }

        override fun skip(n: Long): Long {
            val skipped = source.skip(n)
            if (skipped > 0) count += skipped
            return skipped
        }
    }
}
