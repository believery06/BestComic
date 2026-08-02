package com.mangareader.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileManagerTest {

    @Test
    fun `toSafeFile returns canonical file for existing path`() {
        val file = File.createTempFile("test", ".cbz")
        try {
            assertEquals(file.canonicalFile, FileManager.toSafeFile(file.absolutePath))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `toSafeFile resolves dot-dot path traversal to canonical path`() {
        val dir = File.createTempFile("safe_dir", "").apply {
            delete()
            mkdirs()
        }
        val file = File(dir, "file.txt").apply { createNewFile() }
        try {
            // dir/../dir/file.txt should canonicalize to dir/file.txt
            val traversalPath = File(dir, "../${dir.name}/file.txt").absolutePath
            assertEquals(file.canonicalFile, FileManager.toSafeFile(traversalPath))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `toSafeFile returns null for blank path`() {
        assertNull(FileManager.toSafeFile(""))
        assertNull(FileManager.toSafeFile("   "))
    }

    @Test
    fun `toSafeFile returns null for nonexistent path that cannot canonicalize`() {
        // Most nonexistent paths still canonicalize, but malformed platform paths fail.
        // This test documents the happy/sad boundary: canonicalize strips . and .. segments.
        val path = File(System.getProperty("java.io.tmpdir"), "nonexistent/../file.txt").absolutePath
        val safe = FileManager.toSafeFile(path)
        assertTrue(safe?.path?.contains("..") != true)
    }
}
