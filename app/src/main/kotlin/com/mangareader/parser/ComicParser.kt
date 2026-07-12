package com.mangareader.parser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType

interface ComicParser {
    suspend fun parse(context: Context, uri: Uri): ParserResult

    /** 释放解析器持有的资源（如 PdfRenderer、临时文件等）。默认空实现。 */
    fun close() {}
}

sealed class ParserResult {
    data class Success(val pages: List<ComicPage>, val type: ComicType) : ParserResult()
    data class Error(val message: String) : ParserResult()
}

fun interface PageLoader {
    suspend fun load(index: Int): Bitmap?
}
