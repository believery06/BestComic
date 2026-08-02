package com.mangareader.data

import android.net.Uri
import androidx.compose.runtime.Immutable

enum class ComicType {
    CBZ,
    CBR,
    CB7,
    CBT,
    EPUB,
    PDF,
    MOBI,
    FOLDER,
    COMIC_BOOK,   // 子目录=漫画，内部多个 PDF/EPUB 等为章节
    UNKNOWN
}

enum class ReadingStatus {
    UNREAD,   // 未读
    READING,  // 阅读中
    READ      // 已读
}

@Immutable
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

@Immutable
data class ComicPage(
    val index: Int,
    val name: String,
    val load: suspend () -> android.graphics.Bitmap? = { null },
    /** 原始图片字节流；优先使用，便于上层按屏幕尺寸采样解码，避免全分辨率加载。 */
    val loadStream: suspend () -> java.io.InputStream? = { null },
    val chapterTitle: String = "",
    val providerIndex: Int = index,
    val provider: ComicProvider? = null,
    /** 页面关联的文本内容，用于 TTS 文字朗读。 */
    val textContent: String = ""
)

/**
 * Represents a single chapter file inside a COMIC_BOOK.
 * Each chapter is a PDF/EPUB/CBZ/etc. The chapter has its own page list
 * and the Reader concatenates all chapter pages in order.
 */
@Immutable
data class ComicChapter(
    val title: String,
    val uri: Uri,
    val type: ComicType,
    val pages: List<ComicPage>
)

/**
 * 单条阅读历史记录，按最后访问时间倒序排列。
 */
@Immutable
@kotlinx.serialization.Serializable
data class ReadingHistoryEntry(
    val uriString: String,
    val title: String,
    val lastReadAt: Long = 0L,
    val lastReadPage: Int = 0,
    val totalPages: Int = 0,
    val coverUriString: String? = null
)
