package com.mangareader.parser

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import com.mangareader.utils.FileListHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Parses a folder containing image files (JPG/PNG/WebP/GIF/HEIC/BMP) as a
 * single comic with pages sorted by name using natural sort.
 *
 * Uses [FileListHelper] for robust directory listing across Android versions.
 */
class FolderParser : ComicParser {

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp", "avif"
        )

        /** Natural-sort two file names. */
        fun compareNames(a: String, b: String): Int {
            val pa = splitParts(a)
            val pb = splitParts(b)
            val len = minOf(pa.size, pb.size)
            for (i in 0 until len) {
                val ca = pa[i]
                val cb = pb[i]
                if (ca.isNumeric && cb.isNumeric) {
                    val na = ca.text.toLongOrNull() ?: 0L
                    val nb = cb.text.toLongOrNull() ?: 0L
                    if (na != nb) return na.compareTo(nb)
                } else {
                    val cmp = ca.text.compareTo(cb.text, ignoreCase = true)
                    if (cmp != 0) return cmp
                }
            }
            return pa.size.compareTo(pb.size)
        }

        private data class Part(val text: String, val isNumeric: Boolean)

        private fun splitParts(name: String): List<Part> {
            val result = mutableListOf<Part>()
            var i = 0
            while (i < name.length) {
                if (name[i].isDigit()) {
                    val start = i
                    while (i < name.length && name[i].isDigit()) i++
                    result.add(Part(name.substring(start, i), true))
                } else {
                    val start = i
                    while (i < name.length && !name[i].isDigit()) i++
                    result.add(Part(name.substring(start, i), false))
                }
            }
            return result
        }
    }

    override suspend fun parse(context: Context, uri: Uri): ParserResult = withContext(Dispatchers.IO) {
        val children = FileListHelper.listFiles(context, uri)
            .filter { isImage(it.name) }
            .sortedWith { a, b -> compareNames(a.name, b.name) }

        if (children.isEmpty()) {
            return@withContext ParserResult.Error("该目录下没有图片文件")
        }

        val pages = children.mapIndexed { idx, entry ->
            ComicPage(
                index = idx,
                name = entry.name,
                load = { decodeBitmap(context, entry.uri) },
                loadStream = { context.contentResolver.openInputStream(entry.uri) }
            )
        }

        ParserResult.Success(pages, ComicType.FOLDER)
    }

    private fun decodeBitmap(context: Context, uri: Uri): android.graphics.Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isImage(name: String): Boolean {
        val lower = name.lowercase()
        return SUPPORTED_EXTENSIONS.any { lower.endsWith(".$it") }
    }
}
