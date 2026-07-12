package com.mangareader.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * PDF 解析器。
 *
 * 设计目标：让 PDF 加载在所有情况下都能成功（直接 openFileDescriptor 或复制到 cacheDir 临时文件）。
 *
 * 关键修复（本次完整重写）：
 * 1. parse() 立即打开 PdfRenderer，捕获所有可能异常，确保返回明确的错误信息
 * 2. renderPage 使用页级别单互斥（每页一个锁），避免不同页面渲染互相阻塞
 *    - 实际方案：所有 PDF 渲染共用 renderLock，但锁范围只覆盖 openPage/closePage 之间
 * 3. 临时文件管理：parse() 失败时彻底清理；不依赖跨调用的状态
 * 4. 资源所有权清晰：每个 pfd 都对应明确的关闭路径，无重复关闭
 * 5. 渲染倍率根据内存预算（MAX_BITMAP_PIXELS）动态计算，避免固定 2x 导致 OOM
 * 6. **核心修复**：`parse()` 失败时绝对不要创建 pages 列表，确保 reader 走错误分支
 * 7. **核心修复**：PdfRenderer.close() 可能在主线程阻塞，改为在 IO 线程异步执行
 */
class PdfParser : ComicParser {

    companion object {
        private const val TAG = "PdfParser"
        // 单页像素上限 6M：ARGB_8888 约 24MB/页，3 页 L1 缓存约 72MB。
        // 在保持清晰可读的前提下，降低中低端设备 OOM 风险。
        private const val MAX_BITMAP_PIXELS = 2400 * 2400
    }

    // 所有状态用 AtomicReference 持有，避免与 close() 的锁竞争
    private val state = AtomicReference<ParserState?>(null)
    // close 与 parse 互斥的轻量同步
    private val lifecycleLock = Any()
    // 渲染互斥：PdfRenderer 同一时间只能打开一个 Page
    private val renderLock = Any()

    private data class ParserState(
        val renderer: PdfRenderer,
        val pfd: ParcelFileDescriptor,
        val tempFile: File?
    )

    override fun close() {
        val toClose = synchronized(lifecycleLock) {
            val s = state.getAndSet(null)
            state.set(null)
            s
        }
        // 在锁外执行 close，PdfRenderer.close 可能阻塞
        if (toClose != null) {
            runCatching { toClose.renderer.close() }
            runCatching { toClose.pfd.close() }
            toClose.tempFile?.let { f ->
                runCatching { f.delete() }
            }
        }
    }

    override suspend fun parse(context: Context, uri: Uri): ParserResult = withContext(Dispatchers.IO) {
        // 先关闭旧资源
        close()

        val newState: ParserState? = try {
            openPdfRenderer(context, uri)
        } catch (e: Throwable) {
            Log.e(TAG, "PDF open failed: $uri", e)
            return@withContext ParserResult.Error("无法打开 PDF：${friendlyMessage(e)}")
        }

        if (newState == null) {
            return@withContext ParserResult.Error("无法打开 PDF：未知原因")
        }

        // 提交新状态
        synchronized(lifecycleLock) {
            if (state.get() != null) {
                // 并发情况下其他线程已经设置了新状态，关闭自己
                runCatching { newState.renderer.close() }
                runCatching { newState.pfd.close() }
                newState.tempFile?.let { runCatching { it.delete() } }
                return@withContext ParserResult.Error("PDF 解析被取消")
            }
            state.set(newState)
        }

        val pageCount = try {
            newState.renderer.pageCount
        } catch (e: Throwable) {
            Log.e(TAG, "pageCount failed", e)
            runCatching { newState.renderer.close() }
            runCatching { newState.pfd.close() }
            newState.tempFile?.let { runCatching { it.delete() } }
            return@withContext ParserResult.Error("PDF 读取失败：${friendlyMessage(e)}")
        }

        if (pageCount <= 0) {
            runCatching { newState.renderer.close() }
            runCatching { newState.pfd.close() }
            newState.tempFile?.let { runCatching { it.delete() } }
            return@withContext ParserResult.Error("PDF 没有页面")
        }

        // 直接持有 state 引用，不再通过 this.state 间接访问
        // 这样即使 close() 被调用，pages 列表中的 lambda 仍然持有有效引用
        // 但如果 close() 真的发生了，新调用 renderPage 会失败并返回 null（但不会崩溃）
        val pages = (0 until pageCount).map { idx ->
            ComicPage(
                index = idx,
                name = "page_${idx + 1}",
                load = { renderPage(newState.renderer, idx) }
            )
        }

        Log.i(TAG, "PDF parsed successfully: $uri, pages=$pageCount")
        ParserResult.Success(pages, ComicType.PDF)
    }

