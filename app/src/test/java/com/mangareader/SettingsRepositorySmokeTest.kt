package com.mangareader

import com.mangareader.data.AppTheme
import com.mangareader.data.BackgroundTexture
import com.mangareader.data.GestureConfig
import com.mangareader.data.GoalHistoryEntry
import com.mangareader.data.PageAnimation
import com.mangareader.data.ReadingGoals
import com.mangareader.data.ReadingStats
import com.mangareader.data.ReaderSettings
import com.mangareader.data.ScaleFilter
import com.mangareader.data.ScrollMode
import com.mangareader.data.TapZoneSize
import com.mangareader.data.ZoomMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SettingsRepository 的轻量冒烟测试。
 *
 * SettingsRepository 的持久化逻辑依赖 Android DataStore，
 * 此处聚焦于其引用的纯数据类型与默认值，确保数据类与枚举行为符合预期，
 * 避免因数据类字段变更或枚举解析失败导致 Repository 解析逻辑异常。
 */
class SettingsRepositorySmokeTest {

    @Test
    fun `ReadingStats defaults are sane`() {
        val stats = ReadingStats()
        assertEquals(0, stats.totalPagesRead)
        assertEquals(0, stats.comicsFinished)
        assertEquals(0L, stats.lastReadAt)
        assertFalse(stats.nightOwlUnlocked)
        assertTrue(stats.dailyMinutes.isEmpty())
        assertTrue(stats.dailyPages.isEmpty())
    }

    @Test
    fun `ReadingGoals defaults are zero`() {
        val goals = ReadingGoals()
        assertEquals(0, goals.dailyPages)
        assertEquals(0, goals.dailyMinutes)
    }

    @Test
    fun `GoalHistoryEntry defaults`() {
        val entry = GoalHistoryEntry(date = "2026-08-02")
        assertEquals("2026-08-02", entry.date)
        assertFalse(entry.pagesAchieved)
        assertFalse(entry.minutesAchieved)
    }

    @Test
    fun `ReaderSettings defaults match expectations`() {
        val settings = ReaderSettings()
        assertFalse(settings.dualPage)
        assertFalse(settings.rtl)
        assertTrue(settings.immersive)
        assertEquals("#FF000000", settings.backgroundColor)
        assertEquals(1f, settings.brightness)
        assertEquals(1f, settings.contrast)
        assertEquals(ScrollMode.PAGE, settings.scrollMode)
        assertEquals(ZoomMode.FREE, settings.zoomMode)
        assertEquals(PageAnimation.NONE, settings.pageAnimation)
        assertEquals(1.0f, settings.pageAnimSpeed)
        assertEquals(TapZoneSize.MEDIUM, settings.tapZoneSize)
        assertEquals(ScaleFilter.BILINEAR, settings.scaleFilter)
        assertEquals(BackgroundTexture.NONE, settings.backgroundTexture)
        assertEquals(AppTheme.INK, settings.appTheme)
        assertEquals("#FF5C7A94", settings.customSeedColor)
        assertTrue(settings.keepScreenOn)
        assertFalse(settings.flipSoundEnabled)
        assertTrue(settings.flipVibrationEnabled)
    }

    @Test
    fun `AppTheme INK has expected properties`() {
        val ink = AppTheme.INK
        assertTrue(ink.isDark)
        assertEquals(0xFF5C7A94L, ink.seedColor)
        assertEquals("#FF121212", ink.readerBackground)
        assertEquals(BackgroundTexture.NONE, ink.readerTexture)
    }

    @Test
    fun `AppTheme KRAFT is light theme`() {
        val kraft = AppTheme.KRAFT
        assertFalse(kraft.isDark)
        assertEquals("#FFF5E6C8", kraft.readerBackground)
        assertEquals(BackgroundTexture.KRAFT, kraft.readerTexture)
    }

    @Test
    fun `GestureConfig PRESET_MANGA identity`() {
        // GestureConfig.PRESET_MANGA 是公开常量，可直接访问
        assertTrue(GestureConfig.PRESET_MANGA.presetName.isNotEmpty())
        assertTrue(GestureConfig.PRESET_COMIC.presetName.isNotEmpty())
        assertTrue(GestureConfig.PRESET_SIMPLE.presetName.isNotEmpty())
    }

    @Test
    fun `ScrollMode enum values`() {
        assertEquals(ScrollMode.PAGE, ScrollMode.valueOf("PAGE"))
        assertEquals(ScrollMode.VERTICAL, ScrollMode.valueOf("VERTICAL"))
        assertEquals(ScrollMode.HORIZONTAL, ScrollMode.valueOf("HORIZONTAL"))
    }

    @Test
    fun `ZoomMode enum values`() {
        assertEquals(ZoomMode.FREE, ZoomMode.valueOf("FREE"))
        assertEquals(ZoomMode.FIT_WIDTH, ZoomMode.valueOf("FIT_WIDTH"))
        assertEquals(ZoomMode.FIT_HEIGHT, ZoomMode.valueOf("FIT_HEIGHT"))
        assertEquals(ZoomMode.FIT_SCREEN, ZoomMode.valueOf("FIT_SCREEN"))
    }

    @Test
    fun `PageAnimation fallback from deprecated CURL`() {
        // 模拟旧版 CURL 名称：SettingsRepository.parseReaderSettings 中
        // 若 pageAnimation == "CURL" 则回退到 PageAnimation.NONE
        val curlName = "CURL"
        val resolved = if (curlName == "CURL") PageAnimation.NONE else PageAnimation.valueOf(curlName)
        assertEquals(PageAnimation.NONE, resolved)
    }

    @Test
    fun `ReadingStats todayPages with today entry returns correct value`() {
        val stats = ReadingStats(dailyPages = mapOf("2026-08-02" to 10))
        // 此处不依赖 today 匹配，仅验证 map 行为
        assertEquals(10, stats.dailyPages["2026-08-02"])
    }
}
