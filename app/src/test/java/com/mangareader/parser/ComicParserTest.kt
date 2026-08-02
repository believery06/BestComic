package com.mangareader.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComicParserTest {

    @Test
    fun `normalizeArchiveEntryName keeps normal forward slash paths`() {
        assertEquals("page.jpg", normalizeArchiveEntryName("page.jpg"))
        assertEquals("folder/page.jpg", normalizeArchiveEntryName("folder/page.jpg"))
        assertEquals("a/b/c/page.jpg", normalizeArchiveEntryName("a/b/c/page.jpg"))
    }

    @Test
    fun `normalizeArchiveEntryName converts backslashes to forward slashes`() {
        assertEquals("folder/page.jpg", normalizeArchiveEntryName("folder\\page.jpg"))
        assertEquals("a/b/c/page.jpg", normalizeArchiveEntryName("a\\b\\c\\page.jpg"))
    }

    @Test
    fun `normalizeArchiveEntryName removes current directory segments`() {
        assertEquals("page.jpg", normalizeArchiveEntryName("./page.jpg"))
        assertEquals("folder/page.jpg", normalizeArchiveEntryName("./folder/./page.jpg"))
        assertEquals("folder/page.jpg", normalizeArchiveEntryName("folder/././page.jpg"))
    }

    @Test
    fun `normalizeArchiveEntryName resolves parent directory segments`() {
        assertEquals("page.jpg", normalizeArchiveEntryName("folder/../page.jpg"))
        assertEquals("a/page.jpg", normalizeArchiveEntryName("a/b/../page.jpg"))
        assertEquals("page.jpg", normalizeArchiveEntryName("a/b/c/../../../page.jpg"))
    }

    @Test
    fun `normalizeArchiveEntryName rejects traversal above root`() {
        assertNull(normalizeArchiveEntryName("../page.jpg"))
        assertNull(normalizeArchiveEntryName("folder/../../page.jpg"))
        assertNull(normalizeArchiveEntryName("a/../b/../../page.jpg"))
    }

    @Test
    fun `normalizeArchiveEntryName rejects empty and blank names`() {
        assertNull(normalizeArchiveEntryName(""))
        assertNull(normalizeArchiveEntryName("   "))
        assertNull(normalizeArchiveEntryName("/"))
        assertNull(normalizeArchiveEntryName("./"))
    }

    @Test
    fun `normalizeArchiveEntryName handles mixed separators and redundant segments`() {
        assertEquals(
            "folder/sub/page.jpg",
            normalizeArchiveEntryName("./folder\\.\\sub\\..\\sub/page.jpg")
        )
    }
}
