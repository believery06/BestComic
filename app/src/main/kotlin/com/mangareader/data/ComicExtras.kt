package com.mangareader.data

import kotlinx.serialization.Serializable

/**
 * 阅读统计数据。
 */
@Serializable
data class ReadingStats(
    val totalPagesRead: Int = 0,
    val comicsFinished: Int = 0,
    val comicsStarted: Int = 0,
    val lastReadAt: Long = 0L,
    val dailyReadDates: Set<String> = emptySet(),
    val nightOwlUnlocked: Boolean = false
) {
    /** 连续阅读天数 */
    fun streakDays(): Int {
        if (dailyReadDates.isEmpty()) return 0
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val sorted = dailyReadDates.mapNotNull { runCatching { fmt.parse(it) }.getOrNull() }
            .sortedDescending()
        if (sorted.isEmpty()) return 0
        val cal = java.util.Calendar.getInstance()
        val today = fmt.format(cal.time)
        if (!dailyReadDates.contains(today)) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
            val yesterday = fmt.format(cal.time)
            if (!dailyReadDates.contains(yesterday)) return 0
        }
        var streak = 1
        cal.time = sorted.first()
        for (i in 1 until sorted.size) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
            if (fmt.format(cal.time) == fmt.format(sorted[i])) {
                streak++
            } else break
        }
        return streak
    }
}

/**
 * 漫画评分、短评与标签（本地数据）。
 */
@Serializable
data class ComicExtras(
    val rating: Float = 0f,          // 0-5 星
    val review: String = "",         // 一句话短评
    val tags: Set<String> = emptySet(), // 用户自定义标签
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * 书单条目
 */
@Serializable
data class ReadingListItem(
    val uriString: String,
    val title: String
)

/**
 * 用户书单
 */
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
