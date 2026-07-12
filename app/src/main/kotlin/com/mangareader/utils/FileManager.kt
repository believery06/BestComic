package com.mangareader.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
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

    /**
     * Rename a file or directory.
     * @return The new URI on success, or null on failure.
     */
    fun rename(context: Context, uri: Uri, newName: String): Uri? {
        return try {
            when (uri.scheme) {
                "file" -> {
                    val file = File(uri.path ?: return null)
                    val target = File(file.parentFile, newName)
                    if (file.renameTo(target)) Uri.fromFile(target) else null
                }
                "content" -> {
                    val doc = DocumentFile.fromSingleUri(context, uri) ?: return null
                    if (doc.renameTo(newName)) doc.uri else null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "rename failed: $uri -> $newName", e)
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
                    val file = File(uri.path ?: return false)
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
                "content" -> {
                    val doc = DocumentFile.fromTreeUri(context, uri)
                        ?: DocumentFile.fromSingleUri(context, uri)
                        ?: return false
                    doc.delete()
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
                    val src = File(source.path ?: return null)
                    val parent = File(targetParent.path ?: return null)
                    val target = File(parent, src.name)
                    if (src.renameTo(target)) Uri.fromFile(target) else null
                }
                source.scheme == "content" && targetParent.scheme == "content" -> {
                    val doc = DocumentFile.fromSingleUri(context, source) ?: return null
                    val name = doc.name ?: return null
                    val parentDoc = DocumentFile.fromTreeUri(context, targetParent) ?: return null
                    copy(context, source, targetParent, name)
                    delete(context, source)
                    parentDoc.findFile(name)?.uri
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
                    val src = File(source.path ?: return null)
                    val parent = File(targetParent.path ?: return null)
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
                    context.contentResolver.openInputStream(source)?.use { input ->
                        context.contentResolver.openOutputStream(newDoc.uri)?.use { output ->
                            input.copyTo(output)
                        }
                    } ?: return null
                    newDoc.uri
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "copy failed: $source -> $targetParent ($newName)", e)
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
                    val dir = File(directory.path ?: return 0)
                    dir.listFiles()?.forEach { f ->
                        val newName = transform(f.name)
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
                        val newName = transform(current)
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
