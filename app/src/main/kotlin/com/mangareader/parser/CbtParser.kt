package com.mangareader.parser

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * CBT (TAR) 解析器：解析时将所有图片提取到临时文件，后续按页随机读取。
 * TAR 不支持随机访问，避免每页都重新打开并全量扫描，大幅提升翻页性能。
 */
class CbtParser : ComicParser {
    private val stateLock = Any()
    private var entryFiles: List<File> = emptyList()
    private var tempDir: File? = null

    override fun close() {
        synchronized(stateLock) {
            entryFiles.forEach { runCatching { it.delete() } }
            entryFiles = emptyList()
            tempDir?.let { runCatching { it.deleteRecursively() } }
            tempDir = null
        }
    }

    override suspend fun parse(context: Context, uri: Uri): ParserResult = withContext(Dispatchers.IO) {
        try {
            close()

            val dir = File(context.cacheDir, "cbt_${uri.hashCode()}_${System.currentTimeMillis()}").apply { mkdirs() }
            tempDir = dir

            val extracted = mutableListOf<File>()
            context.contentResolver.openInputStream(uri)?.use { stream ->
                TarArchiveInputStream(BufferedInputStream(stream)).use { tar ->
                    var entry = tar.nextEntry
                    var index = 0
                    while (entry != null) {
                        if (!entry.isDirectory && ParserFactory.isImageFile(entry.name)) {
                            val safeName = "img_${index}_${entry.name.replace("/", "_").replace("\\", "_")}"
                            val outFile = File(dir, safeName)
                            FileOutputStream(outFile).use { output ->
                                tar.copyTo(output, 131072)
                            }
                            if (outFile.length() > 0L) {
                                extracted.add(outFile)
                                index++
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            } ?: return@withContext ParserResult.Error("无法打开 CBT 文件")

            if (extracted.isEmpty()) {
                close()
                return@withContext ParserResult.Error("CBT 文件中没有找到图片")
            }

            // 按原始文件名排序
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
                ComicType.CBT
            )
        } catch (e: Exception) {
            close()
            ParserResult.Error("CBT 解析失败：${e.message ?: "未知错误"}")
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
}
