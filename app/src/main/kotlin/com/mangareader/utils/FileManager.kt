package com.mangareader.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Lightweight file management utilities used by the bookshelf / browser
 * features for batch rename, move, delete, and copy operations.
 *
 * Supports both file:// (java.io.File) and content:// (DocumentFile) schemes
 * so users on Android 11+ can keep using SAF URIs.
 */
object FileManager {
    private const val TAG = "FileManager"

    // 外部文件导入缓存上限：200MB。超过此大小的漫画不复制到私有目录，
    // 避免占用过多用户存储并减少导入等待时间。
    private const val MAX_EXTERNAL_IMPORT_BYTES = 200L * 1024 * 1024

    /**
     * 将 file:// URI 安全地转换为规范化 File 对象。
     * 使用 canonicalPath 解析符号链接与 ..，降低路径遍历风险。
     */
    fun toSafeFile(uri: Uri): File? {
        if (uri.scheme != "file") return null
        return toSafeFile(uri.path ?: return null)
    }

    /**
     * 将本地路径安全地转换为规范化 File 对象。
     * 使用 canonicalPath 解析符号链接与 ..，降低路径遍历风险；
     * 空或空白路径返回 null。
     */
    fun toSafeFile(path: String): File? {
        if (path.isBlank()) return null
        return try {
            File(path).canonicalFile
        } catch (e: Exception) {
            Log.w(TAG, "无法规范化路径: $path", e)
            null
        }
    }

