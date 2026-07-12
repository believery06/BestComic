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
import com.mangareader.parser.ParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

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
            else -> null
        } ?: return@withContext null

        val thumb = scaleDown(bitmap, THUMB_MAX_SIZE)
        if (thumb !== bitmap && !bitmap.isRecycled) bitmap.recycle()

        FileOutputStream(cacheFile).use { out ->
            thumb.compress(Bitmap.CompressFormat.PNG, 85, out)
        }
        thumb.recycle()

        if (cacheFile.exists()) Uri.fromFile(cacheFile) else null
    }

    // ── PDF ──────────────────────────────────────────────────────────────

    private fun extractPdfFirstPage(context: Context, uri: Uri): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Cannot open PDF")
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount <= 0) return null
            renderer.openPage(0).use { page ->
                val scale = (THUMB_MAX_SIZE.toFloat() / maxOf(page.width, page.height)).coerceAtMost(1f)
                // Android 新版 PdfRenderer.render 只接受 ARGB_8888
                val bmp = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bmp)
                canvas.drawColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        } catch (e: Exception) {
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
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    // 顺序读取，找到第一张图片立即停止，避免读完整个 EPUB
                    val found = mutableListOf<Pair<String, ByteArray>>()
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.lowercase()
                        if (isImageExt(name)) {
                            found.add(entry.name to zip.readBytes())
                            break
                        }
                        entry = zip.nextEntry
                    }
                    found.firstOrNull()?.let { (_, data) ->
                        BitmapFactory.decodeByteArray(data, 0, data.size)
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── CBZ / ZIP ────────────────────────────────────────────────────────

    private fun extractZipFirstImage(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    // 顺序读取，找到第一张图片立即停止，避免读完整个压缩包
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && isImageExt(entry.name.lowercase())) {
                            val data = zip.readBytes()
                            return@use BitmapFactory.decodeByteArray(data, 0, data.size)
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
        return try {
            archive = com.github.junrar.Archive(
                context.contentResolver.openInputStream(uri) ?: return null
            )

            val entries = archive.fileHeaders.sortedBy { it.fileName.lowercase() }
            for (entry in entries) {
                if (entry.isDirectory) continue
                if (!isImageExt(entry.fileName.lowercase())) continue
                return archive.getInputStream(entry).use(BitmapFactory::decodeStream)
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { archive?.close() }
        }
    }

    // ── CB7 / 7Z ─────────────────────────────────────────────────────────

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
                sz.getInputStream(target).use(BitmapFactory::decodeStream)
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
                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(input).use { tar ->
                    // 顺序读取，找到第一张图片立即停止
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && isImageExt(entry.name.lowercase())) {
                            return@use BitmapFactory.decodeStream(tar)
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

    // ── helpers ──────────────────────────────────────────────────────────

    private fun safeCacheKey(input: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun isImageExt(name: String): Boolean {
        return ParserFactory.isImageFile(name)
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

}
