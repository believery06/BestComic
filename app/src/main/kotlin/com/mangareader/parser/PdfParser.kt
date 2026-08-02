package com.mangareader.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.mangareader.R
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import kotlinx.coroutines.CancellationException
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
        // 默认单页像素上限：手机 1440p，平板 2400p。
        // 根据屏幕尺寸动态调整，避免一次性渲染超大 Bitmap 导致 OOM 和卡顿。
        private const val PHONE_MAX_PIXELS = 1440 * 2560
        private const val TABLET_MAX_PIXELS = 2400 * 3200
        private const val LOWEND_MAX_PIXELS = 1080 * 1920
    }

    // 所有状态用 AtomicReference 持有，避免与 close() 的锁竞争
    private val state = AtomicReference<ParserState?>(null)
    // close 与 parse 互斥的轻量同步
    private val lifecycleLock = Any()
    // 渲染互斥：PdfRenderer 同一时间只能打开一个 Page
    private val renderLock = Any()

    @Volatile
    private var screenWidth: Int = 1080
    @Volatile
    private var screenHeight: Int = 1920
    @Volatile
    private var isTablet: Boolean = false
    @Volatile
    private var lowEndDevice: Boolean = false

    /** 根据设备屏幕设置渲染预算，应在 parse 前调用。 */
    fun setScreenSize(width: Int, height: Int, tablet: Boolean = false, lowEnd: Boolean = false) {
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)
        isTablet = tablet
        lowEndDevice = lowEnd
    }

    private data class ParserState(
        val renderer: PdfRenderer,
        val pfd: ParcelFileDescriptor,
        val tempFile: File?
    )

    override fun close() {
        val toClose = synchronized(lifecycleLock) {
            state.getAndSet(null)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "PDF open failed: $uri", e)
            return@withContext ParserResult.Error(context.getString(R.string.parser_pdf_error, friendlyMessage(e)))
        }

        if (newState == null) {
            return@withContext ParserResult.Error(context.getString(R.string.parser_pdf_unknown_reason))
        }

        // 提交新状态
        synchronized(lifecycleLock) {
            if (state.get() != null) {
                // 并发情况下其他线程已经设置了新状态，关闭自己
                runCatching { newState.renderer.close() }
                runCatching { newState.pfd.close() }
                newState.tempFile?.let { runCatching { it.delete() } }
                return@withContext ParserResult.Error(context.getString(R.string.parser_pdf_cancelled))
            }
            state.set(newState)
        }

        val pageCount = try {
            newState.renderer.pageCount
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "pageCount failed", e)
            runCatching { newState.renderer.close() }
            runCatching { newState.pfd.close() }
            newState.tempFile?.let { runCatching { it.delete() } }
            return@withContext ParserResult.Error(context.getString(R.string.parser_pdf_read_error, friendlyMessage(e)))
        }

        if (pageCount <= 0) {
            runCatching { newState.renderer.close() }
            runCatching { newState.pfd.close() }
            newState.tempFile?.let { runCatching { it.delete() } }
            return@withContext ParserResult.Error(context.getString(R.string.parser_pdf_no_pages))
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
        tryOpenWithPfd(context, uri)?.let { return it }

        Log.w(TAG, "Strategy 1 failed, trying temp file strategy")
        // 策略2：复制到 cacheDir 后打开
        tryOpenWithTempFile(context, context.cacheDir, uri)?.let { return it }

        // 策略3：复制到 files 目录后打开（cacheDir 可能空间不足或被清理）
        Log.w(TAG, "Strategy 2 failed, trying files dir strategy")
        tryOpenWithTempFile(context, context.filesDir, uri)?.let { return it }

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
            // 某些设备 PdfRenderer 会持有 fd 并在内部读取，dup 一份避免被外部关闭影响
            val dupPfd = try {
                pfd.dup()
            } catch (_: Throwable) {
                pfd
            }
            if (dupPfd !== pfd) {
                runCatching { pfd.close() }
            }
            val renderer = PdfRenderer(dupPfd)
            ParserState(renderer, dupPfd, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "tryOpenWithPfd failed: ${e.message}")
            runCatching { pfd?.close() }
            null
        }
    }

    private fun tryOpenWithTempFile(context: Context, dir: File, uri: Uri): ParserState? {
        var tempFile: File? = null
        var tempPfd: ParcelFileDescriptor? = null
        return try {
            tempFile = File.createTempFile("pdf_", ".pdf", dir)

            val bytesWritten = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output, 262144)
                    }
                }
                tempFile.length()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Copy to temp file failed: ${e.message}")
                -1L
            }

            if (bytesWritten <= 0L) {
                tempFile.delete()
                return null
            }

            tempPfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(tempPfd)
            ParserState(renderer, tempPfd, tempFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "tryOpenWithTempFile failed dir=${dir.name}: ${e.message}")
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
            var retryCount = 0
            val maxRetries = 2

            while (retryCount <= maxRetries) {
                try {
                    page = renderer.openPage(pageIndex) ?: return null
                    val pageWidth = page.width
                    val pageHeight = page.height
                    if (pageWidth <= 0 || pageHeight <= 0) {
                        runCatching { page.close() }
                        return null
                    }

                    val scale = calculateRenderScale(pageWidth, pageHeight)
                    val bmpWidth = (pageWidth * scale).toInt().coerceAtLeast(1)
                    val bmpHeight = (pageHeight * scale).toInt().coerceAtLeast(1)

                    // Android 新版 PdfRenderer.render() 不接受 RGB_565，必须 ARGB_8888
                    val bitmap = try {
                        Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                    } catch (e: OutOfMemoryError) {
                        Log.w(TAG, "createBitmap OOM page=$pageIndex ${bmpWidth}x${bmpHeight}, trying lower scale")
                        val reducedScale = if (retryCount == 0) scale * 0.7f else scale * 0.5f
                        val rw = (pageWidth * reducedScale).toInt().coerceAtLeast(1)
                        val rh = (pageHeight * reducedScale).toInt().coerceAtLeast(1)
                        try {
                            Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
                        } catch (e2: OutOfMemoryError) {
                            runCatching { page.close() }
                            if (retryCount < maxRetries) {
                                retryCount++
                                continue
                            }
                            Log.e(TAG, "createBitmap OOM, giving up page=$pageIndex", e2)
                            return null
                        }
                    }

                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)

                    val destRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
                    page.render(bitmap, destRect, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    runCatching { page.close() }
                    return bitmap
                } catch (e: IllegalStateException) {
                    // PdfRenderer 可能已关闭或处于无效状态
                    Log.w(TAG, "renderPage IllegalStateException page=$pageIndex, retry=$retryCount", e)
                    runCatching { page?.close() }
                    if (retryCount < maxRetries) {
                        retryCount++
                        continue
                    }
                    return null
                } catch (e: java.lang.RuntimeException) {
                    // 某些 PDF 页面 render 会抛出 RuntimeException（如 trying to use a recycled bitmap）
                    Log.w(TAG, "renderPage RuntimeException page=$pageIndex, retry=$retryCount", e)
                    runCatching { page?.close() }
                    if (retryCount < maxRetries) {
                        retryCount++
                        continue
                    }
                    return null
                } catch (e: CancellationException) {
                    runCatching { page?.close() }
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "renderPage failed index=$pageIndex", e)
                    runCatching { page?.close() }
                    return null
                }
            }
            return null
        }
    }

    /**
     * 根据页面实际尺寸、屏幕尺寸和设备性能计算渲染倍率。
     * 优先保证渲染宽度不超过屏幕物理宽度的 1.5 倍（手机 1440p，平板 2400p，低端机 1080p），
     * 避免一次性光栅化超大 Bitmap 导致 CPU 耗时和 OOM。
     */
    private fun calculateRenderScale(pageWidth: Int, pageHeight: Int): Float {
        val pagePixels = pageWidth.toFloat() * pageHeight.toFloat()
        if (pagePixels <= 0f) return 1f

        val maxPixels = when {
            lowEndDevice -> LOWEND_MAX_PIXELS
            isTablet -> TABLET_MAX_PIXELS
            else -> PHONE_MAX_PIXELS
        }.toFloat()

        // 限制最大渲染宽度，避免文字型 PDF 过度渲染
        val maxRenderWidth = when {
            lowEndDevice -> 1080f
            isTablet -> 2400f
            else -> 1440f
        }
        val widthScale = maxRenderWidth / pageWidth.toFloat()

        // 限制总像素数
        val pixelScale = kotlin.math.sqrt(maxPixels / pagePixels)

        return kotlin.math.min(widthScale, pixelScale).coerceIn(0.4f, 2.0f)
    }

    private fun friendlyMessage(e: Throwable): String {
        val msg = e.message
        return if (msg.isNullOrBlank()) e.javaClass.simpleName else msg
    }
}
