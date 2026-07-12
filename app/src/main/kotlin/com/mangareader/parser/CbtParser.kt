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
import java.io.InputStream

/** CBT parser that reopens the TAR and scans to one entry on demand. */
class CbtParser : ComicParser {
    private val stateLock = Any()
    private var appContext: Context? = null
    private var sourceUri: Uri? = null

    override fun close() {
        synchronized(stateLock) {
            appContext = null
            sourceUri = null
        }
    }

    override suspend fun parse(context: Context, uri: Uri): ParserResult = withContext(Dispatchers.IO) {
        try {
            val names: List<String> = context.contentResolver.openInputStream(uri)?.use { stream ->
                TarArchiveInputStream(BufferedInputStream(stream)).use { tar ->
                    val result = mutableListOf<String>()
                        var entry = tar.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && ParserFactory.isImageFile(entry.name)) result.add(entry.name)
                            entry = tar.nextEntry
                        }
                    result
                }
            } ?: return@withContext ParserResult.Error("无法打开 CBT 文件")

            if (names.isEmpty()) return@withContext ParserResult.Error("CBT 文件中没有找到图片")
            val sorted = names.sortedWith(FolderParser::compareNames)
            synchronized(stateLock) {
                appContext = context.applicationContext
                sourceUri = uri
            }
            val pages = sorted.mapIndexed { index, name ->
                ComicPage(index, name, load = { decodePage(name) }, loadStream = { openEntry(name) })
            }
            ParserResult.Success(pages, ComicType.CBT)
        } catch (e: Exception) {
            close()
            ParserResult.Error("CBT 解析失败：${e.message ?: "未知错误"}")
        }
    }

    private suspend fun decodePage(name: String) = withContext(Dispatchers.IO) {
        openEntry(name)?.use(BitmapFactory::decodeStream)
    }

    private fun openEntry(name: String): InputStream? {
        val (context, uri) = synchronized(stateLock) {
            (appContext ?: return null) to (sourceUri ?: return null)
        }
        val source = context.contentResolver.openInputStream(uri) ?: return null
        val tar = TarArchiveInputStream(BufferedInputStream(source))
        return try {
            var entry = tar.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name == name) return tar
                entry = tar.nextEntry
            }
            tar.close()
            null
        } catch (e: Exception) {
            runCatching { tar.close() }
            null
        }
    }
}
