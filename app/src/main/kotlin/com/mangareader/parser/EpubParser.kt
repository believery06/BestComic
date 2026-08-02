package com.mangareader.parser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.mangareader.R
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mangareader.utils.BitmapDecoderUtils
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * EPUB 解析器。解析时只读取目录结构（container.xml、opf、图片路径列表），
 * 每一页通过 [ZipFile.getInputStream] 按需读取原始字节流，
 * 对 content:// URI 使用 ParcelFileDescriptor 直接构建可寻址 ZipFile，避免整体复制。
 */
class EpubParser : ComicParser {

    private var zipFile: ZipFile? = null
    private var pfd: ParcelFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private val entries = ConcurrentHashMap<String, ZipArchiveEntry>()
    private val zipLock = ReentrantReadWriteLock()

    companion object {
        private const val TAG = "EpubParser"
    }

    override suspend fun parse(context: Context, uri: Uri): ParserResult = withContext(Dispatchers.IO) {
        try {
            // 1. 仅在打开/清理 zip 时持有写锁；后续解析只读 zip，改持读锁或无阻锁，
            //    避免大 EPUB 提取 HTML 文本期间阻塞 getPageBytes 的并发读取。
            val zf = synchronized(zipLock) {
                cleanup()
                openEpubFile(context, uri).also { zipFile = it }
            }

            // 第一遍：收集结构文件与图片路径，不加载 HTML 内容。
            // 同时建立「规范化名称 -> 原始 ZipEntry」映射，避免反斜杠或路径遍历导致查找失败。
            val structureFiles = mutableMapOf<String, ByteArray>()
            val normalizedEntries = mutableMapOf<String, ZipArchiveEntry>()
            val imagePathsInZip = mutableListOf<String>()

            zf.entries.iterator().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val name = normalizeArchiveEntryName(entry.name) ?: return@forEach
                normalizedEntries[name] = entry
                when {
                    name == "META-INF/container.xml" || name.endsWith(".opf") -> {
                        structureFiles[name] = zf.getInputStream(entry).use { it.readBytes() }
                    }
                    ParserFactory.isImageFile(name) -> {
                        imagePathsInZip.add(name)
                    }
                }
            }

            val imagePaths = resolveImageOrder(structureFiles, imagePathsInZip.toSet())
                ?.mapNotNull { normalizeArchiveEntryName(it) }
                ?.filter { it in normalizedEntries }
                ?: imagePathsInZip.sortedWith { a, b ->
                    FolderParser.compareNames(
                        a.substringAfterLast('/'),
                        b.substringAfterLast('/')
                    )
                }

            if (imagePaths.isEmpty()) {
                cleanup()
                return@withContext ParserResult.Error(context.getString(R.string.parser_epub_no_images))
            }

            // entries 是 ConcurrentHashMap，写操作简短，仍在写锁下完成以保证可见性。
            synchronized(zipLock) {
                entries.clear()
                imagePaths.forEach { path ->
                    normalizedEntries[path]?.let { entries[path] = it }
                }
            }

            // 第二遍：逐一流式处理 HTML 文件，提取文本并关联到引用该 HTML 的图片，
            // 处理完一个即丢弃，避免大 EPUB 把所有 HTML 同时驻留内存。
            // 预建「文件名 -> 图片路径列表」索引，避免 O(图片数 × HTML 数) 双重循环。
            val imageNameToPaths = imagePaths.groupBy { it.substringAfterLast('/') }
            val imageToText = mutableMapOf<String, String>()
            // 读锁保护 zipFile 不被 close() 并发清理；允许多个 getPageBytes 并发读取。
            zipLock.readLock().lock()
            try {
                zf.entries.iterator().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val name = entry.name
                    val isHtml = name.endsWith(".html", ignoreCase = true) ||
                            name.endsWith(".xhtml", ignoreCase = true) ||
                            name.endsWith(".htm", ignoreCase = true)
                    if (!isHtml) return@forEach

                    val bytes = zf.getInputStream(entry).use { it.readBytes() }
                    val htmlString = String(bytes, Charsets.UTF_8)
                    val text = extractTextFromHtml(bytes)
                    // 仅匹配 HTML 中显式引用的图片文件名，减少误关联并提升速度。
                    val referencedNames = extractImageNamesFromHtml(htmlString)
                    for (imageName in referencedNames) {
                        imageNameToPaths[imageName]?.forEach { imagePath ->
                            imageToText[imagePath] = text
                        }
                    }
                }
            } finally {
                zipLock.readLock().unlock()
            }

