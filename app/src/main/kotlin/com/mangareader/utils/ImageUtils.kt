package com.mangareader.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.pow

object ImageUtils {

    private const val WHITE_THRESHOLD = 235
    private const val MAX_BITMAP_DIMENSION = 4096

    fun autoCropWhiteBorders(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        if (width < 10 || height < 10) return src

        return try {
            val pixels = IntArray(width * height)
            src.getPixels(pixels, 0, width, 0, 0, width, height)

            var top = 0
            var bottom = height - 1
            var left = 0
            var right = width - 1

            while (top < height && isRowWhite(pixels, width, top, 0.95f)) top++
            while (bottom > top && isRowWhite(pixels, width, bottom, 0.95f)) bottom--
            while (left < width && isColumnWhite(pixels, width, height, left, top, bottom, 0.95f)) left++
            while (right > left && isColumnWhite(pixels, width, height, right, top, bottom, 0.95f)) right--

            val margin = 2
            top = max(0, top - margin)
            bottom = min(height - 1, bottom + margin)
            left = max(0, left - margin)
            right = min(width - 1, right + margin)

            if (top <= 1 && bottom >= height - 2 && left <= 1 && right >= width - 2) {
                return src
            }

            val cropWidth = max(1, right - left + 1)
            val cropHeight = max(1, bottom - top + 1)

            safeCreateBitmap(src, left, top, cropWidth, cropHeight)
        } catch (e: OutOfMemoryError) {
            System.gc()
            src
        } catch (e: Exception) {
            src
        }
    }

    private fun isRowWhite(pixels: IntArray, width: Int, row: Int, threshold: Float = 0.98f): Boolean {
        val start = row * width
        val end = min(start + width, pixels.size)
        var whiteCount = 0
        val total = end - start
        for (x in start until end) {
            if (isWhite(pixels[x])) whiteCount++
        }
        return total > 0 && whiteCount.toFloat() / total >= threshold
    }

    private fun isColumnWhite(pixels: IntArray, width: Int, height: Int, col: Int, top: Int = 0, bottom: Int = height - 1, threshold: Float = 0.98f): Boolean {
        var whiteCount = 0
        var total = 0
        for (y in top..bottom) {
            val idx = y * width + col
            if (idx in pixels.indices) {
                total++
                if (isWhite(pixels[idx])) whiteCount++
            }
        }
        return total > 0 && whiteCount.toFloat() / total >= threshold
    }

    private fun isWhite(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val a = (color shr 24) and 0xFF
        if (a < 128) return true
        return r >= WHITE_THRESHOLD && g >= WHITE_THRESHOLD && b >= WHITE_THRESHOLD
    }

    fun scaleBitmap(src: Bitmap, newWidth: Int, newHeight: Int, filter: com.mangareader.data.ScaleFilter): Bitmap {
        if (newWidth <= 0 || newHeight <= 0) return src
        if (newWidth == src.width && newHeight == src.height) return src
        return try {
            Bitmap.createScaledBitmap(src, newWidth, newHeight, true)
        } catch (e: OutOfMemoryError) {
            System.gc()
            try {
                val config = Bitmap.Config.RGB_565
                val tmp = src.copy(config, false)
                val result = Bitmap.createScaledBitmap(tmp, newWidth, newHeight, true)
                tmp.recycle()
                result
            } catch (e2: Exception) {
                src
            }
        }
    }

    private fun safeCreateBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap {
        return try {
            Bitmap.createBitmap(src, x, y, w, h)
        } catch (e: OutOfMemoryError) {
            System.gc()
            try {
                val scaled = Bitmap.createScaledBitmap(src, (src.width * 0.75f).toInt(), (src.height * 0.75f).toInt(), true)
                val sx = (x * 0.75f).toInt()
                val sy = (y * 0.75f).toInt()
                val sw = min((w * 0.75f).toInt(), scaled.width - sx)
                val sh = min((h * 0.75f).toInt(), scaled.height - sy)
                if (sw > 0 && sh > 0) Bitmap.createBitmap(scaled, sx, sy, sw, sh) else src
            } catch (e2: Exception) {
                src
            }
        }
    }

    fun applyAdjustments(
        src: Bitmap,
        brightness: Float,
        contrast: Float,
        rotation: Int,
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
        var work = src
        val rot = ((rotation % 360) + 360) % 360
        if (rot != 0 || mirror) {
            work = transformBitmap(work, rot, mirror)
        }

        val needColorAdjust = grayscale || sharpen || brightness != 1f || contrast != 1f ||
                gamma != 1f || denoise || saturation != 1f || nightMode || eyeCare

        if (!needColorAdjust) {
            return work
        }

        return try {
            val config = if (work.config == Bitmap.Config.RGB_565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            var out = Bitmap.createBitmap(work.width, work.height, config)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
                isAntiAlias = true
                isFilterBitmap = true
                isDither = true
                colorFilter = ColorMatrixColorFilter(
                    buildColorMatrix(grayscale, brightness, contrast, saturation, nightMode, eyeCare)
                )
            }
            canvas.drawBitmap(work, 0f, 0f, paint)
            if (work != src && work != out) work.recycle()

            out
        } catch (e: OutOfMemoryError) {
            System.gc()
            work
        }
    }

