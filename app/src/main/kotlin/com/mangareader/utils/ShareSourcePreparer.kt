package com.mangareader.utils

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import com.mangareader.data.ComicEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 统一准备漫画源文件分享的协作者。
 *
 * 负责将 file:// / content:// 来源复制到应用缓存的 `source_share` 目录，
 * 净化文件名、检查大小上限、清理旧缓存，并返回可通过 FileProvider 分享的 URI。
 * 阅读器与书架的源文件分享均通过此类实现，避免逻辑重复。
 */
class ShareSourcePreparer(private val app: Application) {

    data class SourceItem(
        val uri: Uri,
        val name: String,
        val isDirectory: Boolean = false
    )

    companion object {
        /** 允许分享的最大源文件大小：100 MB。 */
        const val MAX_SHARE_SOURCE_SIZE = 100L * 1024 * 1024
        private const val TAG = "ShareSourcePreparer"
        private const val SHARE_DIR = "source_share"
        private const val BUFFER_SIZE = 256 * 1024
    }

    /**
     * 准备单个分享源文件，返回缓存目录中的 [File]；失败或超限时返回 null。
     */
    suspend fun prepareSingle(uri: Uri, name: String? = null): File? = withContext(Dispatchers.IO) {
        prepareInternal(uri, name ?: uri.lastPathSegment ?: "comic")
    }

    /**
     * 批量准备多个漫画条目的源文件，返回可分享的 URI 列表。
     * 目录类型条目会自动跳过。
     */
    suspend fun prepareBatch(entries: List<ComicEntry>): List<Uri> = withContext(Dispatchers.IO) {
        val outDir = ensureCleanOutDir()
        val result = mutableListOf<Uri>()
        var index = 0
        for (entry in entries) {
            if (entry.isDirectory) {
                Log.w(TAG, "目录类型条目不支持分享: ${entry.uri}")
                continue
            }
            val file = prepareInternal(entry.uri, entry.title, outDir, index++) ?: continue
            result.add(FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file))
        }
        result
    }

    private fun ensureCleanOutDir(): File {
        return File(app.cacheDir, SHARE_DIR).apply {
            mkdirs()
            listFiles()?.forEach { runCatching { it.delete() } }
        }
    }

    private fun prepareInternal(uri: Uri, name: String, outDir: File? = null, index: Int = 0): File? {
        return try {
            when (uri.scheme) {
                "file" -> {
                    val src = FileManager.toSafeFile(uri)?.takeIf { it.exists() && it.isFile }
                        ?: return null
                    if (src.length() > MAX_SHARE_SOURCE_SIZE) {
                        Log.w(TAG, "源文件过大，跳过分享: ${src.name}")
                        return null
                    }
                    copyToShareDir(src, src.name, outDir ?: ensureCleanOutDir(), index)
                }
                "content" -> {
                    val size = app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0 && cursor.moveToFirst()) cursor.getLong(idx) else -1L
                    } ?: -1L
                    if (size !in 1..MAX_SHARE_SOURCE_SIZE) {
                        Log.w(TAG, "源文件过大或无法获取大小，跳过分享: size=$size")
                        return null
                    }
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        copyToShareDir(input, uri.lastPathSegment ?: name, outDir ?: ensureCleanOutDir(), index)
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "复制分享源文件失败: $uri", e)
            null
        }
    }

    /**
     * 将源文件或输入流复制到 [outDir] 目录，返回复制后的文件。
     * [name] 会经过净化处理，并通过 [index] 与时间戳组合保证唯一性，防止重名覆盖。
     */
    private fun copyToShareDir(source: Any, name: String, outDir: File, index: Int): File? {
        val safeName = sanitizeShareFileName(name)
        val prefix = if (index > 0) "${System.currentTimeMillis()}_${index}_" else "${System.currentTimeMillis()}_"
        val outFile = File(outDir, "$prefix$safeName")
        when (source) {
            is File -> source.inputStream().use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output, bufferSize = BUFFER_SIZE) }
            }
            is InputStream -> {
                FileOutputStream(outFile).use { output -> source.copyTo(output, bufferSize = BUFFER_SIZE) }
            }
            else -> return null
        }
        return outFile.takeIf { it.exists() }
    }

    /**
     * 净化分享文件名：去除路径分隔符、空字符、首尾空白，空结果时返回 "comic"，
     * 避免 content:// lastPathSegment 携带子路径导致目录遍历或写入失败。
     */
    private fun sanitizeShareFileName(name: String): String {
        val cleaned = name
            .replace("\\", "_")
            .replace("/", "_")
            .replace("\u0000", "")
            .trim()
        return if (cleaned.isEmpty()) "comic" else cleaned
    }
}
