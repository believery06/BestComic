package com.mangareader.provider

import android.content.Context
import android.net.Uri
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicType
import com.mangareader.parser.ComicBookParser
import com.mangareader.parser.ParserFactory
import com.mangareader.parser.ParserResult

/**
 * 根据 [ComicEntry] 创建对应的 [ComicProvider]。
 *
 * 目前优先复用现有 parser，通过 [ParserBasedComicProvider] 适配；
 * 后续可逐步替换为原生 Provider（如 [LocalFolderProvider]、[ZipArchiveProvider] 等），
 * 上层阅读器代码无需改动。
 */
object ComicProviderFactory {

    suspend fun create(context: Context, entry: ComicEntry): ComicProvider? {
        return when {
            entry.type == ComicType.COMIC_BOOK || entry.isDirectory || entry.type == ComicType.FOLDER -> {
                createWithParser(context, entry, ComicBookParser())
            }
            else -> {
                val parser = ParserFactory.getParser(entry.type) ?: return null
                createWithParser(context, entry, parser)
            }
        }
    }

    fun createFromPages(title: String, pages: List<com.mangareader.data.ComicPage>): ComicProvider {
        return ParserBasedComicProvider(title, pages, parser = null)
    }

    private suspend fun createWithParser(
        context: Context,
        entry: ComicEntry,
        parser: com.mangareader.parser.ComicParser
    ): ComicProvider? {
        val result = parser.parse(context, entry.uri)
        return when (result) {
            is ParserResult.Success -> ParserBasedComicProvider(entry.title, result.pages, parser)
            is ParserResult.Error -> null
        }
    }
}
