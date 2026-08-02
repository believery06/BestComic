package com.mangareader.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 统一目录列表助手，兼容所有 Android 版本和 file:///content:// URI。
 *
 * 核心策略：
 * - file:// → File.listFiles()
 * - content:// → DocumentFile.fromTreeUri → DocumentsContract 查询 → file 路径回退
 */
object FileListHelper {

    private const val TAG = "FileListHelper"

    data class DirEntry(
        val name: String,
        val uri: Uri,
        val isDirectory: Boolean
    )

    /** 会话级缓存，仅缓存非空结果，避免重复 IO。 */
    private val cache = LinkedHashMap<String, List<DirEntry>>(64, 0.75f, true)
    private const val MAX_CACHE_SIZE = 128

    fun listChildren(context: Context, uri: Uri): List<DirEntry> {
        val key = uri.toString()
        synchronized(cache) {
            cache[key]?.let { return it }
        }

        val result = when (uri.scheme) {
            "file" -> listFileChildren(uri)
            "content" -> listContentChildren(context, uri)
            else -> emptyList()
        }

        // 空目录也缓存，避免用户反复进入空 SAF 目录时重复发起 DocumentFile 查询。
        // 文件变更通过 FileListHelper.clearCache() / ReaderViewModel.loadComic() 等入口主动失效。
        synchronized(cache) {
            cache[key] = result
            while (cache.size > MAX_CACHE_SIZE) {
                cache.keys.firstOrNull()?.let { cache.remove(it) }
            }
        }
        return result
    }

    fun clearCache() {
        synchronized(cache) {
            cache.clear()
        }
    }

    // ── file:// ──────────────────────────────────────────────────────────

    private fun listFileChildren(uri: Uri): List<DirEntry> {
        val path = uri.path ?: return emptyList()
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()

        val children = dir.listFiles() ?: return emptyList()
        return children.mapNotNull { file ->
            if (file.name.startsWith(".")) return@mapNotNull null
            DirEntry(
                name = file.name,
                uri = Uri.fromFile(file),
                isDirectory = file.isDirectory
            )
        }
    }

    // ── content:// (SAF) ─────────────────────────────────────────────────

