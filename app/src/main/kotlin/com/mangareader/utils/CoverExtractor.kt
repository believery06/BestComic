package com.mangareader.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.mangareader.data.ComicType
import com.mangareader.utils.BitmapDecoderUtils
import com.mangareader.utils.isImageFile
import com.mangareader.utils.readBytesLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Extracts a cover thumbnail from the first page of a chapter file.
 * Supported formats: PDF, EPUB, CBZ/ZIP, CBR/RAR, CB7/7Z, CBT/TAR.
 *
 * Thumbnails are cached in the app's cache directory so they are only
 * generated once per file.
 */
object CoverExtractor {

    private const val TAG = "CoverExtractor"
    private const val THUMB_MAX_SIZE = 480
    private const val MAX_COVER_CACHE_FILES = 200
    /** 封面源文件单条目上限 50MB，防止恶意或损坏归档中的超大首图导致 OOM。 */
    private const val MAX_COVER_ENTRY_BYTES = 50L * 1024 * 1024

    /**
     * Extract a cover thumbnail from the given file. Returns a cached file
     * URI or null if extraction fails.
     */
    suspend fun extractCover(context: Context, uri: Uri, type: ComicType): Uri? = withContext(Dispatchers.IO) {
        val cacheKey = safeCacheKey(uri.toString())
        val cacheFile = File(context.cacheDir, "thumb_$cacheKey.png")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return@withContext Uri.fromFile(cacheFile)
        }

        val bitmap = when (type) {
            ComicType.PDF -> extractPdfFirstPage(context, uri)
            ComicType.EPUB -> extractEpubFirstImage(context, uri)
            ComicType.CBZ -> extractZipFirstImage(context, uri)
            ComicType.CBR -> extractRarFirstImage(context, uri)
            ComicType.CB7 -> extractSevenZipFirstImage(context, uri)
            ComicType.CBT -> extractTarFirstImage(context, uri)
            ComicType.MOBI -> extractMobiFirstImage(context, uri)
            else -> null
        } ?: return@withContext null

        val thumb = scaleDown(bitmap, THUMB_MAX_SIZE)
        if (thumb !== bitmap && !bitmap.isRecycled) bitmap.recycle()

        // 先写临时文件再原子改名：写入中途进程被杀不会留下损坏的半截 PNG，
        // 否则之后会永久命中 exists() 的坏封面且无法自愈。
        val tmpFile = File(context.cacheDir, "thumb_${cacheKey}.png.tmp")
        var ok = false
        try {
            FileOutputStream(tmpFile).use { out ->
                ok = thumb.compress(Bitmap.CompressFormat.PNG, 85, out)
            }
            if (!ok) {
                tmpFile.delete()
                return@withContext null
            }
            if (!tmpFile.renameTo(cacheFile)) {
                // 极端情况下 rename 失败（如跨挂载点），退化为直接复制写入
                tmpFile.inputStream().use { input ->
                    FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                }
                tmpFile.delete()
            }
        } finally {
            runCatching { if (!thumb.isRecycled) thumb.recycle() }
            runCatching { if (tmpFile.exists()) tmpFile.delete() }
        }

