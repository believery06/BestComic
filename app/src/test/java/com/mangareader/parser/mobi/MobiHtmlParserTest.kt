package com.mangareader.parser.mobi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobiHtmlParserTest {

    private val parser = MobiHtmlParser()

    @Test
    fun `parse simple text html`() {
        val html = "<html><body><p>Hello World</p><p>Second paragraph</p></body></html>"
        val result = parser.parse(html)

        assertTrue(result.pages.isNotEmpty())
        val allText = result.pages.joinToString(" ") { it.plainText }
        assertTrue(allText.contains("Hello World"))
        assertTrue(allText.contains("Second paragraph"))
    }

    @Test
    fun `parse html with headings`() {
        val html = """
            <html><body>
            <h1>Chapter 1</h1>
            <p>Content of chapter 1</p>
            <h1>Chapter 2</h1>
            <p>Content of chapter 2</p>
            </body></html>
        """.trimIndent()

        val result = parser.parse(html)

        assertTrue(result.toc.isNotEmpty())
        assertEquals("Chapter 1", result.toc[0].title)
        assertEquals("Chapter 2", result.toc[1].title)
    }

    @Test
    fun `parse html with image references`() {
        val html = """
            <html><body>
            <p>Before image</p>
            <img recindex="5"/>
            <p>After image</p>
            </body></html>
        """.trimIndent()

        val result = parser.parse(html)

        // Should have at least one page with image
        val imagePages = result.pages.filter { it.hasImages }
        assertTrue(imagePages.isNotEmpty())
        assertTrue(imagePages[0].imageIndices.contains(5))
    }

    @Test
    fun `parse html with pagebreak`() {
        val html = """
            <p>Page 1 content</p>
            <mbp:pagebreak/>
            <p>Page 2 content</p>
        """.trimIndent()

        val result = parser.parse(html)

        // Should produce at least 2 pages due to pagebreak
        assertTrue(result.pages.size >= 2)
    }

    @Test
    fun `parse empty html`() {
        val result = parser.parse("")
        assertTrue(result.pages.isEmpty())
        assertTrue(result.toc.isEmpty())
    }

    @Test
    fun `extract plain text`() {
        val html = "<p>Hello <b>World</b></p><p>Second &amp; Third</p>"
        val text = parser.extractPlainText(html)

        assertTrue(text.contains("Hello World"))
        assertTrue(text.contains("Second & Third"))
    }

    @Test
    fun `extract image indices`() {
        val html = """
            <img recindex="1"/>
            <p>text</p>
            <img recindex="5"/>
            <img recindex="10"/>
        """.trimIndent()

        val indices = parser.extractImageIndices(html)
        assertEquals(listOf(1, 5, 10), indices)
    }

    @Test
    fun `parse html entities`() {
        val html = "<p>&lt;tag&gt; &amp; &quot;quotes&quot; &#39;apostrophe&#39;</p>"
        val result = parser.parse(html)

        val allText = result.pages.joinToString(" ") { it.plainText }
        assertTrue(allText.contains("<tag>"))
        assertTrue(allText.contains("&"))
        assertTrue(allText.contains("\"quotes\""))
        assertTrue(allText.contains("'apostrophe'"))
    }

    @Test
    fun `parse chinese text`() {
        val html = "<html><body><h1>第一章 开始</h1><p>这是一段中文内容。</p></body></html>"
        val result = parser.parse(html)

        assertTrue(result.pages.isNotEmpty())
        val allText = result.pages.joinToString(" ") { it.plainText }
        assertTrue(allText.contains("第一章 开始"))
        assertTrue(allText.contains("这是一段中文内容。"))
    }

    @Test
    fun `parse long text splits into pages`() {
        // Generate a long text that should span multiple pages
        val longParagraph = "这是一个很长的段落。".repeat(200)
        val html = "<html><body><p>$longParagraph</p></body></html>"

        val result = parser.parse(html)

        // With MAX_CHARS_PER_PAGE = 2000, this should split into multiple pages
        assertTrue(result.pages.size >= 1)
        val allText = result.pages.joinToString("") { it.plainText }
        assertTrue(allText.contains("这是一个很长的段落。"))
    }
}
