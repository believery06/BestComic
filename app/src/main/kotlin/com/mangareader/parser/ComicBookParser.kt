package com.mangareader.parser

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.mangareader.R
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import com.mangareader.utils.FileListHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * 漫画书解析器：目录下的子文件（PDF/EPUB/CBZ...）为章节。
 *
 * 遍历章节文件，解析每个章节的页数并拼接成完整页面列表。
 * 如果目录只包含图片文件，自动回退到 [FolderParser]。
 *
 * 安全增强：
 * - 递归深度限制（最多 4 层），防止恶意/异常目录结构导致栈溢出或长时间解析。
 * - 已访问 URI 集合，防止符号链接或重复引用导致的无限循环。
 */
class ComicBookParser : ComicParser {

    companion object {
        private const val TAG = "ComicBookParser"
        private const val MAX_DEPTH = 4
    }

    private val innerParsers = java.util.Collections.synchronizedList(mutableListOf<ComicParser>())

    override fun close() {
        // 必须对整个遍历加锁，避免 parse() 中 add 与 close() 并发导致 ConcurrentModificationException。
        val snapshot: List<ComicParser>
        synchronized(innerParsers) {
            snapshot = innerParsers.toList()
            innerParsers.clear()
        }
        snapshot.forEach { runCatching { it.close() } }
    }

    override suspend fun parse(context: Context, uri: Uri): ParserResult =
        parseRecursive(context, uri, depth = 0, visited = mutableSetOf())

    private suspend fun parseRecursive(
        context: Context,
        uri: Uri,
        depth: Int,
        visited: MutableSet<String>
    ): ParserResult = withContext(Dispatchers.IO) {
        val uriKey = uri.toString()
        if (!visited.add(uriKey)) {
            Log.w(TAG, "Cycle detected or URI revisited, skip: $uri")
            return@withContext ParserResult.Error(context.getString(R.string.parser_comicbook_cycle))
        }
        if (depth > MAX_DEPTH) {
            Log.w(TAG, "Max depth exceeded at: $uri")
            return@withContext ParserResult.Error(context.getString(R.string.parser_comicbook_too_deep))
        }

        val children = FileListHelper.listChildren(context, uri)
        if (children.isEmpty()) {
            return@withContext ParserResult.Error(context.getString(R.string.parser_comicbook_no_files))
        }

        val chapterFiles = children.filter { !it.isDirectory && ParserFactory.detectType(it.name) != ComicType.UNKNOWN }
            .map { ChapterFile(it.name, it.uri, ParserFactory.detectType(it.name)) }
            .sortedWith { a, b -> VolumeSortHelper.compareNames(a.title, b.title) }

        val imageFiles = children.filter { !it.isDirectory && ParserFactory.isImageFile(it.name) }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }

        val subDirs = children.filter { it.isDirectory }
            .sortedWith { a, b -> VolumeSortHelper.compareNames(a.name, b.name) }

        if (chapterFiles.isEmpty() && imageFiles.isEmpty() && subDirs.isEmpty()) {
            return@withContext ParserResult.Error(context.getString(R.string.parser_comicbook_no_chapters_or_images))
        }

        val allPages = mutableListOf<ComicPage>()
        // 本层创建的 parser 先收集到局部列表，全部创建完成后再一次性合并到 innerParsers，
        // 避免 async 执行期间与 close() 并发修改 innerParsers。
        val localParsers = mutableListOf<ComicParser>()

        if (imageFiles.isNotEmpty()) {
            val imagePages = imageFiles.mapIndexed { idx, entry ->
                ComicPage(
                    index = idx,
                    name = entry.name,
                    load = { decodeBitmapFromUri(context, entry.uri) },
                    loadStream = { context.contentResolver.openInputStream(entry.uri) }
                )
            }
            allPages.addAll(imagePages)
        }