        if (cacheFile.exists() && cacheFile.length() > 0) {
            cleanupOldCovers(context)
            Uri.fromFile(cacheFile)
        } else null
    }

    /**
     * 封面缩略图数量超过上限时，按修改时间删除最旧的文件。
     */
    private fun cleanupOldCovers(context: Context) {
        val files = context.cacheDir.listFiles { _, name ->
            name.startsWith("thumb_") && name.endsWith(".png")
        } ?: return
        if (files.size > MAX_COVER_CACHE_FILES) {
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_COVER_CACHE_FILES)
                .forEach { runCatching { it.delete() } }
        }
    }

    // ── PDF ──────────────────────────────────────────────────────────────

    private fun extractPdfFirstPage(context: Context, uri: Uri): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var bmp: Bitmap? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Cannot open PDF")
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount <= 0) return null
            renderer.openPage(0).use { page ->
                val scale = (THUMB_MAX_SIZE.toFloat() / maxOf(page.width, page.height)).coerceAtMost(1f)
                // Android 新版 PdfRenderer.render 只接受 ARGB_8888
                val rendered = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                bmp = rendered
                val canvas = android.graphics.Canvas(rendered)
                canvas.drawColor(Color.WHITE)
                page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            bmp
        } catch (e: OutOfMemoryError) {
            bmp?.takeIf { !it.isRecycled }?.recycle()
            Log.e(TAG, "extractPdfFirstPage OOM: $uri", e)
            null
        } catch (e: Exception) {
            bmp?.takeIf { !it.isRecycled }?.recycle()
            Log.w(TAG, "extractPdfFirstPage failed: $uri", e)
            null
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }

    // ── EPUB ─────────────────────────────────────────────────────────────

    private fun extractEpubFirstImage(context: Context, uri: Uri): Bitmap? {
        return try {
            val coverHref = resolveEpubCoverHref(context, uri)
            if (coverHref != null) {
                decodeEpubEntry(context, uri, coverHref)?.let { return it }
            }
            // Fallback：按自然排序读取第一张图片，按缩略图尺寸降采样，避免大图 OOM。
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.lowercase()
                        if (isImageExt(name)) {
                            return decodeSampled(zip, THUMB_MAX_SIZE, THUMB_MAX_SIZE)
                        }
                        entry = zip.nextEntry
                    }
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractEpubFirstImage failed: $uri", e)
            null
        }
    }

    /**
     * 解析 EPUB 结构，尝试获取封面图片在压缩包内的路径。
     * 优先识别 OPF manifest 中 properties="cover-image" 的 item，
     * 其次识别 metadata 中 <meta name="cover" content="id"/> 引用的 item。
     */
    private fun resolveEpubCoverHref(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use zip@ { zip ->
                    var containerBytes: ByteArray? = null
                    var opfBytes: ByteArray? = null
                    var firstOpfPath = ""
                    var opfDir = ""
                    var entry = zip.nextEntry
                    while (entry != null) {
                        when (entry.name.lowercase()) {
                            "meta-inf/container.xml" -> containerBytes = zip.readBytes()
                            else -> {
                                if (opfBytes == null && entry.name.lowercase().endsWith(".opf")) {
                                    opfBytes = zip.readBytes()
                                    firstOpfPath = entry.name
                                    opfDir = entry.name.substringBeforeLast('/', "")
                                }
                            }
                        }
                        if (containerBytes != null && opfBytes != null) break
                        entry = zip.nextEntry
                    }

                    val opfPathFromContainer = containerBytes?.let { parseRootfilePath(ByteArrayInputStream(it)) }
                    // 优先使用 container.xml 指定的 OPF 路径；若第一次遍历到的不是该路径，则二次遍历
                    val needExactOpf = opfPathFromContainer != null &&
                            !firstOpfPath.equals(opfPathFromContainer, ignoreCase = true)

                    var opfBytesFinal: ByteArray? = opfBytes
                    var opfDirFinal = opfDir
                    if (needExactOpf) {
                        // 抽取局部不可变引用，消除 lambda 内对可空变量的 !! 强转。
                        val exactOpfPath = opfPathFromContainer ?: return@zip null
                        context.contentResolver.openInputStream(uri)?.use { second ->
                            ZipInputStream(second).use { zip2 ->
                                var e2 = zip2.nextEntry
                                while (e2 != null) {
                                    if (e2.name.equals(exactOpfPath, ignoreCase = true)) {
                                        opfBytesFinal = zip2.readBytes()
                                        opfDirFinal = exactOpfPath.substringBeforeLast('/', "")
                                        break
                                    }
                                    e2 = zip2.nextEntry
                                }
                            }
                        }
                    }

                    opfBytesFinal ?: return@zip null
                    val (idToHref, propertiesToHref) = parseOpfManifest(ByteArrayInputStream(opfBytesFinal))
                    propertiesToHref["cover-image"]?.firstOrNull()?.let { href ->
                        return@zip resolveOpfHref(opfDirFinal, href)
                    }
                    val coverId = parseCoverMeta(ByteArrayInputStream(opfBytesFinal))
                    coverId?.let { idToHref[it] }?.let { href ->
                        return@zip resolveOpfHref(opfDirFinal, href)
                    }
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveEpubCoverHref failed: $uri", e)
            null
        }
    }

    private fun decodeEpubEntry(context: Context, uri: Uri, href: String): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use zip@ { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name.equals(href, ignoreCase = true)) {
                            return@zip decodeSampled(zip, THUMB_MAX_SIZE, THUMB_MAX_SIZE)
                        }
                        entry = zip.nextEntry
                    }
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "decodeEpubEntry failed: $uri / $href", e)
            null
        }
    }

    private fun parseRootfilePath(input: InputStream): String? {
        return try {
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
            null
        } catch (_: Exception) {
            null
        }
    }

    private data class ManifestResult(
        val idToHref: Map<String, String>,
        val propertiesToHref: Map<String, List<String>>
    )

    private fun parseOpfManifest(input: InputStream): ManifestResult {
        val idToHref = mutableMapOf<String, String>()
        val propertiesToHref = mutableMapOf<String, MutableList<String>>()
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(input, "UTF-8")
            var event = parser.eventType
            var inManifest = false
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "manifest") {
                    inManifest = true
                } else if (event == XmlPullParser.END_TAG && parser.name == "manifest") {
                    inManifest = false
                } else if (inManifest && event == XmlPullParser.START_TAG && parser.name == "item") {
                    val id = parser.getAttributeValue(null, "id") ?: continue
                    val href = parser.getAttributeValue(null, "href") ?: continue
                    val props = parser.getAttributeValue(null, "properties") ?: ""
                    idToHref[id] = href
                    props.split(" ").filter { it.isNotBlank() }.forEach { prop ->
                        propertiesToHref.getOrPut(prop) { mutableListOf() }.add(href)
                    }
                }
                event = parser.next()
            }
            ManifestResult(idToHref, propertiesToHref)
        } catch (_: Exception) {
            ManifestResult(emptyMap(), emptyMap())
        }
    }

    private fun parseCoverMeta(input: InputStream): String? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(input, "UTF-8")
            var event = parser.eventType
            var inMetadata = false
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "metadata") {
                    inMetadata = true
                } else if (event == XmlPullParser.END_TAG && parser.name == "metadata") {
                    inMetadata = false
                } else if (inMetadata && event == XmlPullParser.START_TAG && parser.name == "meta") {
                    val name = parser.getAttributeValue(null, "name")
                    val content = parser.getAttributeValue(null, "content")
                    if (name.equals("cover", ignoreCase = true) && !content.isNullOrBlank()) {
                        return content
                    }
                }
                event = parser.next()
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveOpfHref(opfDir: String, href: String): String {
        return if (opfDir.isEmpty()) href else "$opfDir/$href"
    }

    // ── CBZ / ZIP ────────────────────────────────────────────────────────

    private fun extractZipFirstImage(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use zipUse@ { zip ->
                    // 顺序读取，找到第一张图片立即停止，避免读完整个压缩包；同时按缩略图尺寸降采样。
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && isImageExt(entry.name.lowercase())) {
                            return@zipUse decodeSampled(zip, THUMB_MAX_SIZE, THUMB_MAX_SIZE)
                        }
                        entry = zip.nextEntry
                    }
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── CBR / RAR ────────────────────────────────────────────────────────

    private fun extractRarFirstImage(context: Context, uri: Uri): Bitmap? {
        var archive: com.github.junrar.Archive? = null
        var input: InputStream? = null
        return try {
            input = context.contentResolver.openInputStream(uri) ?: return null
            archive = com.github.junrar.Archive(input)
            input = null // Archive 接管流的关闭

            val entries = archive.fileHeaders.sortedBy { it.fileName.lowercase() }
            for (entry in entries) {
                if (entry.isDirectory) continue
                if (!isImageExt(entry.fileName.lowercase())) continue
                return archive.getInputStream(entry).use { decodeSampled(it, THUMB_MAX_SIZE, THUMB_MAX_SIZE) }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { archive?.close() }
            runCatching { input?.close() }
        }
    }

    // ── CB7 / 7Z ─────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun extractSevenZipFirstImage(context: Context, uri: Uri): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var input: java.io.FileInputStream? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            input = java.io.FileInputStream(pfd.fileDescriptor)
            org.apache.commons.compress.archivers.sevenz.SevenZFile(input.channel).use { sz ->
                var entry = sz.nextEntry
                val entries = mutableListOf<org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry>()
                while (entry != null) {
                    if (!entry.isDirectory && isImageExt(entry.name.lowercase())) {
                        entries.add(entry)
                    }
                    entry = sz.nextEntry
                }
                entries.sortBy { it.name.lowercase() }
                val target = entries.firstOrNull() ?: return null
                sz.getInputStream(target).use { decodeSampled(it, THUMB_MAX_SIZE, THUMB_MAX_SIZE) }
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { input?.close() }
            runCatching { pfd?.close() }
        }
    }

    // ── CBT / TAR ────────────────────────────────────────────────────────

    private fun extractTarFirstImage(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(input).use tarUse@ { tar ->
                    // 顺序读取，找到第一张图片立即停止，按缩略图尺寸降采样。
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && isImageExt(entry.name.lowercase())) {
                            return@tarUse decodeSampled(tar, THUMB_MAX_SIZE, THUMB_MAX_SIZE)
                        }
                        entry = tar.nextEntry
                    }
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── MOBI / AZW3 ──────────────────────────────────────────────────────

    /**
     * 从 MOBI/AZW3 文件提取首张图片作为封面。
     * 解析 PDB 头部和 MOBI 头部定位图片 record，读取第一张图片解码。
     */
    private fun extractMobiFirstImage(context: Context, uri: Uri): Bitmap? {
        var raf: java.io.RandomAccessFile? = null
        var tempFile: File? = null
        return try {
            val file: File = if (uri.scheme == "file") {
                uri.path?.let(::File)?.takeIf(File::exists) ?: return null
            } else {
                val temp = File(context.cacheDir, "mobi_thumb_${System.currentTimeMillis()}.mobi")
                tempFile = temp
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                } ?: return null
                temp
            }

            raf = java.io.RandomAccessFile(file, "r")

            // 解析 PDB 头部
            val headerBytes = ByteArray(78)
            raf.seek(0)
            raf.readFully(headerBytes)
            val numRecords = ((headerBytes[76].toInt() and 0xFF) shl 8) or (headerBytes[77].toInt() and 0xFF)
            if (numRecords <= 0 || numRecords > 65535) return null

            val infoBytes = ByteArray(numRecords * 8)
            raf.seek(78)
            raf.readFully(infoBytes)

            val offsets = LongArray(numRecords)
            for (i in 0 until numRecords) {
                val base = i * 8
                offsets[i] = ((infoBytes[base].toLong() and 0xFF) shl 24) or
                        ((infoBytes[base + 1].toLong() and 0xFF) shl 16) or
                        ((infoBytes[base + 2].toLong() and 0xFF) shl 8) or
                        (infoBytes[base + 3].toLong() and 0xFF)
            }

            val fileLength = raf.length()

            // 扫描全部 records 寻找第一张图片
            // 不依赖 firstImageIndex，直接用 magic bytes 检测
            // 同时检查偏移 0/1/2/4 处，兼容对齐填充
            val probeSize = 16
            val probeBuf = ByteArray(probeSize)

            for (i in 0 until numRecords) {
                val recordOffset = offsets[i]
                val nextOffset = if (i + 1 < numRecords) offsets[i + 1] else fileLength
                val recordSize = nextOffset - recordOffset
                if (recordSize < 4 || recordSize > MAX_COVER_ENTRY_BYTES) continue

                raf.seek(recordOffset)
                val read = raf.read(probeBuf)
                if (read < 3) continue

                // 在偏移 0 到 4 范围内检查图片 magic bytes
                var imagePadding = -1
                for (padding in 0..4) {
                    if (padding + 3 > read) break
                    if (isImageMagic(probeBuf, padding, read)) {
                        imagePadding = padding
                        break
                    }
                }
                if (imagePadding < 0) continue

                // 读取图片数据并解码
                val imageOffset = recordOffset + imagePadding
                val imageSize = recordSize - imagePadding
                val readSize = minOf(imageSize, MAX_COVER_ENTRY_BYTES).toInt()
                val imgBytes = ByteArray(readSize)
                raf.seek(imageOffset)
                raf.readFully(imgBytes)
                return BitmapDecoderUtils.decodeSampled(imgBytes, THUMB_MAX_SIZE, THUMB_MAX_SIZE, tag = TAG)
            }

            null
        } catch (e: Exception) {
            Log.w(TAG, "extractMobiFirstImage failed: $uri", e)
            null
        } finally {
            runCatching { raf?.close() }
            tempFile?.let { runCatching { it.delete() } }
        }
    }

    /**
     * 检查缓冲区指定偏移处是否为图片 magic bytes。
     * 支持 JPEG / PNG / GIF / BMP / WebP。
     */
    private fun isImageMagic(buf: ByteArray, offset: Int, limit: Int): Boolean {
        val remaining = limit - offset
        if (remaining < 2) return false
        val b0 = buf[offset].toInt() and 0xFF
        val b1 = buf[offset + 1].toInt() and 0xFF

        // JPEG: FF D8 FF
        if (remaining >= 3 && b0 == 0xFF && b1 == 0xD8 && (buf[offset + 2].toInt() and 0xFF) == 0xFF) return true
        // PNG: 89 50 4E 47
        if (remaining >= 4 && b0 == 0x89 && b1 == 0x50 &&
            (buf[offset + 2].toInt() and 0xFF) == 0x4E && (buf[offset + 3].toInt() and 0xFF) == 0x47) return true
        // GIF: 47 49 46 38
        if (remaining >= 4 && b0 == 0x47 && b1 == 0x49 &&
            (buf[offset + 2].toInt() and 0xFF) == 0x46 && (buf[offset + 3].toInt() and 0xFF) == 0x38) return true
        // BMP: 42 4D
        if (remaining >= 2 && b0 == 0x42 && b1 == 0x4D) return true
        // WebP: 52 49 46 46 .... 57 45 42 50
        if (remaining >= 12 && b0 == 0x52 && b1 == 0x49 &&
            (buf[offset + 2].toInt() and 0xFF) == 0x46 && (buf[offset + 3].toInt() and 0xFF) == 0x46 &&
            (buf[offset + 8].toInt() and 0xFF) == 0x57 && (buf[offset + 9].toInt() and 0xFF) == 0x45 &&
            (buf[offset + 10].toInt() and 0xFF) == 0x42 && (buf[offset + 11].toInt() and 0xFF) == 0x50) return true

        return false
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * 从输入流按目标尺寸采样解码，避免封面原图过大导致 OOM。
     * 读取完成后不会关闭输入流，由调用方管理。
     */
    private fun decodeSampled(input: InputStream, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val bytes = input.readBytesLimited(MAX_COVER_ENTRY_BYTES)
            BitmapDecoderUtils.decodeSampled(bytes, reqWidth, reqHeight, tag = TAG)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "decodeSampled OOM", e)
            null
        } catch (e: java.io.IOException) {
            Log.w(TAG, "decodeSampled 单张封面超过 ${MAX_COVER_ENTRY_BYTES / 1024 / 1024}MB 上限", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "decodeSampled failed", e)
            null
        }
    }

    private fun safeCacheKey(input: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun isImageExt(name: String): Boolean {
        return isImageFile(name)
    }

    private fun scaleDown(src: Bitmap, maxSize: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxSize && h <= maxSize) return src
        val ratio = minOf(maxSize.toFloat() / w, maxSize.toFloat() / h)
        val newW = (w * ratio).toInt().coerceAtLeast(1)
        val newH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    // ── Local file ──────────────────────────────────────────────────────

    /**
     * 从本地图片文件创建缩略图。用于 FOLDER 类型的封面提取。
     */
    fun extractLocalImageThumbnail(context: Context, uri: Uri, maxDimension: Int = THUMB_MAX_SIZE): Uri? {
        return try {
            val cacheKey = safeCacheKey(uri.toString())
            val cacheFile = File(context.cacheDir, "thumb_local_$cacheKey.png")
            if (cacheFile.exists() && cacheFile.length() > 0) {
                return Uri.fromFile(cacheFile)
            }

            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                decodeSampled(stream, maxDimension, maxDimension)
            } ?: return null

            val thumb = scaleDown(bitmap, maxDimension)
            if (thumb !== bitmap && !bitmap.isRecycled) bitmap.recycle()

            FileOutputStream(cacheFile).use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            thumb.recycle()

            if (cacheFile.exists()) {
                // 与 extractCover 一致地按数量上限清理，避免 FOLDER 漫画的本地缩略图无界增长。
                cleanupOldCovers(context)
                Uri.fromFile(cacheFile)
            } else null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "extractLocalImageThumbnail OOM: $uri", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "extractLocalImageThumbnail failed: $uri", e)
            null
        }
    }
}
