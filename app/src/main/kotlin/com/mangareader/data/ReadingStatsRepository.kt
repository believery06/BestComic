package com.mangareader.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 阅读统计、阅读目标、阅读会话与目标达成历史的专属 Repository。
 *
 * 从 [SettingsRepository] 拆分出来，隔离与阅读数据相关的持久化逻辑，
 * 避免单一 Repository 成为全局变更热点。
 */
class ReadingStatsRepository(private val context: Context) {

    companion object {
        @Volatile
        private var instance: ReadingStatsRepository? = null

        fun getInstance(context: Context): ReadingStatsRepository {
            return instance ?: synchronized(this) {
                instance ?: ReadingStatsRepository(context.applicationContext).also { instance = it }
            }
        }

        private val KEY_READING_STATS = stringPreferencesKey("reading_stats")
        private val KEY_READING_GOALS = stringPreferencesKey("reading_goals")
        private val KEY_READING_SESSION_START = longPreferencesKey("reading_session_start")
        private val KEY_READING_SESSION_URI = stringPreferencesKey("reading_session_uri")
        private val KEY_GOAL_HISTORY = stringPreferencesKey("goal_history")

        private const val MAX_DAILY_STATS_RETENTION_DAYS = 730 // 保留约 2 年日统计数据
        private const val MAX_READING_SESSION_MINUTES = 240 // 单次阅读会话上限 4 小时
        private const val MAX_SESSION_RECOVERY_MINUTES = 240
        private const val MAX_GOAL_HISTORY_DAYS = 90

        private val json = Json { ignoreUnknownKeys = true }
    }

    fun getReadingStats(): Flow<ReadingStats> = context.userDataStore.data.map { prefs ->
        parseReadingStats(prefs[KEY_READING_STATS])
    }.flowOn(Dispatchers.IO)

    suspend fun updateReadingStats(
        pagesDelta: Int = 0,
        comicFinished: Boolean = false,
        readingMinutes: Int = 0
    ) {
        context.userDataStore.edit { prefs ->
            val stats = parseReadingStats(prefs[KEY_READING_STATS])
            val now = System.currentTimeMillis()
            val dateStr = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
                .format(LocalDate.now())
            val isNightOwl = runCatching {
                val hour = java.time.LocalDateTime.now().hour
                hour in 2..4
            }.getOrDefault(false)
            val newMinutes = stats.dailyMinutes.toMutableMap().apply {
                if (readingMinutes > 0) {
                    this[dateStr] = (this[dateStr] ?: 0) + readingMinutes
                }
                pruneOldDates(this, MAX_DAILY_STATS_RETENTION_DAYS)
            }
            val newPages = stats.dailyPages.toMutableMap().apply {
                if (pagesDelta > 0) {
                    this[dateStr] = (this[dateStr] ?: 0) + pagesDelta
                }
                pruneOldDates(this, MAX_DAILY_STATS_RETENTION_DAYS)
            }
            val newDates = stats.dailyReadDates.toMutableSet().apply {
                add(dateStr)
                pruneOldDateSet(this, MAX_DAILY_STATS_RETENTION_DAYS)
            }
            prefs[KEY_READING_STATS] = json.encodeToString(
                stats.copy(
                    totalPagesRead = stats.totalPagesRead + pagesDelta,
                    comicsFinished = stats.comicsFinished + if (comicFinished) 1 else 0,
                    lastReadAt = now,
                    dailyReadDates = newDates,
                    nightOwlUnlocked = stats.nightOwlUnlocked || isNightOwl,
                    dailyMinutes = newMinutes,
                    dailyPages = newPages
                )
            )
        }
    }

