package com.mangareader.data

import android.net.Uri
import com.mangareader.provider.ComicProvider

enum class ComicType {
    CBZ,
    CBR,
    CB7,
    CBT,
    EPUB,
    PDF,
    FOLDER,
    COMIC_BOOK,   // 子目录=漫画，内部多个 PDF/EPUB 等为章节
    UNKNOWN
}

enum class ReadingStatus {
    UNREAD,   // 未读
    READING,  // 阅读中
    READ      // 已读
}

data class ComicEntry(
    val uri: Uri,
    val title: String,
    val type: ComicType,
    val coverUri: Uri? = null,
    val path: String = "",
    val lastReadPage: Int = 0,
    val totalPages: Int = 0,
    val isDirectory: Boolean = false
)

data class ComicPage(
    val index: Int,
    val name: String,
    val load: suspend () -> android.graphics.Bitmap? = { null },
    /** 原始图片字节流；优先使用，便于上层按屏幕尺寸采样解码，避免全分辨率加载。 */
    val loadStream: suspend () -> java.io.InputStream? = { null },
    val chapterTitle: String = "",
    val providerIndex: Int = index,
    val provider: ComicProvider? = null
)

/**
 * Represents a single chapter file inside a COMIC_BOOK.
 * Each chapter is a PDF/EPUB/CBZ/etc. The chapter has its own page list
 * and the Reader concatenates all chapter pages in order.
 */
data class ComicChapter(
    val title: String,
    val uri: Uri,
    val type: ComicType,
    val pages: List<ComicPage>
)

/**
 * 用于在 Bookshelf/Browser 和 Reader 之间传递 ComicEntry，避免通过
 * Navigation 路由参数编码/解码 SAF content:// URI（%2F、%3A 会被
 * Uri.decode 破坏）。
 */
object ComicHolder {
    @Volatile
    var entry: ComicEntry? = null

    /** 当前选中的分卷/分章；Reader 会优先读取该卷页面。 */
    @Volatile
    var chapter: ComicChapter? = null
}
