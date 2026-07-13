package com.mangareader.parser

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * CB7 解析器：解析时将所有图片提取到临时文件，后续按页随机读取。
 * 避免每页都重新打开 7z 并全量扫描，大幅提升翻页性能。
 */
class Cb7Parser : ComicParser {
    private val stateLock = Any()
    private var entryFiles: List<File> = emptyList()
    private var tempDir: File? = null

    override fun close() {
        synchronized(stateLock) {
            // 清理临时文件
            entryFiles.forEach { runCatching { it.delete() } }
            entryFiles = emptyList()
            tempDir?.let { runCatching { it.deleteRecursively() } }
            tempDir = null
        }
    }

    override suspend fun parse(context: Context, uri: Uri): ParserResult = withContext(Dispatchers.IO) {
        try {
            close()

            val dir = File(context.cacheDir, "cb7_${uri.hashCode()}_${System.currentTimeMillis()}").apply { mkdirs() }
            tempDir = dir

            val extracted = mutableListOf<File>()
            val handle = openArchive(context, uri)
            try {
                var entry = handle.archive.nextEntry
                var index = 0
                while (entry != null) {
                    if (!entry.isDirectory && ParserFactory.isImageFile(entry.name)) {
                        val safeName = "img_${index}_${entry.name.replace("/", "_").replace("\\", "_")}"
                        val outFile = File(dir, safeName)
                        handle.archive.getInputStream(entry).use { input ->
                            FileOutputStream(outFile).use { output ->
                                input.copyTo(output, 131072)
                            }
                        }
                        if (outFile.length() > 0L) {
                            extracted.add(outFile)
                            index++
                        }
                    }
                    entry = handle.archive.nextEntry
                }
            } finally {
                handle.close()
            }

            if (extracted.isEmpty()) {
                close()
                return@withContext ParserResult.Error("CB7 文件中没有找到图片")
            }

            // 按原始文件名排序：提取文件名中的数字部分用于自然排序
            val sorted = extracted.sortedWith { a, b ->
                val nameA = a.name.removePrefix("img_").substringAfter("_")
                val nameB = b.name.removePrefix("img_").substringAfter("_")
                FolderParser.compareNames(nameA, nameB)
            }

            entryFiles = sorted

            ParserResult.Success(
                sorted.mapIndexed { idx, file ->
                    ComicPage(
                        index = idx,
                        name = file.name,
                        load = { decodePage(file) },
                        loadStream = { openEntry(file) }
                    )
                },
                ComicType.CB7
            )
        } catch (e: Exception) {
            close()
            ParserResult.Error("CB7 解析失败：${e.message ?: "未知错误"}")
        }
    }

    private suspend fun decodePage(file: File) = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists() || file.length() == 0L) return@runCatching null
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()
    }

    private fun openEntry(file: File): InputStream? {
        return runCatching {
            if (!file.exists() || file.length() == 0L) return null
            FileInputStream(file)
        }.getOrNull()
    }

    private fun openArchive(context: Context, uri: Uri): ArchiveHandle {
        if (uri.scheme == "file") {
            uri.path?.let(::File)?.takeIf(File::exists)?.let { return ArchiveHandle(SevenZFile(it)) }
        }
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Cannot open file descriptor")
        return try {
            val input = FileInputStream(pfd.fileDescriptor)
            ArchiveHandle(SevenZFile(input.channel), input, pfd)
        } catch (e: Exception) {
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