    private fun transformBitmap(src: Bitmap, rotation: Int, mirror: Boolean): Bitmap {
        return try {
            val matrix = Matrix()
            if (mirror) matrix.preScale(-1f, 1f)
            if (rotation != 0) matrix.postRotate(rotation.toFloat())
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            System.gc()
            try {
                val scaled = Bitmap.createScaledBitmap(src, src.width / 2, src.height / 2, true)
                val matrix = Matrix()
                if (mirror) matrix.preScale(-1f, 1f)
                if (rotation != 0) matrix.postRotate(rotation.toFloat())
                Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
            } catch (e2: Exception) {
                src
            }
        }
    }

    private fun buildColorMatrix(
        grayscale: Boolean,
        brightness: Float,
        contrast: Float,
        saturation: Float,
        nightMode: Boolean,
        eyeCare: Boolean
    ): ColorMatrix {
        val cm = ColorMatrix()

        if (grayscale) {
            cm.setSaturation(0f)
        } else if (saturation != 1f) {
            cm.setSaturation(saturation.coerceIn(0f, 2f))
        }

        val c = contrast.coerceIn(0.5f, 2f)
        val t = (1f - c) * 128f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(contrastMatrix)

        if (brightness != 1f) {
            val bOffset = (brightness.coerceIn(0.5f, 2f) - 1f) * 255f
            val brightnessMatrix = ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, bOffset,
                    0f, 1f, 0f, 0f, bOffset,
                    0f, 0f, 1f, 0f, bOffset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(brightnessMatrix)
        }

        if (nightMode) {
            val nightMatrix = ColorMatrix(
                floatArrayOf(
                    0.6f, 0f, 0f, 0f, 0f,
                    0f, 0.6f, 0f, 0f, 0f,
                    0f, 0f, 0.6f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(nightMatrix)
        }

        if (eyeCare) {
            val eyeCareMatrix = ColorMatrix(
                floatArrayOf(
                    1.08f, 0f, 0f, 0f, 8f,
                    0f, 1.04f, 0f, 0f, 4f,
                    0f, 0f, 0.85f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(eyeCareMatrix)
        }

        return cm
    }



    /**
     * 生成漫画分享卡片：自动加边框、漫画名 + 页码水印、"来自 XX 阅读器" 角标。
     * 返回保存后的 PNG 文件，调用方负责分享或展示。
     *
     * @param src 当前页面位图（不会被回收）
     * @param comicTitle 漫画标题
     * @param pageNumber 当前页码（1-based）
     * @param appName 应用名称，用于角标
     */
    fun createShareCard(
        src: Bitmap,
        comicTitle: String,
        pageNumber: Int,
        appName: String
    ): Bitmap? {
        if (src.isRecycled) return null
        return try {
            val maxDim = 1600
            val scale = if (maxOf(src.width, src.height) > maxDim) {
                maxDim.toFloat() / maxOf(src.width, src.height)
            } else 1f
            val imgW = (src.width * scale).toInt()
            val imgH = (src.height * scale).toInt()
            val img = if (scale < 1f) Bitmap.createScaledBitmap(src, imgW, imgH, true) else src

            val padding = 32
            val footerH = 96
            val width = imgW + padding * 2
            val height = imgH + padding * 2 + footerH

            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // 背景：拍立得风格白底
            canvas.drawColor(android.graphics.Color.WHITE)

            // 轻微阴影边框
            paint.color = android.graphics.Color.parseColor("#FFE0E0E0")
            canvas.drawRect(
                (padding - 4).toFloat(),
                (padding - 4).toFloat(),
                (width - padding + 4).toFloat(),
                (imgH + padding + 4).toFloat(),
                paint
            )

            // 图片
            canvas.drawBitmap(img, padding.toFloat(), padding.toFloat(), paint)

            // 底部文字
            paint.color = android.graphics.Color.BLACK
            paint.textSize = 28f
            val titleText = comicTitle.take(26) + if (comicTitle.length > 26) "..." else ""
            canvas.drawText(titleText, padding.toFloat(), (height - footerH + 36).toFloat(), paint)

            paint.textSize = 22f
            paint.color = android.graphics.Color.DKGRAY
            canvas.drawText("第 $pageNumber 页", padding.toFloat(), (height - footerH + 72).toFloat(), paint)

            // 右上角角标
            paint.textSize = 20f
            paint.color = android.graphics.Color.parseColor("#FF666666")
            val badge = "来自 $appName"
            val badgeWidth = paint.measureText(badge)
            canvas.drawText(badge, (width - padding - badgeWidth), (height - footerH + 72).toFloat(), paint)

            // 释放临时缩放的图片（如果创建了新的）
            if (img !== src && !img.isRecycled) img.recycle()
            result
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } catch (e: OutOfMemoryError) {
            System.gc()
            null
        }
    }
}
