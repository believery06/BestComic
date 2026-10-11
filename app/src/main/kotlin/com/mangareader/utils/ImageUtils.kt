package com.mangareader.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object ImageUtils {

    private const val WHITE_THRESHOLD = 235
    private const val EDGE_RATIO = 0.05

    /**
     * 安全创建 Bitmap，优先使用指定 config；OOM 时降级到 RGB_565，仍失败则返回 null。
     * 避免滤镜链路中一张大图分配失败就导致整个阅读器崩溃。
     */
    private fun createBitmapSafe(width: Int, height: Int, preferredConfig: Bitmap.Config): Bitmap? {
        if (width <= 0 || height <= 0) return null
        return try {
            Bitmap.createBitmap(width, height, preferredConfig)
        } catch (_: OutOfMemoryError) {
            if (preferredConfig == Bitmap.Config.RGB_565) null
            else try {
                Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            } catch (_: OutOfMemoryError) {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 按百分比手动裁切四边边距，用于精确切除扫描装订空白或多余边框。
     * [topPct]、[bottomPct]、[leftPct]、[rightPct] 为 0–100 的整数百分比。
     */
    fun cropMargins(
        src: Bitmap,
        topPct: Int = 0,
        bottomPct: Int = 0,
        leftPct: Int = 0,
        rightPct: Int = 0
    ): Bitmap {
        val width = src.width
        val height = src.height
        if (width < 2 || height < 2) return src
        val top = (height * topPct / 100f).toInt().coerceIn(0, height - 1)
        val bottom = (height * bottomPct / 100f).toInt().coerceIn(0, height - 1 - top)
        val left = (width * leftPct / 100f).toInt().coerceIn(0, width - 1)
        val right = (width * rightPct / 100f).toInt().coerceIn(0, width - 1 - left)
        val cropWidth = max(1, width - left - right)
        val cropHeight = max(1, height - top - bottom)
        return safeCreateBitmap(src, left, top, cropWidth, cropHeight)
    }

    // 仅扫描上下左右各 5% 的边缘区域，避免大图的逐像素全扫描
    fun autoCropWhiteBorders(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        if (width < 10 || height < 10) return src

        return try {
            val topRows = (height * EDGE_RATIO).toInt().coerceAtLeast(1)
            val bottomRows = (height * EDGE_RATIO).toInt().coerceAtLeast(1)
            val leftCols = (width * EDGE_RATIO).toInt().coerceAtLeast(1)
            val rightCols = (width * EDGE_RATIO).toInt().coerceAtLeast(1)

            // 读取上下边缘行
            val topBottomPixels = IntArray(width * (topRows + bottomRows))
            src.getPixels(topBottomPixels, 0, width, 0, 0, width, topRows)
            if (bottomRows > 0) {
                src.getPixels(topBottomPixels, width * topRows, width, 0, height - bottomRows, width, bottomRows)
            }

            var top = 0
            var bottom = height - 1
            for (r in 0 until topRows) {
                if (isRowWhite(topBottomPixels, width, r, leftCols, width - 1 - rightCols)) top++ else break
            }
            for (r in 0 until bottomRows) {
                val row = height - 1 - r
                // 底部行在 topBottomPixels 中从 topRows 开始，倒序存放
                val arrayRow = topRows + bottomRows - 1 - r
                if (row > top && isRowWhite(topBottomPixels, width, arrayRow, leftCols, width - 1 - rightCols)) bottom-- else break
            }

            // 读取左右边缘列
            val leftPixels = IntArray(leftCols * height)
            val rightPixels = IntArray(rightCols * height)
            src.getPixels(leftPixels, 0, leftCols, 0, 0, leftCols, height)
            if (rightCols > 0) {
                src.getPixels(rightPixels, 0, rightCols, width - rightCols, 0, rightCols, height)
            }

            var left = 0
            var right = width - 1
            for (c in 0 until leftCols) {
                if (isColumnWhite(leftPixels, leftCols, c, top, bottom)) left++ else break
            }
            for (c in 0 until rightCols) {
                val col = width - 1 - c
                if (col < left) break
                if (isColumnWhite(rightPixels, rightCols, c, top, bottom)) right-- else break
            }

            val margin = 2
            top = (top - margin).coerceAtLeast(0)
            bottom = (bottom + margin).coerceAtMost(height - 1)
            left = (left - margin).coerceAtLeast(0)
            right = (right + margin).coerceAtMost(width - 1)

            if (top <= 1 && bottom >= height - 2 && left <= 1 && right >= width - 2) {
                return src
            }

            val cropWidth = max(1, right - left + 1)
            val cropHeight = max(1, bottom - top + 1)

            safeCreateBitmap(src, left, top, cropWidth, cropHeight)
        } catch (e: OutOfMemoryError) {
            src
        } catch (e: Exception) {
            src
        }
    }

    private fun isRowWhite(pixels: IntArray, width: Int, row: Int, startCol: Int, endCol: Int): Boolean {
        val s = row * width + startCol.coerceIn(0, width - 1)
        val e = row * width + endCol.coerceIn(0, width - 1)
        val lo = min(s, e)
        val hi = max(s, e)
        var whiteCount = 0
        val total = hi - lo + 1
        for (x in lo..hi) {
            if (isWhite(pixels[x])) whiteCount++
        }
        return total > 0 && whiteCount.toFloat() / total >= 0.95f
    }

    private fun isColumnWhite(pixels: IntArray, width: Int, col: Int, top: Int, bottom: Int): Boolean {
        var whiteCount = 0
        var total = 0
        val c = col.coerceIn(0, width - 1)
        val lo = min(top, bottom)
        val hi = max(top, bottom)
        for (y in lo..hi) {
            val idx = y * width + c
            if (idx in pixels.indices) {
                total++
                if (isWhite(pixels[idx])) whiteCount++
            }
        }
        return total > 0 && whiteCount.toFloat() / total >= 0.95f
    }

    fun isWhite(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return r >= WHITE_THRESHOLD && g >= WHITE_THRESHOLD && b >= WHITE_THRESHOLD
    }

    private fun safeCreateBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap {
        return try {
            Bitmap.createBitmap(src, x, y, w, h)
        } catch (e: OutOfMemoryError) {
            src
        } catch (e: Exception) {
            src
        }
    }

    fun scaleForScreen(src: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (src.width == targetWidth && src.height == targetHeight) return src
        return try {
            Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true)
        } catch (e: Throwable) {
            src
        }
    }

    fun applyFilters(
        src: Bitmap,
        brightness: Float = 1f,
        contrast: Float = 1f,
        gamma: Float = 1f,
        saturation: Float = 1f,
        grayscale: Boolean = false,
        mirror: Boolean = false,
        rotation: Int = 0
    ): Bitmap {
        val width = src.width
        val height = src.height
        if (width <= 0 || height <= 0) return src

        // 无任何图像变换时直接返回原图，避免为阅读器主页/默认状态分配额外 Bitmap。
        if (brightness == 1f && contrast == 1f && gamma == 1f && saturation == 1f &&
            !grayscale && !mirror && rotation == 0
        ) {
            return src
        }

        return try {
            val result = createBitmapSafe(width, height, Bitmap.Config.ARGB_8888) ?: return src
            val canvas = Canvas(result)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            val colorMatrix = ColorMatrix()

            if (brightness != 1f || contrast != 1f || gamma != 1f) {
                val cm = ColorMatrix()
                if (brightness != 1f) {
                    cm.set(
                        floatArrayOf(
                            brightness, 0f, 0f, 0f, 0f,
                            0f, brightness, 0f, 0f, 0f,
                            0f, 0f, brightness, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                    colorMatrix.postConcat(cm)
                }
                if (contrast != 1f) {
                    val scale = contrast
                    val translate = (-0.5f * scale + 0.5f) * 255f
                    cm.set(
                        floatArrayOf(
                            scale, 0f, 0f, 0f, translate,
                            0f, scale, 0f, 0f, translate,
                            0f, 0f, scale, 0f, translate,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                    colorMatrix.postConcat(cm)
                }
            }

            if (saturation != 1f) {
                val cm = ColorMatrix()
                cm.setSaturation(saturation)
                colorMatrix.postConcat(cm)
            }

            if (grayscale) {
                val cm = ColorMatrix()
                cm.setSaturation(0f)
                colorMatrix.postConcat(cm)
            }

            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)

            val matrix = Matrix()
            if (mirror) {
                matrix.postScale(-1f, 1f)
                matrix.postTranslate(width.toFloat(), 0f)
            }
            if (rotation != 0) {
                matrix.postRotate(rotation.toFloat())
                if (rotation == 90 || rotation == 270) {
                    matrix.postTranslate(
                        if (rotation == 90) width.toFloat() else 0f,
                        if (rotation == 270) height.toFloat() else 0f
                    )
                }
            }

            canvas.drawBitmap(src, matrix, paint)
            // ColorMatrix 无法直接表达 gamma，需通过像素级 LUT 后处理；仅开启时执行。
            if (gamma != 1f) {
                applyGammaLutInPlace(result, gamma)
            }
            result
        } catch (e: Throwable) {
            // 兜底：OOM、Skia 异常、参数非法等均返回原图，避免阅读器崩溃。
            src
        }
    }

    /**
     * 对位图原地应用 gamma LUT，避免额外分配一张新位图。
     * 仅处理 RGB 通道，保留 Alpha。
     */
    private fun applyGammaLutInPlace(bitmap: Bitmap, gamma: Float) {
        if (gamma == 1f) return
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return
        try {
            val invGamma = 1f / gamma
            val lut = FloatArray(256) { i ->
                (255f * (i / 255f).toDouble().pow(invGamma.toDouble())).toFloat().coerceIn(0f, 255f)
            }
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val nr = lut[r].toInt()
                val ng = lut[g].toInt()
                val nb = lut[b].toInt()
                pixels[i] = (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        } catch (_: Throwable) {
            // 原地 gamma 失败时不影响原图显示
        }
    }

    fun applyAdjustments(
        src: Bitmap,
        brightness: Float = 1f,
        contrast: Float = 1f,
        rotation: Int = 0,
        grayscale: Boolean = false,
        sharpen: Boolean = false,
        gamma: Float = 1f,
        denoise: Boolean = false,
        saturation: Float = 1f,
        nightMode: Boolean = false,
        eyeCare: Boolean = false,
        sharpenStrength: Float = 1f,
        denoiseStrength: Float = 1f,
        mirror: Boolean = false
    ): Bitmap {
        var result = applyFilters(
            src = src,
            brightness = brightness,
            contrast = contrast,
            gamma = gamma,
            saturation = saturation,
            grayscale = grayscale,
            mirror = mirror,
            rotation = rotation
        )
        // 滤镜链路中会产生多个中间 Bitmap；在获得下一步输出后及时回收中间结果，
        // 但绝不应回收原始 src（可能来自页面缓存或 Coil，生命周期由调用方管理）。
        fun advance(next: (Bitmap) -> Bitmap): Bitmap {
            val out = next(result)
            if (out !== result && result !== src) {
                runCatching { if (!result.isRecycled) result.recycle() }
            }
            return out
        }

        if (nightMode || eyeCare) {
            val tintColor = if (nightMode) 0x80FF8800.toInt() else 0x80FFFF88.toInt()
            result = advance { tintBitmap(it, tintColor) }
        }
        if (sharpen && sharpenStrength > 0f) {
            result = advance { sharpenBitmap(it, sharpenStrength) }
        }
        if (denoise && denoiseStrength > 0f) {
            result = advance { denoiseBitmap(it, denoiseStrength) }
        }
        return result
    }

    private fun tintBitmap(src: Bitmap, tintColor: Int): Bitmap {
        val width = src.width
        val height = src.height
        return try {
            val result = createBitmapSafe(width, height, Bitmap.Config.ARGB_8888) ?: return src
            val canvas = Canvas(result)
            canvas.drawBitmap(src, 0f, 0f, null)
            val paint = Paint().apply {
                colorFilter = android.graphics.PorterDuffColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            result
        } catch (e: Throwable) {
            src
        }
    }

    private fun sharpenBitmap(src: Bitmap, strength: Float): Bitmap {
        val width = src.width
        val height = src.height
        if (width <= 0 || height <= 0) return src
        val s = strength.coerceIn(0f, 3f)
        if (s <= 0f) return src
        // 真正的 3x3 锐化卷积：中心 1+4s、四邻域 -s（拉普拉斯锐化）。
        // 旧实现用 ColorMatrix 跨通道负系数，只产生偏色而非锐化。
        return try {
            val pixels = IntArray(width * height)
            src.getPixels(pixels, 0, width, 0, 0, width, height)
            val outPixels = IntArray(pixels.size)
            val centerWeight = 1f + 4f * s

            for (y in 0 until height) {
                val rowOffset = y * width
                val yUp = if (y > 0) -width else 0
                val yDown = if (y < height - 1) width else 0
                for (x in 0 until width) {
                    val idx = rowOffset + x
                    val center = pixels[idx]
                    if (x == 0 || x == width - 1 || y == 0 || y == height - 1) {
                        // 边缘像素不做卷积，避免越界
                        outPixels[idx] = center
                        continue
                    }
                    val top = pixels[idx + yUp]
                    val bottom = pixels[idx + yDown]
                    val left = pixels[idx - 1]
                    val right = pixels[idx + 1]
                    val a = (center ushr 24) and 0xFF
                    val rr = clampChannel(
                        (((center shr 16) and 0xFF) * centerWeight) -
                                ((((top shr 16) and 0xFF) + ((bottom shr 16) and 0xFF) +
                                        ((left shr 16) and 0xFF) + ((right shr 16) and 0xFF)) * s)
                    )
                    val gg = clampChannel(
                        (((center shr 8) and 0xFF) * centerWeight) -
                                ((((top shr 8) and 0xFF) + ((bottom shr 8) and 0xFF) +
                                        ((left shr 8) and 0xFF) + ((right shr 8) and 0xFF)) * s)
                    )
                    val bb = clampChannel(
                        ((center and 0xFF) * centerWeight) -
                                ((((top and 0xFF) + (bottom and 0xFF) +
                                        (left and 0xFF) + (right and 0xFF))) * s)
                    )
                    outPixels[idx] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
            val result = createBitmapSafe(width, height, Bitmap.Config.ARGB_8888) ?: return src
            result.setPixels(outPixels, 0, width, 0, 0, width, height)
            result
        } catch (e: Throwable) {
            src
        }
    }

    private fun clampChannel(v: Float): Int =
        if (v < 0f) 0 else if (v > 255f) 255 else v.toInt()

    private fun denoiseBitmap(src: Bitmap, strength: Float): Bitmap {
        val width = src.width
        val height = src.height
        if (width <= 0 || height <= 0) return src
        return fastBlur(src, strength.coerceIn(1f, 10f).toInt())
    }

    private fun fastBlur(src: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceAtLeast(1)
        if (r <= 0) return src
        val srcWidth = src.width
        val srcHeight = src.height
        if (srcWidth <= 1 || srcHeight <= 1) return src
        var b = src
        repeat(min(r, 5)) {
            val w = b.width
            val h = b.height
            if (w <= 2 || h <= 2) return@repeat
            val out = createBitmapSafe(max(1, w / 2), max(1, h / 2), Bitmap.Config.ARGB_8888) ?: return b
            val canvas = Canvas(out)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val matrix = Matrix().apply { postScale(0.5f, 0.5f) }
            canvas.drawBitmap(b, matrix, paint)
            // 只回收 fastBlur 自己产生的中间 Bitmap，绝不回收调用方传入的原始 src。
            if (b !== src && !b.isRecycled) runCatching { b.recycle() }
            b = out
        }
        if (b === src) return src
        // 降采样模糊后必须放大回原始尺寸，否则开启降噪的页面会越变越小。
        return try {
            val upscaled = Bitmap.createScaledBitmap(b, srcWidth, srcHeight, true)
            if (!b.isRecycled) runCatching { b.recycle() }
            upscaled
        } catch (e: Throwable) {
            // 放大失败时返回原图，绝不能返回缩小图造成页面尺寸错乱。
            if (!b.isRecycled) runCatching { b.recycle() }
            src
        }
    }

    fun createShareCard(src: Bitmap, comicTitle: String, pageLabel: String, appName: String): Bitmap? {
        return try {
            val cardWidth = 800
            val cardHeight = 1200
            val srcAspect = src.width.toFloat() / src.height.toFloat()
            val targetAspect = cardWidth.toFloat() / cardHeight
            val drawWidth: Int
            val drawHeight: Int
            if (srcAspect > targetAspect) {
                drawWidth = cardWidth
                drawHeight = (cardWidth / srcAspect).toInt()
            } else {
                drawHeight = cardHeight
                drawWidth = (cardHeight * srcAspect).toInt()
            }
            val result = Bitmap.createBitmap(cardWidth, cardHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(0xFF121212.toInt())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val dstRect = android.graphics.Rect(
                (cardWidth - drawWidth) / 2,
                (cardHeight - drawHeight) / 2,
                (cardWidth + drawWidth) / 2,
                (cardHeight + drawHeight) / 2
            )
            canvas.drawBitmap(src, null, dstRect, paint)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 32f
            }
            canvas.drawText(comicTitle, 30f, 50f, textPaint)
            canvas.drawText(pageLabel, 30f, 90f, textPaint)
            textPaint.textSize = 24f
            canvas.drawText(appName, 3f, cardHeight - 20f, textPaint)
            result
        } catch (e: Exception) {
            null
        }
    }
}
