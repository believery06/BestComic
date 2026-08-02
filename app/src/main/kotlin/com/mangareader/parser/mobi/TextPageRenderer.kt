package com.mangareader.parser.mobi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream

class TextPageRenderer(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val isDarkTheme: Boolean = false
) {
    companion object {
        private const val MARGIN_HORIZONTAL = 56
        private const val MARGIN_TOP = 48
        private const val MARGIN_BOTTOM = 72

        private const val TEXT_SIZE_BODY = 42f
        private const val TEXT_SIZE_HEADING1 = 56f
        private const val TEXT_SIZE_HEADING2 = 50f
        private const val TEXT_SIZE_HEADING3 = 46f
        private const val TEXT_SIZE_PAGE_NUM = 28f

        private const val LINE_SPACING_BODY = 1.5f
        private const val LINE_SPACING_HEADING = 1.3f

        private const val PARAGRAPH_SPACING = 24

        private const val MAX_RENDER_BLOCKS = 100

        private val LIGHT_TEXT_COLOR = Color.BLACK
        private val LIGHT_BG_COLOR = Color.WHITE
        private val LIGHT_PAGE_NUM_COLOR = Color.GRAY

        private val DARK_TEXT_COLOR = Color.parseColor("#E0E0E0")
        private val DARK_BG_COLOR = Color.parseColor("#121212")
        private val DARK_PAGE_NUM_COLOR = Color.parseColor("#888888")
    }

    private val textColor: Int = if (isDarkTheme) DARK_TEXT_COLOR else LIGHT_TEXT_COLOR
    private val bgColor: Int = if (isDarkTheme) DARK_BG_COLOR else LIGHT_BG_COLOR
    private val pageNumColor: Int = if (isDarkTheme) DARK_PAGE_NUM_COLOR else LIGHT_PAGE_NUM_COLOR

    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = TEXT_SIZE_BODY
        typeface = Typeface.DEFAULT
    }

    private val headingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = TEXT_SIZE_HEADING1
        typeface = Typeface.DEFAULT_BOLD
    }

    private val pageNumPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pageNumColor
        textSize = TEXT_SIZE_PAGE_NUM
        textAlign = Paint.Align.CENTER
    }

    private val textWidth: Int
        get() = screenWidth - MARGIN_HORIZONTAL * 2

    private val textHeight: Int
        get() = screenHeight - MARGIN_TOP - MARGIN_BOTTOM

    fun renderPage(
        blocks: List<MobiHtmlParser.ContentBlock>,
        pageNumber: Int = 0
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bgColor)

        var y = MARGIN_TOP.toFloat()
        var renderedBlocks = 0

        for (block in blocks) {
            if (renderedBlocks >= MAX_RENDER_BLOCKS) break

            when (block.type) {
                MobiHtmlParser.BlockType.HEADING -> {
                    y = renderHeading(canvas, block.text, block.level, y)
                }
                MobiHtmlParser.BlockType.PARAGRAPH -> {
                    y = renderParagraph(canvas, block.text, y)
                }
                MobiHtmlParser.BlockType.IMAGE -> {
                    y += PARAGRAPH_SPACING
                }
                else -> {}
            }

            renderedBlocks++

            if (y > screenHeight - MARGIN_BOTTOM) break
        }

        if (pageNumber > 0) {
            val pageText = "$pageNumber"
            val pageY = screenHeight - MARGIN_BOTTOM / 2f
            canvas.drawText(pageText, screenWidth / 2f, pageY + TEXT_SIZE_PAGE_NUM / 2, pageNumPaint)
        }

        return bitmap
    }

    fun renderText(
        text: String,
        pageNumber: Int = 0
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bgColor)

        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, bodyPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING_BODY)
            .setIncludePad(true)
            .build()

        canvas.save()
        canvas.translate(MARGIN_HORIZONTAL.toFloat(), MARGIN_TOP.toFloat())
        layout.draw(canvas)
        canvas.restore()

        if (pageNumber > 0) {
            val pageText = "$pageNumber"
            val pageY = screenHeight - MARGIN_BOTTOM / 2f
            canvas.drawText(pageText, screenWidth / 2f, pageY + TEXT_SIZE_PAGE_NUM / 2, pageNumPaint)
        }

        return bitmap
    }

    fun encodeToPng(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
        return stream.toByteArray()
    }

    private fun renderHeading(
        canvas: Canvas,
        text: String,
        level: Int,
        startY: Float
    ): Float {
        val paint = when (level) {
            1 -> headingPaint
            2 -> createHeadingPaint(TEXT_SIZE_HEADING2)
            3 -> createHeadingPaint(TEXT_SIZE_HEADING3)
            else -> createHeadingPaint(TEXT_SIZE_BODY)
        }

        var y = startY + PARAGRAPH_SPACING

        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, LINE_SPACING_HEADING)
            .setIncludePad(true)
            .build()

        canvas.save()
        canvas.translate(MARGIN_HORIZONTAL.toFloat(), y)
        layout.draw(canvas)
        canvas.restore()

        y += layout.height + PARAGRAPH_SPACING

        return y
    }

    private fun renderParagraph(
        canvas: Canvas,
        text: String,
        startY: Float
    ): Float {
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, bodyPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING_BODY)
            .setIncludePad(true)
            .build()

        canvas.save()
        canvas.translate(MARGIN_HORIZONTAL.toFloat(), startY)
        layout.draw(canvas)
        canvas.restore()

        return startY + layout.height + PARAGRAPH_SPACING
    }

    private fun createHeadingPaint(textSize: Float): TextPaint {
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    fun measureTextHeight(text: String, isHeading: Boolean = false, headingLevel: Int = 1): Int {
        val paint = if (isHeading) {
            when (headingLevel) {
                1 -> headingPaint
                2 -> createHeadingPaint(TEXT_SIZE_HEADING2)
                3 -> createHeadingPaint(TEXT_SIZE_HEADING3)
                else -> createHeadingPaint(TEXT_SIZE_BODY)
            }
        } else {
            bodyPaint
        }

        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, textWidth)
            .setAlignment(if (isHeading) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, if (isHeading) LINE_SPACING_HEADING else LINE_SPACING_BODY)
            .setIncludePad(true)
            .build()

        return layout.height
    }
}
