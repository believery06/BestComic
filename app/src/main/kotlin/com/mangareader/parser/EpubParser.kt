package com.mangareader.parser

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * EPUB 解析器。解析时只读取目录结构（container.xml、opf、图片路径列表），
 * 每一页通过 [ZipFile.getInputStream] 按需读取原始字节流，
 * 由上层按屏幕尺寸采样解码，避免全分辨率加载。
 */
class EpubParser : ComicParser {

    private var tempFile: File? = null
    private var ownsTempFile: Boolean = false
    private var zipFile: ZipFile? = null
    private val entries = mutableMapOf<String, java.util.zip.ZipEntry>()
    private val zipLock = Any()

    override suspend fun parse(context: Context, uri: Uri): ParserResult = withContext(Dispatchers.IO) {
        try {
            cleanup()

            val tmp = openEpubFile(context, uri)
            tempFile = tmp
            val zf = ZipFile(tmp)
            zipFile = zf

            val structureFiles = mutableMapOf<String, ByteArray>()
            val imagePathsInZip = mutableListOf<String>()

            val zipEntries = zf.entries()
            while (zipEntries.hasMoreElements()) {
                val entry = zipEntries.nextElement()
                if (!entry.isDirectory) {
                    val name = entry.name
                    when {
                        name == "META-INF/container.xml" || name.endsWith(".opf") -> {
                            structureFiles[name] = zf.getInputStream(entry).use { it.readBytes() }
                        }
                        ParserFactory.isImageFile(name) -> {
                            imagePathsInZip.add(name)
                        }
                    }
                }
            }

            val imagePaths = resolveImageOrder(structureFiles, imagePathsInZip.toSet())
                ?: imagePathsInZip.sortedWith { a, b ->
                    FolderParser.compareNames(
                        a.substringAfterLast('/'),
                        b.substringAfterLast('/')
                    )
                }

            if (imagePaths.isEmpty()) {
                cleanup()
                return@withContext ParserResult.Error("EPUB 中没有找到图片")
            }

            imagePaths.forEach { path ->
                zf.getEntry(path)?.let { entries[path] = it }
            }

            val pages = imagePaths.mapIndexed { idx, path ->
                ComicPage(
                    index = idx,
                    name = path.substringAfterLast('/'),
                    load = { loadEpubImage(path) },
                    loadStream = { getPageInputStream(path) }
                )
            }

            ParserResult.Success(pages, ComicType.EPUB)
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            ParserResult.Error("EPUB 解析错误: ${e.message}")
        }
    }

    private suspend fun loadEpubImage(entryName: String): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            getPageInputStream(entryName)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }

    private fun getPageInputStream(entryName: String): InputStream? {
        val zf = zipFile ?: return null
        val entry = entries[entryName] ?: zf.getEntry(entryName) ?: return null
        return try {
            synchronized(zipLock) {
                zf.getInputStream(entry)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun openEpubFile(context: Context, uri: Uri): File {
        // file:// URI 可直接使用真实路径，避免大文件复制
        if (uri.scheme == "file") {
            val path = uri.path?.let { File(it) }
            if (path != null && path.exists()) {
                ownsTempFile = false
                return path
            }
        }
        val temp = File.createTempFile("epub_", ".epub", context.cacheDir)
        ownsTempFile = true
        context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedInputStream(input, 262144).use { bis ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(131072)
                    var read: Int
                    while (bis.read(buffer).also { read = it } > 0) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
        } ?: throw IllegalStateException("Cannot open EPUB input stream")
        return temp
    }

    private fun cleanup() {
        entries.clear()
        try {
            zipFile?.close()
        } catch (_: Exception) {}
        zipFile = null
        if (ownsTempFile) {
            tempFile?.delete()
        }
        tempFile = null
        ownsTempFile = false
    }

    override fun close() {
        cleanup()
    }

    private fun resolveImageOrder(
        structureFiles: Map<String, ByteArray>,
        imageKeys: Set<String>
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
            e.printStackTrace()
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

}