    /**
     * 开始一次阅读会话，将开始时间和漫画 URI 持久化到 DataStore。
     * 当应用进程被系统回收后重新启动时，可据此恢复未结算的阅读时长。
     */
    suspend fun startReadingSession(uri: Uri) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_READING_SESSION_START] = System.currentTimeMillis()
            prefs[KEY_READING_SESSION_URI] = uri.toString()
        }
    }

    /**
     * 结束当前阅读会话，将累计时长计入阅读统计，并清除持久化的会话标记。
     * [expectedUri] 用于校验会话是否属于当前漫画；传入 null 时强制结算。
     * 读、算、清在同一个 DataStore edit 中完成，避免并发调用导致重复统计。
     */
    suspend fun endReadingSession(expectedUri: Uri? = null) {
        var minutesToAdd = 0
        context.userDataStore.edit { prefs ->
            val start = prefs[KEY_READING_SESSION_START] ?: 0L
            val savedUri = prefs[KEY_READING_SESSION_URI]
            if (start > 0L && (expectedUri == null || savedUri == expectedUri.toString())) {
                val elapsed = ((System.currentTimeMillis() - start) / 60000).toInt()
                minutesToAdd = elapsed.coerceAtMost(MAX_READING_SESSION_MINUTES)
            }
            prefs.remove(KEY_READING_SESSION_START)
            prefs.remove(KEY_READING_SESSION_URI)
        }
        if (minutesToAdd > 0) {
            updateReadingStats(readingMinutes = minutesToAdd)
        }
    }

    /**
     * 应用启动时调用：若存在未正常结束的阅读会话，将时长按上限计入统计并清理标记，
     * 避免系统杀进程后阅读时长丢失。
     */
    suspend fun recoverReadingSession() {
        var minutesToAdd = 0
        context.userDataStore.edit { prefs ->
            val start = prefs[KEY_READING_SESSION_START] ?: return@edit
            val now = System.currentTimeMillis()
            val elapsedMinutes = ((now - start) / 60000).toInt()
            if (elapsedMinutes in 1..MAX_SESSION_RECOVERY_MINUTES) {
                minutesToAdd = elapsedMinutes
            }
            prefs.remove(KEY_READING_SESSION_START)
            prefs.remove(KEY_READING_SESSION_URI)
        }
        if (minutesToAdd > 0) {
            updateReadingStats(readingMinutes = minutesToAdd)
        }
    }

    fun getReadingGoals(): Flow<ReadingGoals> = context.userDataStore.data.map { prefs ->
        parseReadingGoals(prefs[KEY_READING_GOALS])
    }.flowOn(Dispatchers.IO)

    suspend fun saveReadingGoals(goals: ReadingGoals) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_READING_GOALS] = json.encodeToString(goals)
        }
    }

    /**
     * 在一个 DataStore 读取事务中同时获取目标与统计，避免分别读取时产生竞态。
     */
    suspend fun getGoalsAndStats(): Pair<ReadingGoals, ReadingStats> {
        return context.userDataStore.data.first().let { prefs ->
            parseReadingGoals(prefs[KEY_READING_GOALS]) to parseReadingStats(prefs[KEY_READING_STATS])
        }
    }

    fun getGoalHistory(): Flow<List<GoalHistoryEntry>> = context.userDataStore.data.map { prefs ->
        parseGoalHistory(prefs[KEY_GOAL_HISTORY])
    }.flowOn(Dispatchers.IO)

    suspend fun recordGoalAchievement(date: String, pagesAchieved: Boolean, minutesAchieved: Boolean) {
        context.userDataStore.edit { prefs ->
            val current = parseGoalHistory(prefs[KEY_GOAL_HISTORY]).associateBy { it.date }.toMutableMap()
            val existing = current[date]
            current[date] = GoalHistoryEntry(
                date = date,
                pagesAchieved = existing?.pagesAchieved == true || pagesAchieved,
                minutesAchieved = existing?.minutesAchieved == true || minutesAchieved
            )
            val trimmed = current.values.sortedByDescending { it.date }.take(MAX_GOAL_HISTORY_DAYS)
            prefs[KEY_GOAL_HISTORY] = json.encodeToString(ListSerializer(GoalHistoryEntry.serializer()), trimmed)
        }
    }

    /**
     * 本地备份恢复时合并阅读统计。
     * 在 [SettingsRepository.importAllData] 的主 edit 事务之外调用，避免 DataStore 嵌套 edit。
     */
    suspend fun mergeReadingStats(backupStats: ReadingStats, overwrite: Boolean) {
        context.userDataStore.edit { prefs ->
            val existingStats = if (overwrite) ReadingStats() else parseReadingStats(prefs[KEY_READING_STATS])
            prefs[KEY_READING_STATS] = json.encodeToString(existingStats.mergeWith(backupStats))
        }
    }

    private fun parseReadingStats(raw: String?): ReadingStats {
        if (raw.isNullOrBlank()) return ReadingStats()
        return runCatching { json.decodeFromString<ReadingStats>(raw) }.getOrDefault(ReadingStats())
    }

    private fun parseReadingGoals(raw: String?): ReadingGoals {
        if (raw.isNullOrBlank()) return ReadingGoals()
        return runCatching { json.decodeFromString<ReadingGoals>(raw) }.getOrDefault(ReadingGoals())
    }

    private fun parseGoalHistory(raw: String?): List<GoalHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(GoalHistoryEntry.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    private fun pruneOldDates(map: MutableMap<String, Int>, keepDays: Int) {
        if (map.size <= keepDays) return
        val threshold = LocalDate.now().minusDays(keepDays.toLong())
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
        map.keys.filter {
            runCatching { LocalDate.parse(it, fmt).isBefore(threshold) }.getOrDefault(false)
        }.forEach { map.remove(it) }
    }

    private fun pruneOldDateSet(set: MutableSet<String>, keepDays: Int) {
        if (set.size <= keepDays) return
        val threshold = LocalDate.now().minusDays(keepDays.toLong())
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
        set.removeAll {
            runCatching { LocalDate.parse(it, fmt).isBefore(threshold) }.getOrDefault(false)
        }
    }
}
