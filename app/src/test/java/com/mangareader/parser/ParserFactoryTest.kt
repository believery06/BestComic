package com.mangareader.parser

import com.mangareader.data.ComicType
import org.junit.Assert.assertEquals
import org.junit.Test

class ParserFactoryTest {

    @Test
    fun `detectType recognizes cbz and zip`() {
        assertEquals(ComicType.CBZ, ParserFactory.detectType("comic.cbz"))
        assertEquals(ComicType.CBZ, ParserFactory.detectType("comic.zip"))
        assertEquals(ComicType.CBZ, ParserFactory.detectType("COMIC.CBZ"))
    }

    @Test
    fun `detectType recognizes cbr and rar`() {
        assertEquals(ComicType.CBR, ParserFactory.detectType("chapter.cbr"))
        assertEquals(ComicType.CBR, ParserFactory.detectType("chapter.rar"))
    }

    @Test
    fun `detectType recognizes cb7 and 7z`() {
        assertEquals(ComicType.CB7, ParserFactory.detectType("volume.cb7"))
        assertEquals(ComicType.CB7, ParserFactory.detectType("volume.7z"))
    }

    @Test
    fun `detectType recognizes cbt and tar`() {
        assertEquals(ComicType.CBT, ParserFactory.detectType("archive.cbt"))
        assertEquals(ComicType.CBT, ParserFactory.detectType("archive.tar"))
    }

    @Test
    fun `detectType recognizes epub and pdf`() {
        assertEquals(ComicType.EPUB, ParserFactory.detectType("book.epub"))
        assertEquals(ComicType.PDF, ParserFactory.detectType("book.pdf"))
    }

    @Test
    fun `detectType recognizes mobi azw and azw3`() {
        assertEquals(ComicType.MOBI, ParserFactory.detectType("comic.mobi"))
        assertEquals(ComicType.MOBI, ParserFactory.detectType("comic.azw"))
        assertEquals(ComicType.MOBI, ParserFactory.detectType("comic.azw3"))
        assertEquals(ComicType.MOBI, ParserFactory.detectType("COMIC.MOBI"))
    }

    @Test
    fun `detectType returns unknown for unsupported formats`() {
        assertEquals(ComicType.UNKNOWN, ParserFactory.detectType("image.jpg"))
        assertEquals(ComicType.UNKNOWN, ParserFactory.detectType("document.txt"))
        assertEquals(ComicType.UNKNOWN, ParserFactory.detectType(""))
    }

    @Test
    fun `isImageFile recognizes common image extensions`() {
        assertEquals(true, ParserFactory.isImageFile("page.jpg"))
        assertEquals(true, ParserFactory.isImageFile("page.jpeg"))
        assertEquals(true, ParserFactory.isImageFile("page.png"))
        assertEquals(true, ParserFactory.isImageFile("page.webp"))
        assertEquals(true, ParserFactory.isImageFile("page.gif"))
        assertEquals(true, ParserFactory.isImageFile("page.heic"))
        assertEquals(true, ParserFactory.isImageFile("page.bmp"))
        assertEquals(true, ParserFactory.isImageFile("page.avif"))
    }

    @Test
    fun `isImageFile rejects non-image names`() {
        assertEquals(false, ParserFactory.isImageFile("page.pdf"))
        assertEquals(false, ParserFactory.isImageFile("page.txt"))
        assertEquals(false, ParserFactory.isImageFile("png"))
    }

    @Test
    fun `getParser returns correct parser for supported types`() {
        assertEquals(true, ParserFactory.getParser(ComicType.CBZ) is CbzParser)
        assertEquals(true, ParserFactory.getParser(ComicType.CBR) is CbrParser)
        assertEquals(true, ParserFactory.getParser(ComicType.CB7) is Cb7Parser)
        assertEquals(true, ParserFactory.getParser(ComicType.CBT) is CbtParser)
        assertEquals(true, ParserFactory.getParser(ComicType.EPUB) is EpubParser)
        assertEquals(true, ParserFactory.getParser(ComicType.PDF) is PdfParser)
        assertEquals(true, ParserFactory.getParser(ComicType.MOBI) is MobiParser)
        assertEquals(true, ParserFactory.getParser(ComicType.FOLDER) is FolderParser)
        assertEquals(true, ParserFactory.getParser(ComicType.COMIC_BOOK) is ComicBookParser)
    }

    @Test
    fun `getParser returns null for unknown type`() {
        assertEquals(null, ParserFactory.getParser(ComicType.UNKNOWN))
    }
}
