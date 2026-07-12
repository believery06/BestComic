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
import java.io.FilterInputStream
import java.io.InputStream

/** CB7 parser that gives each page stream its own archive handle. */
class Cb7Parser : ComicParser {
    private val stateLock = Any()
    private var appContext: Context? = null
    private var sourceUri: Uri? = null

    override fun close() = synchronized(stateLock) {
        appContext = null
        sourceUri = null
    }

    override suspend fun parse(context: Context, uri: Uri): ParserResult = withContext(Dispatchers.IO) {
        try {
            val names = openArchive(context, uri).use { handle ->
                buildList {
                    var entry = handle.archive.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && ParserFactory.isImageFile(entry.name)) add(entry.name)
                        entry = handle.archive.nextEntry
                    }
                }
            }
            if (names.isEmpty()) return@withContext ParserResult.Error("CB7 文件中没有找到图片")
            val sorted = names.sortedWith(FolderParser::compareNames)
            synchronized(stateLock) {
                appContext = context.applicationContext
                sourceUri = uri
            }
            ParserResult.Success(sorted.mapIndexed { index, name ->
                ComicPage(index, name, load = { decodePage(name) }, loadStream = { openEntry(name) })
            }, ComicType.CB7)
        } catch (e: Exception) {
            close()
            ParserResult.Error("CB7 解析失败：${e.message ?: "未知错误"}")
        }
    }

    private suspend fun decodePage(name: String) = withContext(Dispatchers.IO) {
        openEntry(name)?.use(BitmapFactory::decodeStream)
    }

    private fun openEntry(name: String): InputStream? {
        val (context, uri) = synchronized(stateLock) {
            (appContext ?: return null) to (sourceUri ?: return null)
        }
        val handle = runCatching { openArchive(context, uri) }.getOrNull() ?: return null
        return try {
            var entry = handle.archive.nextEntry
            while (entry != null && entry.name != name) entry = handle.archive.nextEntry
            if (entry == null) {
                handle.close()
                null
            } else {
                object : FilterInputStream(handle.archive.getInputStream(entry)) {
                    override fun close() {
                        runCatching { super.close() }
                        handle.close()
                    }
                }
            }
        } catch (e: Exception) {
            handle.close()
            null
        }
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
