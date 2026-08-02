package com.mangareader.reader

import android.app.Application
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.mangareader.AppForegroundTracker
import com.mangareader.MangaReaderApplication
import com.mangareader.R
import com.mangareader.data.ReadingStatsRepository
import com.mangareader.utils.NotificationUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 阅读会话统计跟踪器。
 *
 * 负责阅读会话的生命周期（开始/结束/当前时长）、翻页页数统计、每日目标检测与提示。
 * 从 [ReaderViewModel] 拆分出来，隔离与 UI 无关的统计逻辑，降低 ViewModel 复杂度。
 */
class ReadingStatsTracker(
    private val app: Application,
    private val statsRepository: ReadingStatsRepository,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    companion object {
        private const val TAG = "ReadingStatsTracker"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    @Volatile
    private var readingSessionStart: Long = 0L
    private val readingSessionActive = AtomicBoolean(false)
    private var goalToastShownDate: String = ""

    /** 返回当前阅读会话已持续毫秒数；会话未激活时返回 0。 */
    fun currentSessionDurationMs(): Long {
        val start = readingSessionStart
        return if (start > 0L && readingSessionActive.get()) {
            System.currentTimeMillis() - start
        } else 0L
    }

    /** 开始一次新的阅读会话。 */
    fun startSession() {
        readingSessionStart = System.currentTimeMillis()
        readingSessionActive.set(true)
    }

    /**
     * 结束阅读会话并结算时长。
     * 使用 [MangaReaderApplication.applicationScope] 在后台执行，避免 ViewModel 销毁导致保存丢失。
     */
    fun endSession(uri: Uri?) {
        if (readingSessionActive.compareAndSet(true, false)) {
            readingSessionStart = 0L
            MangaReaderApplication.applicationScope.launch(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(timeoutMs) {
                        statsRepository.endReadingSession(uri)
                        checkGoalCompletion()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "结算阅读时长失败", e)
                }
            }
        }
    }

    /**
     * 记录翻页进度并更新阅读统计。
     *
     * @return 更新后的 [lastRecordedPage]，调用方应覆盖原值以避免重复统计。
     */
    suspend fun recordProgress(currentPage: Int, lastRecordedPage: Int, pageCount: Int): Int {
        val delta = (currentPage - lastRecordedPage).coerceAtLeast(0)
        val finished = currentPage >= (pageCount - 1).coerceAtLeast(0)
        if (delta > 0 || finished) {
            statsRepository.updateReadingStats(pagesDelta = delta, comicFinished = finished)
            checkGoalCompletion()
        }
        return maxOf(currentPage, lastRecordedPage)
    }

    /**
     * 检查今日阅读目标是否刚达成，并弹出提示或通知。
     * 已提示过的日期会被记录，避免同一天重复打扰。
     */
    suspend fun checkGoalCompletion() {
        val (goals, stats) = statsRepository.getGoalsAndStats()
        if (goals.dailyPages <= 0 && goals.dailyMinutes <= 0) return
        val today = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
            .format(LocalDate.now())
        if (goalToastShownDate == today) return

        val pagesDone = goals.dailyPages > 0 && stats.todayPages() >= goals.dailyPages
        val minutesDone = goals.dailyMinutes > 0 && stats.todayMinutes() >= goals.dailyMinutes
        if (pagesDone || minutesDone) {
            goalToastShownDate = today
            val parts = mutableListOf<String>()
            if (pagesDone) {
                parts.add(app.getString(R.string.reader_goal_pages_format, stats.todayPages(), goals.dailyPages))
            }
            if (minutesDone) {
                parts.add(app.getString(R.string.reader_goal_minutes_format, stats.todayMinutes(), goals.dailyMinutes))
            }
            statsRepository.recordGoalAchievement(
                date = today,
                pagesAchieved = pagesDone,
                minutesAchieved = minutesDone
            )
            showGoalToast(parts, pagesDone, minutesDone, stats, goals)
        }
    }

    private suspend fun showGoalToast(
        parts: List<String>,
        pagesDone: Boolean,
        minutesDone: Boolean,
        stats: com.mangareader.data.ReadingStats,
        goals: com.mangareader.data.ReadingGoals
    ) {
        val msg = if (parts.isEmpty()) {
            app.getString(R.string.reader_goal_toast_title)
        } else {
            app.getString(R.string.reader_goal_toast_with_parts, parts.joinToString(app.getString(R.string.list_separator)))
        }
        if (AppForegroundTracker.isInForeground) {
            withContext(Dispatchers.Main) {
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
            }
        } else {
            NotificationUtils.showGoalCompletedNotification(
                app,
                pagesText = if (pagesDone) app.getString(R.string.reader_goal_pages_format, stats.todayPages(), goals.dailyPages) else null,
                minutesText = if (minutesDone) app.getString(R.string.reader_goal_minutes_format, stats.todayMinutes(), goals.dailyMinutes) else null
            )
        }
    }
}
