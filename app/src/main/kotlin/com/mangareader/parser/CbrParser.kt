package com.mangareader.parser

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * CBR 解析器：解析时打开 RAR 并保持 handle，避免每页都重新打开压缩包。
 * 仅按需读取和解码单页，不预加载全部数据。
 */
class CbrParser : ComicParser {
    private val stateLock = Any()
    private var appContext: Context? = null
    private var sourceUri: Uri? = null
    private var cachedArchive: Archive? = null
    private var entryHeaders: Map<String, FileHeader> = emptyMap()

    override fun close() {
        synchronized(stateLock) {
            runCatching { cachedArchive?.close() }
            cachedArchive = null
            entryHeaders = emptyMap()
            appContext = null
            sourceUri = null
        }
    }

    override suspend fun parse(context: Context, uri: Uri): ParserResult = withContext(Dispatchers.IO) {
        try {
            // 先关闭之前的资源
            close()

            val archive = openArchive(context, uri)
            val headers = archive.fileHeaders
                .asSequence()
                .filter { !it.isDirectory && ParserFactory.isImageFile(it.fileName) }
                .toList()

            if (headers.isEmpty()) {
                runCatching { archive.close() }
                return@withContext ParserResult.Error("CBR 文件中没有找到图片")
            }

            val sorted = headers.sortedWith { a, b -> FolderParser.compareNames(a.fileName, b.fileName) }
            val sortedNames = sorted.map { it.fileName }

            synchronized(stateLock) {
                cachedArchive = archive
                appContext = context.applicationContext
                sourceUri = uri
                entryHeaders = sorted.associateBy { it.fileName }
            }

            ParserResult.Success(
                sortedNames.mapIndexed { index, name ->
                    ComicPage(
                        index = index,
                        name = name,
                        load = { decodePage(name) },
                        loadStream = { openEntry(name) }
                    )
                },
                ComicType.CBR
            )
        } catch (e: Exception) {
            close()
            ParserResult.Error("CBR 解析失败：${e.message ?: "未知错误"}")
        }
    }

    private suspend fun decodePage(name: String) = withContext(Dispatchers.IO) {
        openEntry(name)?.use(BitmapFactory::decodeStream)
    }

    private fun openEntry(name: String): InputStream? {
        val archive = synchronized(stateLock) { cachedArchive } ?: return null
        val header = synchronized(stateLock) { entryHeaders[name] } ?: return null
        // 返回 stream 时不关闭 archive，由 close() 统一管理
        return runCatching { archive.getInputStream(header) }.getOrNull()
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