    /**
     * Rename a file or directory.
     * @return The new URI on success, or null on failure.
     */
    fun rename(context: Context, uri: Uri, newName: String): Uri? {
        val safeName = sanitizeFileName(newName)
        if (safeName.isBlank() || safeName == "." || safeName == "..") {
            Log.w(TAG, "rename rejected unsafe name: $newName")
            return null
        }
        return try {
            when (uri.scheme) {
                "file" -> {
                    val file = toSafeFile(uri) ?: return null
                    val target = File(file.parentFile, safeName)
                    if (file.renameTo(target)) Uri.fromFile(target) else null
                }
                "content" -> {
                    val doc = DocumentFile.fromSingleUri(context, uri) ?: return null
                    if (doc.renameTo(safeName)) doc.uri else null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "rename failed: $uri -> $safeName", e)
            null
        }
    }

    /**
     * Delete a file or directory recursively.
     */
    fun delete(context: Context, uri: Uri): Boolean {
        return try {
            when (uri.scheme) {
                "file" -> {
                    val file = toSafeFile(uri) ?: return false
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
                "content" -> {
                    val doc = DocumentFile.fromTreeUri(context, uri)
                        ?: DocumentFile.fromSingleUri(context, uri)
                        ?: return false
                    return@delete doc.delete()
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "delete failed: $uri", e)
            false
        }
    }

    /**
     * Move a file/folder into a target parent directory.
     * For file://, this is a simple rename across directories.
     * For content://, this copies then deletes the source.
     */
    fun move(context: Context, source: Uri, targetParent: Uri): Uri? {
        return try {
            when {
                source.scheme == "file" && targetParent.scheme == "file" -> {
                    val src = toSafeFile(source) ?: return null
                    val safeName = sanitizeFileName(src.name)
                    if (safeName.isBlank()) return null
                    val parent = toSafeFile(targetParent) ?: return null
                    val target = File(parent, safeName)
                    if (src.renameTo(target)) Uri.fromFile(target) else null
                }
                source.scheme == "content" && targetParent.scheme == "content" -> {
                    val doc = DocumentFile.fromSingleUri(context, source) ?: return null
                    val name = sanitizeFileName(doc.name ?: return null)
                    if (name.isBlank()) return null
                    val copiedUri = copy(context, source, targetParent, name) ?: return null
                    delete(context, source)
                    copiedUri
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "move failed: $source -> $targetParent", e)
            null
        }
    }

    /**
     * Copy a file to a target directory with optional new name.
     */
    fun copy(context: Context, source: Uri, targetParent: Uri, newName: String? = null): Uri? {
        return try {
            val resolvedName = newName ?: queryDisplayName(context, source) ?: return null
            when {
                source.scheme == "file" && targetParent.scheme == "file" -> {
                    val src = toSafeFile(source) ?: return null
                    val parent = toSafeFile(targetParent) ?: return null
                    val target = File(parent, resolvedName)
                    src.inputStream().use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Uri.fromFile(target)
                }
                source.scheme == "content" && targetParent.scheme == "content" -> {
                    val parentDoc = DocumentFile.fromTreeUri(context, targetParent) ?: return null
                    val mime = context.contentResolver.getType(source) ?: "application/octet-stream"
                    val newDoc = parentDoc.createFile(mime, resolvedName) ?: return null
                    try {
                        context.contentResolver.openInputStream(source)?.use { input ->
                            context.contentResolver.openOutputStream(newDoc.uri)?.use { output ->
                                input.copyTo(output)
                            }
                        } ?: throw IllegalStateException("无法打开源文件输入流")
                        newDoc.uri
                    } catch (e: Exception) {
                        // 复制失败时删除已创建的空白目标文件，避免残留垃圾文件
                        runCatching { newDoc.delete() }
                        throw e
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "copy failed: $source -> $targetParent ($newName)", e)
            null
        }
    }

    /**
     * 判断 URI 是否在应用生命周期内可持续访问。
     *
     * - file:// 方案（应用私有目录或本地文件系统）视为持久。
     * - content:// 方案仅在已获取持久读权限时视为持久，防止把临时分享的
     *   URI 存入 DataStore（如阅读历史），避免下次启动因权限失效而无法打开。
     */
    fun isDurableUri(context: Context, uri: Uri): Boolean {
        return when (uri.scheme) {
            "file" -> true
            "content" -> {
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
            }
            else -> false
        }
    }

    /**
     * 将外部 Intent 传入的 URI 转换为应用可持续访问的 URI。
     *
     * 对 content:// URI 先尝试获取持久读/写权限；若文件管理器未授予持久权限，
     * 则复制到应用私有目录 [filesDir/external_imports]，避免进程重建或权限
     * 过期后无法从历史记录再次打开。file:// URI 直接返回原值。
     *
     * 超过 [MAX_EXTERNAL_IMPORT_BYTES] 的大文件不复制，仍使用原 URI，
     * 由上层在读取失败时提示用户重新从文件管理器打开。
     */
    suspend fun resolveExternalUri(context: Context, uri: Uri): Uri? = withContext(Dispatchers.IO) {
        when (uri.scheme) {
            "file" -> uri
            "content" -> {
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                    uri
                } catch (e: SecurityException) {
                    copyExternalToCache(context, uri)
                }
            }
            else -> {
                Log.w(TAG, "resolveExternalUri unsupported scheme: ${uri.scheme}")
                null
            }
        }
    }

    private fun copyExternalToCache(context: Context, uri: Uri): Uri? {
        return try {
            val size = context.contentResolver.openFileDescriptor(uri, "r")?.use {
                it.statSize
            } ?: -1L
            if (size > MAX_EXTERNAL_IMPORT_BYTES) {
                Log.w(TAG, "外部文件超过 ${MAX_EXTERNAL_IMPORT_BYTES}B，不复制到缓存: $uri")
                return uri
            }
            val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "external_file"
            val safeName = sanitizeFileName(name).takeIf { it.isNotBlank() } ?: "external_file"
            val dir = File(context.filesDir, "external_imports").apply { mkdirs() }
            val target = File(dir, safeName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            Uri.fromFile(target)
        } catch (e: Exception) {
            Log.e(TAG, "copy external file to cache failed: $uri", e)
            null
        }
    }

    /**
     * Apply a regex transformation to a list of file names in the same
     * directory. Useful for batch re-numbering chapters like
     * `chapter_001` -> `第01话`.
     *
     * @return Number of files renamed.
     */
    fun batchRename(
        context: Context,
        directory: Uri,
        transform: (String) -> String
    ): Int {
        return try {
            var renamed = 0
            when (directory.scheme) {
                "file" -> {
                    val dir = toSafeFile(directory) ?: return 0
                    dir.listFiles()?.forEach { f ->
                        val rawName = transform(f.name)
                        val newName = sanitizeFileName(rawName)
                        if (newName.isNotBlank() && newName != f.name) {
                            val target = File(dir, newName)
                            if (f.renameTo(target)) renamed++
                        }
                    }
                }
                "content" -> {
                    val doc = DocumentFile.fromTreeUri(context, directory) ?: return 0
                    doc.listFiles().forEach { f ->
                        val current = f.name ?: return@forEach
                        val rawName = transform(current)
                        val newName = sanitizeFileName(rawName)
                        if (newName.isNotBlank() && newName != current) {
                            if (f.renameTo(newName)) renamed++
                        }
                    }
                }
            }
            renamed
        } catch (e: Exception) {
            Log.e(TAG, "batchRename failed: $directory", e)
            0
        }
    }

    /**
     * 清理文件名中的路径分隔符与非法字符，防止路径遍历或创建失败。
     */
    private fun sanitizeFileName(name: String?): String {
        if (name == null) return ""
        return name.replace(Regex("[\\\\/:\\*\\?\\\"<>|]"), "_")
            .replace("..", "_")
            .trim()
            .takeIf { it.isNotBlank() } ?: ""
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryDisplayName failed: $uri", e)
            null
        }
    }
}
