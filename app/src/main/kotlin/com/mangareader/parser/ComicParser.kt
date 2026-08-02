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

/**
 * 规范化压缩包条目名，防止路径遍历并统一分隔符。
 *
 * - 将反斜杠替换为正斜杠
 * - 解析 `.` 与 `..` 段
 * - 拒绝绝对路径或以 `..` 开头的路径
 *
 * @return 规范化后的路径；若存在遍历风险则返回 null
 */
fun normalizeArchiveEntryName(name: String): String? {
    if (name.isBlank()) return null
    // 统一分隔符并拆分
    val parts = name.replace('\\', '/').split('/')
    val resolved = mutableListOf<String>()
    for (part in parts) {
        when (part) {
            "", "." -> continue
            ".." -> {
                if (resolved.isEmpty()) return null // 试图越出根目录
                resolved.removeAt(resolved.lastIndex)
            }
            else -> resolved.add(part)
        }
    }
    if (resolved.isEmpty()) return null
    return resolved.joinToString("/")
}
