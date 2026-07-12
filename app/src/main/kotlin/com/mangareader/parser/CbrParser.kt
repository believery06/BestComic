package com.mangareader.parser

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.github.junrar.Archive
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/** CBR parser using junrar's stream API; no procfs path or page byte array is needed. */
class CbrParser : ComicParser {
    private val stateLock = Any()
    private var appContext: Context? = null
    private var sourceUri: Uri? = null

    override fun close() = synchronized(stateLock) {
        appContext = null
        sourceUri = null
    }

    override suspend fun parse(context: Context, uri: Uri): ParserResult = withContext(Dispatchers.IO) {
        try {
            val names = openArchive(context, uri).use { archive ->
                archive.fileHeaders.asSequence()
                    .filter { !it.isDirectory && ParserFactory.isImageFile(it.fileName) }
                    .map { it.fileName }
                    .toList()
            }
            if (names.isEmpty()) return@withContext ParserResult.Error("CBR 文件中没有找到图片")
            val sorted = names.sortedWith(FolderParser::compareNames)
            synchronized(stateLock) {
                appContext = context.applicationContext
                sourceUri = uri
            }
            ParserResult.Success(sorted.mapIndexed { index, name ->
                ComicPage(index, name, load = { decodePage(name) }, loadStream = { openEntry(name) })
            }, ComicType.CBR)
        } catch (e: Exception) {
            close()
            ParserResult.Error("CBR 解析失败：${e.message ?: "未知错误"}")
        }
    }

    private suspend fun decodePage(name: String) = withContext(Dispatchers.IO) {
        openEntry(name)?.use(BitmapFactory::decodeStream)
    }

    private fun openEntry(name: String): InputStream? {
        val (context, uri) = synchronized(stateLock) {
            (appContext ?: return null) to (sourceUri ?: return null)
        }
        val archive = runCatching { openArchive(context, uri) }.getOrNull() ?: return null
        return try {
            val header = archive.fileHeaders.firstOrNull { it.fileName == name }
            if (header == null) {
                archive.close()
                null
            } else {
                object : FilterInputStream(archive.getInputStream(header)) {
                    override fun close() {
                        runCatching { super.close() }
                        runCatching { archive.close() }
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { archive.close() }
            null
        }
    }

    private fun openArchive(context: Context, uri: Uri): Archive {
        if (uri.scheme == "file") {
            uri.path?.let(::File)?.takeIf(File::exists)?.let { return Archive(it) }
        }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open CBR stream")
        return try {
            Archive(input)
        } catch (e: Exception) {
            runCatching { input.close() }
            throw e
        }
    }
}