            val (screenW, screenH) = BitmapDecoderUtils.getScreenSize(context)

            val pages = imagePaths.mapIndexed { idx, path ->
                ComicPage(
                    index = idx,
                    name = path.substringAfterLast('/'),
                    load = { loadEpubImage(path, screenW, screenH) },
                    loadStream = { getPageInputStream(path) },
                    textContent = imageToText[path]?.trim() ?: ""
                )
            }

            ParserResult.Success(pages, ComicType.EPUB)
        } catch (e: CancellationException) {
            cleanup()
            throw e
        } catch (e: Exception) {
            cleanup()
            Log.e(TAG, "EPUB 解析失败", e)
            ParserResult.Error(context.getString(R.string.parser_epub_error, e.message ?: context.getString(R.string.parser_unknown_error)))
        }
    }

    private suspend fun loadEpubImage(entryName: String, screenW: Int, screenH: Int): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            val bytes = getPageBytes(entryName) ?: return@withContext null
            BitmapDecoderUtils.decodeSampled(bytes, screenW, screenH, tag = TAG)
        }

    /** 直接从 EPUB 压缩包读取单页原始字节，避免 decodePage 中 InputStream -> ByteArray 的二次拷贝。 */
    private fun getPageBytes(entryName: String): ByteArray? {
        val normalized = normalizeArchiveEntryName(entryName) ?: return null
        val entry = entries[normalized]
        return try {
            zipLock.readLock().lock()
            try {
                val zf = zipFile ?: return null
                val resolvedEntry = entry ?: zf.getEntry(normalized) ?: return null
                zf.getInputStream(resolvedEntry)?.use { it.readBytes() }
            } finally {
                zipLock.readLock().unlock()
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取 EPUB 页面字节失败", e)
            null
        }
    }

    private fun getPageInputStream(entryName: String): InputStream? {
        val normalized = normalizeArchiveEntryName(entryName) ?: return null
        val entry = entries[normalized]
        return try {
            zipLock.readLock().lock()
            try {
                val zf = zipFile ?: return null
                val resolvedEntry = entry ?: zf.getEntry(normalized) ?: return null
                zf.getInputStream(resolvedEntry)?.use { it.readBytes() }?.let { ByteArrayInputStream(it) }
            } finally {
                zipLock.readLock().unlock()
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取 EPUB 页面流失败", e)
            null
        }
    }

    private fun openEpubFile(context: Context, uri: Uri): ZipFile {
        // file:// URI 可直接使用真实路径，避免大文件复制
        if (uri.scheme == "file") {
            uri.path?.let { File(it) }?.takeIf(File::exists)?.let {
                return ZipFile.Builder().setFile(it).get()
            }
        }
        // content:// URI 使用 ParcelFileDescriptor 直接创建可寻址 ZipFile
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Cannot open EPUB file descriptor")
        var input: FileInputStream? = null
        return try {
            input = FileInputStream(descriptor.fileDescriptor)
            val zf = ZipFile.Builder().setSeekableByteChannel(input.channel).get()
            pfd = descriptor
            inputStream = input
            zf
        } catch (e: Exception) {
            runCatching { input?.close() }
            runCatching { descriptor.close() }
            throw e
        }
    }

    private fun cleanup() {
        entries.clear()
        try {
            zipFile?.close()
        } catch (_: Exception) {}
        zipFile = null
        try {
            inputStream?.close()
        } catch (_: Exception) {}
        inputStream = null
        try {
            pfd?.close()
        } catch (_: Exception) {}
        pfd = null
    }

    override fun close() {
        zipLock.writeLock().lock()
        try {
            cleanup()
        } finally {
            zipLock.writeLock().unlock()
        }
    }

    private fun resolveImageOrder(
        structureFiles: Map<String, ByteArray>,
        @Suppress("UNUSED_PARAMETER") imageKeys: Set<String>
    ): List<String>? {
        try {
            val container = structureFiles["META-INF/container.xml"] ?: return null
            val rootfilePath = parseRootfilePath(container.inputStream()) ?: return null
            val opfBytes = structureFiles[rootfilePath] ?: return null
            val opfDir = rootfilePath.substringBeforeLast('/', "")

            val (idToHref, _) = parseManifest(opfBytes.inputStream())
            val spineIds = parseSpine(opfBytes.inputStream())

            return spineIds.mapNotNull { id ->
                val href = idToHref[id]
                if (href != null && ParserFactory.isImageFile(href)) {
                    if (opfDir.isEmpty()) href else "$opfDir/$href"
                } else null
            }.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "解析 EPUB 图片顺序失败", e)
            return null
        }
    }

    private fun parseRootfilePath(input: InputStream): String? {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                return parser.getAttributeValue(null, "full-path")
            }
            event = parser.next()
        }
        return null
    }

    private fun parseManifest(input: InputStream): Pair<Map<String, String>, Map<String, String>> {
        val idToHref = mutableMapOf<String, String>()
        val mediaTypes = mutableMapOf<String, String>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val id = parser.getAttributeValue(null, "id") ?: ""
                val href = parser.getAttributeValue(null, "href") ?: ""
                val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                idToHref[id] = href
                mediaTypes[id] = mediaType
            }
            event = parser.next()
        }
        return idToHref to mediaTypes
    }

    private fun parseSpine(input: InputStream): List<String> {
        val ids = mutableListOf<String>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "itemref") {
                parser.getAttributeValue(null, "idref")?.let { ids.add(it) }
            }
            event = parser.next()
        }
        return ids
    }

    /**
     * 从 HTML 内容中提取所有引用的图片文件名（src/srcset 属性）。
     * 用于把图片与所在 HTML 的文本关联，避免全字符串包含匹配导致误关联。
     */
    private fun extractImageNamesFromHtml(html: String): Set<String> {
        return try {
            val names = mutableSetOf<String>()
            // 匹配 <img src="..." /> 或 <img src='...'>
            val imgRegex = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            for (match in imgRegex.findAll(html)) {
                names.add(match.groupValues[1].substringAfterLast('/'))
            }
            // 匹配 srcset="1x.png 1x, 2x.png 2x"
            val srcsetRegex = Regex("srcset=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            for (match in srcsetRegex.findAll(html)) {
                match.groupValues[1].split(',').forEach { part ->
                    part.trim().substringBefore(' ').substringAfterLast('/').takeIf { it.isNotBlank() }?.let(names::add)
                }
            }
            names
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * 从 HTML/XHTML 字节流中提取纯文本，用于 TTS 文字朗读。
     * 简单移除标签、脚本与样式内容，保留可读文本。
     */
    private fun extractTextFromHtml(bytes: ByteArray): String {
        return try {
            val html = String(bytes, Charsets.UTF_8)
            // 移除 script/style 标签及其内容
            var text = html
                .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
            // 移除其余标签
            text = text.replace(Regex("<[^>]+>"), " ")
            // 解码常见 HTML 实体
            text = text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
            // 合并空白
            text.replace(Regex("\\s+"), " ").trim()
        } catch (_: Exception) {
            ""
        }
    }

}
