package com.mangareader

import com.mangareader.data.GoalHistoryEntry
import com.mangareader.data.ReadingGoals
import com.mangareader.data.ReadingStats
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ReadingStatsRepository 的轻量单元测试。
 *
 * ReadingStatsRepository 的核心持久化逻辑依赖 Android DataStore，
 * 直接在 JVM 上运行需要完整的 Robolectric 环境。此处聚焦于：
 * - ReadingStats 数据类的纯函数逻辑（mergeWith / todayPages / streakDays 等）
 * - JSON 序列化 / 反序列化的正确性（使用与生产代码相同的 Json 配置）
 *
 * 这些函数是 ReadingStatsRepository 内部 parse/merge 逻辑的公开体现，
 * 覆盖了 repository 的核心业务规则。
 */
class ReadingStatsRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `mergeWith sums totalPagesRead`() {
        val a = ReadingStats(totalPagesRead = 100)
        val b = ReadingStats(totalPagesRead = 50)
        val merged = a.mergeWith(b)
        assertEquals(150, merged.totalPagesRead)
    }

    @Test
    fun `mergeWith takes max of perDate daily values`() {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
        val today = fmt.format(LocalDate.now())
        val yesterday = fmt.format(LocalDate.now().minusDays(1))

        val a = ReadingStats(
            dailyPages = mapOf(today to 30, yesterday to 20),
            dailyMinutes = mapOf(today to 60, yesterday to 40)
        )
        val b = ReadingStats(
            dailyPages = mapOf(today to 25, yesterday to 50),
            dailyMinutes = mapOf(today to 80)
        )
        val merged = a.mergeWith(b)
        assertEquals(30, merged.dailyPages[today])
        assertEquals(50, merged.dailyPages[yesterday])
        assertEquals(80, merged.dailyMinutes[today])
        assertEquals(40, merged.dailyMinutes[yesterday])
    }

    @Test
    fun `mergeWith unions read dates and takes max of comicsFinished`() {
        val a = ReadingStats(
            dailyReadDates = setOf("2026-08-01"),
            comicsFinished = 3,
            lastReadAt = 1000L
        )
        val b = ReadingStats(
            dailyReadDates = setOf("2026-08-02"),
            comicsFinished = 5,
            lastReadAt = 2000L
        )
        val merged = a.mergeWith(b)
        assertTrue(merged.dailyReadDates.contains("2026-08-01"))
        assertTrue(merged.dailyReadDates.contains("2026-08-02"))
        assertEquals(5, merged.comicsFinished)
        assertEquals(2000L, merged.lastReadAt)
    }

    @Test
    fun `mergeWith combines nightOwlUnlocked with logical OR`() {
        val a = ReadingStats(nightOwlUnlocked = true)
        val b = ReadingStats(nightOwlUnlocked = false)
        assertTrue(a.mergeWith(b).nightOwlUnlocked)
        assertTrue(b.mergeWith(a).nightOwlUnlocked)
        assertFalse(
            ReadingStats(nightOwlUnlocked = false)
                .mergeWith(ReadingStats(nightOwlUnlocked = false))
                .nightOwlUnlocked
        )
    }

    @Test
    fun `todayPages returns pages for today`() {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
        val today = fmt.format(LocalDate.now())
        val stats = ReadingStats(dailyPages = mapOf(today to 42))
        assertEquals(42, stats.todayPages())
    }

    @Test
    fun `todayMinutes returns minutes for today`() {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
        val today = fmt.format(LocalDate.now())
        val stats = ReadingStats(dailyMinutes = mapOf(today to 90))
        assertEquals(90, stats.todayMinutes())
    }

    @Test
    fun `todayPages returns zero when no entry for today`() {
        val stats = ReadingStats()
        assertEquals(0, stats.todayPages())
    }

    @Test
    fun `streakDays returns zero for empty dates`() {
        assertEquals(0, ReadingStats().streakDays())
    }

    @Test
    fun `streakDays counts consecutive days ending today`() {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
        val today = LocalDate.now()
        val dates = (0..4L).map { fmt.format(today.minusDays(it)) }.toSet()
        val stats = ReadingStats(dailyReadDates = dates)
        assertEquals(5, stats.streakDays())
    }

    @Test
    fun `streakDays resets when yesterday is missing`() {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
        val today = LocalDate.now()
        val dates = setOf(
            fmt.format(today),
            fmt.format(today.minusDays(2)),
            fmt.format(today.minusDays(3))
        )
        val stats = ReadingStats(dailyReadDates = dates)
        assertEquals(1, stats.streakDays())
    }

    @Test
    fun `totalMinutesRead sums all daily minutes`() {
        val stats = ReadingStats(dailyMinutes = mapOf("2026-08-01" to 30, "2026-08-02" to 45))
        assertEquals(75, stats.totalMinutesRead())
    }

    @Test
    fun `averagePagesPerHour computes correctly`() {
        val stats = ReadingStats(totalPagesRead = 120, dailyMinutes = mapOf("2026-08-01" to 60))
        assertEquals(120, stats.averagePagesPerHour())
    }

    @Test
    fun `averagePagesPerHour returns zero when no minutes recorded`() {
        val stats = ReadingStats(totalPagesRead = 100)
        assertEquals(0, stats.averagePagesPerHour())
    }

    @Test
    fun `readingStats serializes and deserializes via Json`() {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
        val today = fmt.format(LocalDate.now())
        val original = ReadingStats(
            totalPagesRead = 999,
            comicsFinished = 7,
            lastReadAt = 12345678L,
            dailyReadDates = setOf(today),
            nightOwlUnlocked = true,
            dailyMinutes = mapOf(today to 120),
            dailyPages = mapOf(today to 50)
        )
        val encoded = json.encodeToString(ReadingStats.serializer(), original)
        val decoded = json.decodeFromString(ReadingStats.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `readingGoals serializes and deserializes via Json`() {
        val original = ReadingGoals(dailyPages = 30, dailyMinutes = 60)
        val encoded = json.encodeToString(ReadingGoals.serializer(), original)
        val decoded = json.decodeFromString(ReadingGoals.serializer(), encoded)
        assertEquals(30, decoded.dailyPages)
        assertEquals(60, decoded.dailyMinutes)
    }

    @Test
    fun `goalHistoryEntry serializes and deserializes via Json`() {
        val original = GoalHistoryEntry(
            date = "2026-08-02",
            pagesAchieved = true,
            minutesAchieved = false
        )
        val encoded = json.encodeToString(GoalHistoryEntry.serializer(), original)
        val decoded = json.decodeFromString(GoalHistoryEntry.serializer(), encoded)
        assertEquals("2026-08-02", decoded.date)
        assertTrue(decoded.pagesAchieved)
        assertFalse(decoded.minutesAchieved)
    }
}
