package com.mangareader.parser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import com.mangareader.R
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mangareader.utils.BitmapDecoderUtils
import com.mangareader.utils.readBytesLimited
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * CBR 解析器：解析时打开 RAR 并保持 handle，避免每页都重新打开压缩包。
 * 仅按需读取和解码单页，不预加载全部数据。
 *
 * 由于 junrar 需要随机访问，content:// URI 会复制到缓存目录的临时文件，
 * 然后以 [Archive(File)] 打开，确保翻页时无需重复从源 URI 顺序读取。
 */
class CbrParser : ComicParser {
    private val stateLock = Any()
    private var appContext: Context? = null
    private var sourceUri: Uri? = null
    private var cachedArchive: Archive? = null
    private var entryHeaders: Map<String, FileHeader> = emptyMap()
    /** content:// 等无法随机访问的源对应的临时归档文件，关闭时清理。 */
    private var tempFile: File? = null

    companion object {
        private const val TAG = "CbrParser"
        /** 单条目最大 100MB，防止恶意或损坏归档中的超大文件导致 OOM。 */
        private const val MAX_ENTRY_BYTES = 100L * 1024 * 1024
    }

    override fun close() {
        synchronized(stateLock) {
            runCatching { cachedArchive?.close() }
            cachedArchive = null
            entryHeaders = emptyMap()
            appContext = null
            sourceUri = null
            tempFile?.let { runCatching { it.delete() } }
            tempFile = null
        }
    }

    override suspend fun parse(context: Context, uri: Uri): ParserResult = withContext(Dispatchers.IO) {
        try {
            synchronized(stateLock) {
                // 先关闭之前的资源
                close()

                val archive = openArchive(context, uri)
                val headers = archive.fileHeaders
                    .asSequence()
                    .filter { !it.isDirectory && ParserFactory.isImageFile(it.fileName) }
                    .toList()

                if (headers.isEmpty()) {
                    // 关闭归档并清理临时文件；直接调用 close() 可统一释放所有资源。
                    close()
                    return@withContext ParserResult.Error(context.getString(R.string.parser_cbr_no_images))
                }

                val sorted = headers.sortedWith { a, b -> FolderParser.compareNames(a.fileName, b.fileName) }
                val sortedNames = sorted.map { it.fileName }

                cachedArchive = archive
                appContext = context.applicationContext
                sourceUri = uri
                entryHeaders = sorted.associateBy { it.fileName }

                val (screenW, screenH) = BitmapDecoderUtils.getScreenSize(context)

                ParserResult.Success(
                    sortedNames.mapIndexed { index, name ->
                        ComicPage(
                            index = index,
                            name = name,
                            load = { decodePage(name, screenW, screenH) },
                            loadStream = { openEntry(name) }
                        )
                    },
                    ComicType.CBR
                )
            }
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            ParserResult.Error(context.getString(R.string.parser_cbr_error, e.message ?: context.getString(R.string.parser_unknown_error)))
        }
    }

    private suspend fun decodePage(name: String, screenW: Int, screenH: Int) = withContext(Dispatchers.IO) {
        val bytes = getEntryBytes(name) ?: return@withContext null
        BitmapDecoderUtils.decodeSampled(bytes, screenW, screenH, tag = TAG)
    }

    /** 直接从 RAR 归档读取单页原始字节，避免 decodePage 中 InputStream -> ByteArray 的二次拷贝。 */
    private fun getEntryBytes(name: String): ByteArray? {
        return try {
            synchronized(stateLock) {
                val archive = cachedArchive ?: return null
                val header = entryHeaders[name] ?: return null
                archive.getInputStream(header).use { it.readBytesLimited(MAX_ENTRY_BYTES) }
            }
        } catch (e: java.io.IOException) {
            Log.e(TAG, "读取 CBR 页面字节失败: 条目超过大小上限", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "读取 CBR 页面字节失败", e)
            null
        }
    }

    private fun openEntry(name: String): InputStream? {
        return try {
            synchronized(stateLock) {
                val archive = cachedArchive ?: return null
                val header = entryHeaders[name] ?: return null
                archive.getInputStream(header).use { it.readBytesLimited(MAX_ENTRY_BYTES) }
                    .let { ByteArrayInputStream(it) }
            }
        } catch (e: java.io.IOException) {
            Log.e(TAG, "读取 CBR 页面流失败: 条目超过大小上限", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "读取 CBR 页面流失败", e)
            null
        }
    }

    private fun openArchive(context: Context, uri: Uri): Archive {
        if (uri.scheme == "file") {
            uri.path?.let(::File)?.takeIf(File::exists)?.let { return Archive(it) }
        }

        // junrar 需要可随机访问的 RAR 文件。content:// 等 URI 先复制到临时文件，
        // 用 Archive(File) 打开以保证翻页效率；关闭时删除该临时文件。
        val temp = File(context.cacheDir, "cbr_temp_${System.currentTimeMillis()}.cbr")
        tempFile = temp
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Cannot open CBR stream")
            Archive(temp)
        } catch (e: Throwable) {
            // 复制或打开失败时立即删除临时文件，避免残留在缓存目录。
            runCatching { temp.delete() }
            tempFile = null
            throw e
        }
    }
}
