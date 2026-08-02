package com.mangareader.data

import android.content.Context
import androidx.compose.runtime.Immutable
import com.mangareader.R
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * 阅读统计数据。
 */
@Immutable
@Serializable
data class ReadingStats(
    val totalPagesRead: Int = 0,
    val comicsFinished: Int = 0,
    val comicsStarted: Int = 0,
    val lastReadAt: Long = 0L,
    val dailyReadDates: Set<String> = emptySet(),
    val nightOwlUnlocked: Boolean = false,
    /** 每日阅读分钟数：yyyy-MM-dd -> minutes */
    val dailyMinutes: Map<String, Int> = emptyMap(),
    /** 每日阅读页数：yyyy-MM-dd -> pages */
    val dailyPages: Map<String, Int> = emptyMap()
) {
    /** 连续阅读天数 */
    fun streakDays(): Int {
        if (dailyReadDates.isEmpty()) return 0
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
        val sorted = dailyReadDates.mapNotNull { runCatching { LocalDate.parse(it, fmt) }.getOrNull() }
            .sortedDescending()
        if (sorted.isEmpty()) return 0
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val mostRecent = sorted.first()
        if (mostRecent != today && mostRecent != yesterday) return 0
        var streak = 1
        var current = mostRecent
        for (i in 1 until sorted.size) {
            val prev = current.minusDays(1)
            if (prev == sorted[i]) {
                streak++
                current = prev
            } else break
        }
        return streak
    }

    /** 按周聚合阅读分钟数：返回最近若干周（周一开始） */
    fun weeklyMinutes(weeks: Int = 8): List<Pair<String, Int>> {
        if (dailyMinutes.isEmpty()) return emptyList()
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
        val result = linkedMapOf<String, Int>()
        var current = LocalDate.now()
        for (i in 0 until weeks * 7) {
            result[fmt.format(current)] = 0
            current = current.minusDays(1)
        }
        // 将每天归到所在周的周一
        val weekly = linkedMapOf<String, Int>()
        for ((date, minutes) in result) {
            val parsed = runCatching { LocalDate.parse(date, fmt) }.getOrNull() ?: continue
            val monday = parsed.minusDays((parsed.dayOfWeek.value - 1).toLong())
            val mondayKey = fmt.format(monday)
            weekly[mondayKey] = (weekly[mondayKey] ?: 0) + minutes + (dailyMinutes[date] ?: 0)
        }
        return weekly.toList().sortedBy { it.first }
    }

    /** 按月聚合阅读分钟数 */
    fun monthlyMonths(months: Int = 12): List<Pair<String, Int>> {
        if (dailyMinutes.isEmpty()) return emptyList()
        val fmtMonth = DateTimeFormatter.ofPattern("yyyy-MM", Locale.getDefault())
        val result = linkedMapOf<String, Int>()
        var current = LocalDate.now()
        for (i in 0 until months) {
            result[fmtMonth.format(current)] = 0
            current = current.minusMonths(1)
        }
        for ((date, minutes) in dailyMinutes) {
            val m = date.take(7)
            if (result.containsKey(m)) {
                result[m] = (result[m] ?: 0) + minutes
            }
        }
        return result.toList().sortedBy { it.first }
    }

    /** 今日阅读分钟数 */
    fun todayMinutes(): Int {
        val today = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()).format(LocalDate.now())
        return dailyMinutes[today] ?: 0
    }

    /** 今日阅读页数 */
    fun todayPages(): Int {
        val today = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()).format(LocalDate.now())
        return dailyPages[today] ?: 0
    }

    /** 累计阅读分钟数 */
    fun totalMinutesRead(): Int = dailyMinutes.values.sum()

    /** 平均阅读速度（页/小时），无有效数据时返回 0 */
    fun averagePagesPerHour(): Int {
        val minutes = totalMinutesRead()
        return if (minutes > 0) (totalPagesRead * 60f / minutes).toInt() else 0
    }

    /** 日均阅读分钟数，按有记录的日期计算 */
    fun averageMinutesPerDay(): Int {
        val days = dailyMinutes.size.coerceAtLeast(1)
        return totalMinutesRead() / days
    }

    /** 与另一份统计合并：累计值取和，日期/每日数据取最大值。 */
    fun mergeWith(other: ReadingStats): ReadingStats {
        val mergedDailyMinutes = (dailyMinutes.keys + other.dailyMinutes.keys).associateWith { date ->
            maxOf(dailyMinutes[date] ?: 0, other.dailyMinutes[date] ?: 0)
        }
        val mergedDailyPages = (dailyPages.keys + other.dailyPages.keys).associateWith { date ->
            maxOf(dailyPages[date] ?: 0, other.dailyPages[date] ?: 0)
        }
        return copy(
            totalPagesRead = totalPagesRead + other.totalPagesRead,
            comicsFinished = maxOf(comicsFinished, other.comicsFinished),
            comicsStarted = maxOf(comicsStarted, other.comicsStarted),
            lastReadAt = maxOf(lastReadAt, other.lastReadAt),
            dailyReadDates = dailyReadDates + other.dailyReadDates,
            nightOwlUnlocked = nightOwlUnlocked || other.nightOwlUnlocked,
            dailyMinutes = mergedDailyMinutes,
            dailyPages = mergedDailyPages
        )
    }
}

