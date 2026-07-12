package com.mangareader.parser

import android.net.Uri
import com.mangareader.data.ComicType

object ParserFactory {

    fun getParser(type: ComicType): ComicParser? {
        return when (type) {
            ComicType.CBZ -> CbzParser()
            ComicType.CBR -> CbrParser()
            ComicType.CB7 -> Cb7Parser()
            ComicType.CBT -> CbtParser()
            ComicType.EPUB -> EpubParser()
            ComicType.PDF -> PdfParser()
            ComicType.FOLDER -> FolderParser()
            ComicType.COMIC_BOOK -> ComicBookParser()
            else -> null
        }
    }

    fun detectType(name: String): ComicType {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".cbz") || lower.endsWith(".zip") -> ComicType.CBZ
            lower.endsWith(".cbr") || lower.endsWith(".rar") -> ComicType.CBR
            lower.endsWith(".cb7") || lower.endsWith(".7z") -> ComicType.CB7
            lower.endsWith(".cbt") || lower.endsWith(".tar") -> ComicType.CBT
            lower.endsWith(".epub") -> ComicType.EPUB
            lower.endsWith(".pdf") -> ComicType.PDF
            lower.endsWith(".djvu") || lower.endsWith(".djv") -> ComicType.UNKNOWN // Not yet supported
            lower.endsWith(".xps") || lower.endsWith(".oxps") -> ComicType.UNKNOWN // Not yet supported
            else -> ComicType.UNKNOWN
        }
    }

    fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp", "avif")
        return imageExtensions.any { lower.endsWith(".$it") }
    }
}