        val chapterDeferreds = chapterFiles.mapNotNull { chapter ->
            val parser = ParserFactory.getParser(chapter.type) ?: return@mapNotNull null
            localParsers.add(parser)
            async {
                try {
                    when (val result = parser.parse(context, chapter.uri)) {
                        is ParserResult.Success -> {
                            result.pages.map { it.copy(chapterTitle = chapter.title) }
                        }
                        is ParserResult.Error -> {
                            Log.w(TAG, "Chapter parse failed: ${chapter.title} - ${result.message}")
                            emptyList()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Chapter parse exception: ${chapter.title}", e)
                    emptyList()
                }
            }
        }

        val subDirDeferreds = subDirs.mapNotNull { subDir ->
            val subType = detectSubDirContent(context, subDir.uri) ?: return@mapNotNull null
            if (subType == ComicType.COMIC_BOOK) {
                async {
                    when (val result = parseRecursive(context, subDir.uri, depth + 1, visited)) {
                        is ParserResult.Success -> {
                            result.pages.map { it.copy(chapterTitle = subDir.name) }
                        }
                        is ParserResult.Error -> {
                            Log.w(TAG, "Subdir parse failed: ${subDir.name} - ${result.message}")
                            emptyList()
                        }
                    }
                }
            } else {
                val parser = ParserFactory.getParser(subType) ?: return@mapNotNull null
                localParsers.add(parser)
                async {
                    try {
                        when (val result = parser.parse(context, subDir.uri)) {
                            is ParserResult.Success -> {
                                result.pages.map { it.copy(chapterTitle = subDir.name) }
                            }
                            is ParserResult.Error -> {
                                Log.w(TAG, "Subdir parse failed: ${subDir.name} - ${result.message}")
                                emptyList()
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Subdir parse exception: ${subDir.name}", e)
                        emptyList()
                    }
                }
            }
        }

        // 解析任务创建完毕后再统一注册到 innerParsers，避免并发遍历问题。
        if (localParsers.isNotEmpty()) {
            synchronized(innerParsers) { innerParsers.addAll(localParsers) }
        }

        val parsedChapterPages = chapterDeferreds.awaitAll().flatten()
        val parsedSubDirPages = subDirDeferreds.awaitAll().flatten()

        allPages.addAll(parsedChapterPages)
        allPages.addAll(parsedSubDirPages)

        if (allPages.isEmpty()) {
            // 回退到 FolderParser 时也要纳入 innerParsers 管理，确保 close() 能统一关闭。
            val folderParser = FolderParser()
            synchronized(innerParsers) { innerParsers.add(folderParser) }
            return@withContext folderParser.parse(context, uri)
        }

        ParserResult.Success(allPages, ComicType.COMIC_BOOK)
    }

    private fun detectSubDirContent(context: Context, dirUri: Uri): ComicType? {
        val subChildren = FileListHelper.listChildren(context, dirUri)
        val hasChapter = subChildren.any { !it.isDirectory && ParserFactory.detectType(it.name) != ComicType.UNKNOWN }
        if (hasChapter) return ComicType.COMIC_BOOK
        val hasImages = subChildren.any { !it.isDirectory && ParserFactory.isImageFile(it.name) }
        if (hasImages) return ComicType.FOLDER
        return null
    }

    private fun decodeBitmapFromUri(context: Context, uri: Uri): android.graphics.Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // 按 1920×1080 目标降采样，避免大图一次性占用过多内存
                val bytes = input.readBytes()
                decodeSampled(bytes, 1920, 1080)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeSampled(bytes: ByteArray, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            var halfW = bounds.outWidth
            var halfH = bounds.outHeight
            while (halfW / 2 >= reqWidth && halfH / 2 >= reqHeight) {
                halfW /= 2
                halfH /= 2
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        }
    }

    private fun naturalCompare(a: String, b: String): Int =
        FolderParser.compareNames(a, b)

    private data class ChapterFile(
        val title: String,
        val uri: Uri,
        val type: ComicType
    )
}
