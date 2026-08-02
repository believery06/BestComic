package com.mangareader.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavSyncTest {

    @Test
    fun `mergeSyncData picks max page and total when no timestamps`() {
        val local = SyncData(
            progress = mapOf("uri1" to 10),
            totalPages = mapOf("uri1" to 20),
            progressTimes = emptyMap()
        )
        val remote = SyncData(
            progress = mapOf("uri1" to 15),
            totalPages = mapOf("uri1" to 25),
            progressTimes = emptyMap()
        )

        val merged = WebDavSync.mergeSyncData(local, remote)

        assertEquals(15, merged.progress["uri1"])
        assertEquals(25, merged.totalPages["uri1"])
    }

    @Test
    fun `mergeSyncData prefers remote progress when remote is newer`() {
        val local = SyncData(
            progress = mapOf("uri1" to 10),
            totalPages = mapOf("uri1" to 20),
            progressTimes = mapOf("uri1" to 1000L)
        )
        val remote = SyncData(
            progress = mapOf("uri1" to 5),
            totalPages = mapOf("uri1" to 20),
            progressTimes = mapOf("uri1" to 2000L)
        )

        val merged = WebDavSync.mergeSyncData(local, remote)

        // 按时间戳应选择较新的远端进度，即使页码更小
        assertEquals(5, merged.progress["uri1"])
        assertEquals(2000L, merged.progressTimes["uri1"])
    }

    @Test
    fun `mergeSyncData keeps local progress when local is newer`() {
        val local = SyncData(
            progress = mapOf("uri1" to 10),
            totalPages = mapOf("uri1" to 20),
            progressTimes = mapOf("uri1" to 3000L)
        )
        val remote = SyncData(
            progress = mapOf("uri1" to 15),
            totalPages = mapOf("uri1" to 25),
            progressTimes = mapOf("uri1" to 2000L)
        )

        val merged = WebDavSync.mergeSyncData(local, remote)

        assertEquals(10, merged.progress["uri1"])
        assertEquals(25, merged.totalPages["uri1"])
        assertEquals(3000L, merged.progressTimes["uri1"])
    }

    @Test
    fun `mergeSyncData keeps local when only remote lacks timestamp`() {
        val local = SyncData(
            progress = mapOf("uri1" to 10),
            progressTimes = mapOf("uri1" to 1000L)
        )
        val remote = SyncData(
            progress = mapOf("uri1" to 20),
            progressTimes = emptyMap()
        )

        val merged = WebDavSync.mergeSyncData(local, remote)

        assertEquals(10, merged.progress["uri1"])
        assertEquals(1000L, merged.progressTimes["uri1"])
    }

    @Test
    fun `mergeSyncData unions bookmarks and favorites`() {
        val local = SyncData(
            bookmarks = mapOf("uri1" to listOf(1, 2)),
            favorites = setOf("uri1")
        )
        val remote = SyncData(
            bookmarks = mapOf("uri1" to listOf(2, 3), "uri2" to listOf(5)),
            favorites = setOf("uri2")
        )

        val merged = WebDavSync.mergeSyncData(local, remote)

        assertEquals(listOf(1, 2, 3), merged.bookmarks["uri1"])
        assertEquals(listOf(5), merged.bookmarks["uri2"])
        assertEquals(setOf("uri1", "uri2"), merged.favorites)
    }

    @Test
    fun `mergeSyncData merges progressTimes for remote only entries`() {
        val local = SyncData(
            progress = mapOf("uri1" to 10),
            progressTimes = mapOf("uri1" to 1000L)
        )
        val remote = SyncData(
            progress = mapOf("uri2" to 20),
            progressTimes = mapOf("uri2" to 2000L)
        )

        val merged = WebDavSync.mergeSyncData(local, remote)

        assertEquals(mapOf("uri1" to 1000L, "uri2" to 2000L), merged.progressTimes)
    }
}