    private fun listContentChildren(context: Context, uri: Uri): List<DirEntry> {
        // 策略 1: DocumentFile.fromTreeUri
        // 在 API 26+ 上能正确处理 tree URI 和子文档 URI
        try {
            val doc = DocumentFile.fromTreeUri(context, uri)
            if (doc != null && doc.isDirectory) {
                val children = doc.listFiles()
                if (children.isNotEmpty()) {
                    return children.mapNotNull { f ->
                        val name = f.name ?: return@mapNotNull null
                        if (name.startsWith(".")) return@mapNotNull null
                        DirEntry(name = name, uri = f.uri, isDirectory = f.isDirectory)
                    }
                }
                // listFiles 返回空 — 可能是真正的空目录，也可能是查询失败
                // 继续尝试策略 2
            }
        } catch (e: Exception) {
            Log.w(TAG, "fromTreeUri failed: ${e.message}")
        }

        // 策略 2: DocumentsContract 直接查询
        try {
            val treeUri = extractTreeUri(uri)
            val docId = getDocumentIdSafe(uri)
            if (docId != null) {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                val result = mutableListOf<DirEntry>()
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val childDocId = cursor.getString(idCol) ?: continue
                        val name = cursor.getString(nameCol) ?: continue
                        if (name.startsWith(".")) continue
                        val mime = cursor.getString(mimeCol)
                        val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                        result.add(DirEntry(name = name, uri = childUri, isDirectory = isDir))
                    }
                }
                if (result.isNotEmpty()) return result
            }
        } catch (e: Exception) {
            Log.w(TAG, "DocumentsContract query failed: ${e.message}")
        }

        // 策略 3: 尝试将 content:// URI 转为文件路径（需要 MANAGE_EXTERNAL_STORAGE）
        val filePath = contentUriToFilePath(context, uri)
        if (filePath != null) {
            val fileResult = listFileChildren(Uri.fromFile(File(filePath)))
            if (fileResult.isNotEmpty()) return fileResult
        }

        return emptyList()
    }

    /**
     * 从文档 URI 中提取 tree URI。
     * content://auth/tree/rootId/document/childId → content://auth/tree/rootId
     */
    private fun extractTreeUri(documentUri: Uri): Uri {
        val uriStr = documentUri.toString()
        val treeIdx = uriStr.indexOf("/tree/")
        val docIdx = uriStr.indexOf("/document/")
        return if (treeIdx >= 0 && docIdx > treeIdx) {
            Uri.parse(uriStr.substring(0, docIdx))
        } else {
            documentUri
        }
    }

    /**
     * 安全获取 DocumentId，兼容 tree URI 和 document URI。
     */
    private fun getDocumentIdSafe(uri: Uri): String? {
        return try {
            DocumentsContract.getDocumentId(uri)
        } catch (e: Exception) {
            try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * 尝试将 SAF content:// URI 转为文件系统路径。
     * 仅支持 ExternalStorageProvider 的 primary 和 SD 卡路径。
     */
    private fun contentUriToFilePath(context: Context, uri: Uri): String? {
        val authority = uri.authority ?: return null
        if (authority != "com.android.externalstorage.documents") return null

        val decoded = uri.toString()
        // 提取 /document/ 后面的部分
        val docIdx = decoded.indexOf("/document/")
        if (docIdx < 0) return null
        val docPart = decoded.substring(docIdx + "/document/".length)
        // URL 解码
        val docId = java.net.URLDecoder.decode(docPart, "UTF-8")

        return when {
            docId.startsWith("primary:") -> {
                val relative = docId.substringAfter("primary:")
                val primaryRoot = getPrimaryStorageRoot(context) ?: return null
                File(primaryRoot, relative).absolutePath
            }
            docId == "primary" -> getPrimaryStorageRoot(context)
            else -> {
                // 可能是 SD 卡路径，格式如 "1234-5678:folder/file"
                val colonIdx = docId.indexOf(':')
                if (colonIdx > 0) {
                    val storageId = docId.substring(0, colonIdx)
                    val relative = docId.substring(colonIdx + 1)
                    // 尝试在挂载点中查找
                    val sdRoot = getSdCardRoot(context, storageId)
                    if (sdRoot != null && sdRoot.exists()) {
                        File(sdRoot, relative).absolutePath
                    } else null
                } else null
            }
        }
    }

    /**
     * 通过 Context.getExternalFilesDir 反推主存储根目录，
     * 避免使用废弃的 Environment.getExternalStorageDirectory。
     */
    private fun getPrimaryStorageRoot(context: Context): String? {
        val externalFiles = context.getExternalFilesDir(null) ?: return null
        val path = externalFiles.absolutePath
        val marker = "/Android/data/"
        val idx = path.indexOf(marker)
        return if (idx > 0) path.substring(0, idx) else null
    }

    /**
     * 通过 Context.getExternalFilesDirs 匹配 storageId 并反推 SD 卡根目录。
     */
    private fun getSdCardRoot(context: Context, storageId: String): File? {
        val dirs = context.getExternalFilesDirs(null)
        for (dir in dirs) {
            if (dir == null) continue
            val path = dir.absolutePath
            if (path.contains("/$storageId/")) {
                val marker = "/Android/data/"
                val idx = path.indexOf(marker)
                if (idx > 0) return File(path.substring(0, idx))
            }
        }
        return null
    }

    // ── Convenience filters ──────────────────────────────────────────────

    fun listFiles(context: Context, uri: Uri): List<DirEntry> =
        listChildren(context, uri).filter { !it.isDirectory }

    fun listDirectories(context: Context, uri: Uri): List<DirEntry> =
        listChildren(context, uri).filter { it.isDirectory }

    fun hasChildren(context: Context, uri: Uri): Boolean =
        listChildren(context, uri).isNotEmpty()
}