/**
 * 漫画评分、短评与标签（本地数据）。
 */
@Immutable
@Serializable
data class ComicExtras(
    val rating: Float = 0f,          // 0-5 星
    val review: String = "",         // 一句话短评
    val tags: Set<String> = emptySet(), // 用户自定义标签
    val addedAt: Long = System.currentTimeMillis()
) {
    /** 合并评分标签：评分取最大值，标签取并集，短评非空则保留。 */
    fun mergeWith(other: ComicExtras?): ComicExtras {
        if (other == null) return this
        return copy(
            rating = maxOf(rating, other.rating),
            review = review.ifBlank { other.review },
            tags = tags + other.tags,
            addedAt = minOf(addedAt, other.addedAt)
        )
    }
}

/**
 * 书单条目
 */
@Immutable
@Serializable
data class ReadingListItem(
    val uriString: String,
    val title: String
)

/**
 * 用户书单
 */
@Immutable
@Serializable
data class ReadingList(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val items: List<ReadingListItem> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 本地可解锁的成就定义
 */
enum class Achievement(
    val title: String,
    val description: String,
    val iconEmoji: String
) {
    FIRST_COMIC("入门读者", "读完第一本漫画", "\uD83D\uDCDA"),
    STREAK_7("连续阅读 7 天", "累计 7 天打开阅读器", "\uD83D\uDD25"),
    HUNDRED_COMICS("百漫斩", "书架中收录 100 本漫画", "\uD83C\uDFC6"),
    NIGHT_OWL("夜猫子", "凌晨 2 点还在看漫画", "\uD83C\uDF19"),
    TEN_THOUSAND_PAGES("卷王", "累计阅读 10000 页", "\uD83D\uDCD6"),
    FIRST_REVIEW("评论家", "为第一本漫画写下短评", "\u270D\uFE0F"),
    FIRST_LIST("书单达人", "创建第一个书单", "\uD83D\uDCD1");

    val key: String get() = name
}

fun Achievement.displayTitle(context: Context): String = when (this) {
    Achievement.FIRST_COMIC -> context.getString(R.string.achievement_first_comic_title)
    Achievement.STREAK_7 -> context.getString(R.string.achievement_streak_7_title)
    Achievement.HUNDRED_COMICS -> context.getString(R.string.achievement_hundred_comics_title)
    Achievement.NIGHT_OWL -> context.getString(R.string.achievement_night_owl_title)
    Achievement.TEN_THOUSAND_PAGES -> context.getString(R.string.achievement_ten_thousand_pages_title)
    Achievement.FIRST_REVIEW -> context.getString(R.string.achievement_first_review_title)
    Achievement.FIRST_LIST -> context.getString(R.string.achievement_first_list_title)
}

fun Achievement.displayDescription(context: Context): String = when (this) {
    Achievement.FIRST_COMIC -> context.getString(R.string.achievement_first_comic_desc)
    Achievement.STREAK_7 -> context.getString(R.string.achievement_streak_7_desc)
    Achievement.HUNDRED_COMICS -> context.getString(R.string.achievement_hundred_comics_desc)
    Achievement.NIGHT_OWL -> context.getString(R.string.achievement_night_owl_desc)
    Achievement.TEN_THOUSAND_PAGES -> context.getString(R.string.achievement_ten_thousand_pages_desc)
    Achievement.FIRST_REVIEW -> context.getString(R.string.achievement_first_review_desc)
    Achievement.FIRST_LIST -> context.getString(R.string.achievement_first_list_desc)
}