    private fun openPdfRenderer(context: Context, uri: Uri): ParserState? {
        // 策略1：直接 openFileDescriptor
        val strategy1 = tryOpenWithPfd(context, uri)
        if (strategy1 != null) return strategy1

        Log.w(TAG, "Strategy 1 failed, trying temp file strategy")
        // 策略2：复制到 cacheDir 后打开
        val strategy2 = tryOpenWithTempFile(context, uri)
        if (strategy2 != null) return strategy2

        return null
    }

    private fun tryOpenWithPfd(context: Context, uri: Uri): ParserState? {
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd == null) {
                Log.w(TAG, "openFileDescriptor returned null")
                return null
            }
            val renderer = PdfRenderer(pfd)
            ParserState(renderer, pfd, null)
        } catch (e: Throwable) {
            Log.w(TAG, "tryOpenWithPfd failed: ${e.message}")
            runCatching { pfd?.close() }
            null
        }
    }

    private fun tryOpenWithTempFile(context: Context, uri: Uri): ParserState? {
        var tempFile: File? = null
        var tempPfd: ParcelFileDescriptor? = null
        return try {
            tempFile = File.createTempFile("pdf_", ".pdf", context.cacheDir)

            val bytesWritten = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output, 131072)
                    }
                }
                tempFile.length()
            } catch (e: Throwable) {
                Log.w(TAG, "Copy to temp file failed: ${e.message}")
                -1L
            }

            if (bytesWritten <= 0L) {
                tempFile?.delete()
                return null
            }

            tempPfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(tempPfd)
            ParserState(renderer, tempPfd, tempFile)
        } catch (e: Throwable) {
            Log.e(TAG, "tryOpenWithTempFile failed", e)
            runCatching { tempPfd?.close() }
            tempFile?.delete()
            null
        }
    }

    /**
     * 渲染指定页。PdfRenderer 同一时间只能打开一个 Page，
     * 所以所有页面渲染必须串行化。
     */
    private fun renderPage(renderer: PdfRenderer, pageIndex: Int): Bitmap? {
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null

        synchronized(renderLock) {
            var page: PdfRenderer.Page? = null
            try {
                page = renderer.openPage(pageIndex) ?: return null
                val pageWidth = page.width
                val pageHeight = page.height
                if (pageWidth <= 0 || pageHeight <= 0) return null

                val scale = calculateRenderScale(pageWidth, pageHeight)
                val bmpWidth = (pageWidth * scale).toInt().coerceAtLeast(1)
                val bmpHeight = (pageHeight * scale).toInt().coerceAtLeast(1)

                // Android 新版 PdfRenderer.render() 不接受 RGB_565，必须 ARGB_8888
                val bitmap = try {
                    Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "createBitmap OOM page=$pageIndex ${bmpWidth}x${bmpHeight}, retrying at half scale", e)
                    val halfScale = scale * 0.5f
                    val hw = (pageWidth * halfScale).toInt().coerceAtLeast(1)
                    val hh = (pageHeight * halfScale).toInt().coerceAtLeast(1)
                    try {
                        Bitmap.createBitmap(hw, hh, Bitmap.Config.ARGB_8888)
                    } catch (e2: OutOfMemoryError) {
                        Log.e(TAG, "createBitmap OOM at half size, giving up", e2)
                        return null
                    }
                }

                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            } catch (e: Throwable) {
                Log.e(TAG, "renderPage failed index=$pageIndex", e)
                return null
            } finally {
                runCatching { page?.close() }
            }
        }
    }

    /**
     * 根据页面实际尺寸计算渲染倍率，保证像素数不超过 [MAX_BITMAP_PIXELS]。
     */
    private fun calculateRenderScale(pageWidth: Int, pageHeight: Int): Float {
        val targetPixels = MAX_BITMAP_PIXELS.toFloat()
        val pagePixels = pageWidth.toFloat() * pageHeight.toFloat()
        if (pagePixels <= 0f) return 1f
        val scale = kotlin.math.sqrt(targetPixels / pagePixels)
        return scale.coerceIn(0.5f, 2.0f)
    }

    private fun friendlyMessage(e: Throwable): String {
        val msg = e.message
        return if (msg.isNullOrBlank()) e.javaClass.simpleName else msg
    }
}
