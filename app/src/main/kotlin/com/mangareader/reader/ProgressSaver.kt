package com.mangareader.reader

import android.net.Uri
import com.mangareader.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 进度保存器：管理阅读进度防抖写入、翻页计数与统计跟踪。
 * 从 ReaderViewModel 中提取，使 ViewModel 专注于状态聚合。
 */
class ProgressSaver(
    private val settingsRepository: SettingsRepository,
    private val statsTracker: ReadingStatsTracker,
    private val scope: CoroutineScope
) {
    companion object {
        const val PROGRESS_SAVE_DEBOUNCE_MS = 400L
    }

    var lastRecordedPage: Int = -1
        private set
    var flipCount: Int = 0
        private set

    private var progressJob: Job? = null

    /**
     * 打开漫画时设置翻页统计基线（通常为起始页/续读页）。
     * 否则 lastRecordedPage 默认为 -1，续读到第 N 页会把前 N 页全部误算成本次新翻页。
     */
    fun setBaseline(page: Int) {
        lastRecordedPage = page
    }

    fun cancelPendingJob() {
        progressJob?.cancel()
        progressJob = null
    }

    /**
     * 触发进度保存（带防抖）。
     * @param uri 漫画 URI
     * @param clamped 当前页码
     * @param chapter 当前章节标题
     * @param allChapters 分卷列表（null 表示整本模式）
     * @param computeOverallProgress 计算整体进度的函数
     */
    fun scheduleSave(
        uri: Uri,
        clamped: Int,
        chapter: String,
        allChapters: List<com.mangareader.data.ComicChapter>?,
        computeOverallProgress: (Int) -> Pair<Int, Int>,
        totalPages: Int
    ) {
        progressJob?.cancel()
        progressJob = scope.launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            flushProgressAndStats(uri, clamped, chapter, allChapters, computeOverallProgress, totalPages)
        }
    }

    /**
     * 立即刷新进度与统计（无防抖）。用于退出时的兜底保存。
     */
    suspend fun flushNow(
        uri: Uri,
        clamped: Int,
        chapter: String,
        allChapters: List<com.mangareader.data.ComicChapter>?,
        computeOverallProgress: (Int) -> Pair<Int, Int>,
        totalPages: Int
    ) {
        flushProgressAndStats(uri, clamped, chapter, allChapters, computeOverallProgress, totalPages)
    }

    private suspend fun flushProgressAndStats(
        uri: Uri,
        clamped: Int,
        chapter: String,
        allChapters: List<com.mangareader.data.ComicChapter>?,
        computeOverallProgress: (Int) -> Pair<Int, Int>,
        totalPages: Int
    ) {
        // 分卷模式下进度键包含分卷标题，整本漫画模式使用 URI 本身
        // 卷标题必须以「全局页码实际所在的分卷」为准：页内 chapterTitle 为空的散页
        // 会被聚合进以漫画名命名的合成卷，继续按传入 chapter 匹配标题会找不到卷，
        // 导致 chapterStart 累加成总页数、卷内页码恒为 0。
        val effectiveChapter: String? = if (allChapters != null) {
            var acc = 0
            var found: String? = null
            for (c in allChapters) {
                if (clamped in acc until acc + c.pages.size) { found = c.title; break }
                acc += c.pages.size
            }
            found ?: allChapters.lastOrNull()?.title ?: chapter
        } else null
        val progressChapter = effectiveChapter
        // 分卷级键保存「卷内页码」；全局页码 clamped 需要减去该卷起始页。
        val chapterPage = if (allChapters != null) {
            var chapterStart = 0
            for (c in allChapters) {
                if (c.title == effectiveChapter) break
                chapterStart += c.pages.size
            }
            (clamped - chapterStart).coerceAtLeast(0)
        } else {
            clamped
        }
        // 同步更新漫画整体进度，保证书架阅读状态实时刷新
        val (overallProgress, overallTotal) = computeOverallProgress(clamped)
        // 合并为一次 DataStore 事务写入，降低快速翻页时的 IO 开销
        settingsRepository.saveReadingProgress(
            uri,
            progressChapter,
            chapterPage,
            overallProgress,
            overallTotal
        )
        // 记录阅读进度统计（仅累计向前翻页，避免重复计算）
        lastRecordedPage = statsTracker.recordProgress(clamped, lastRecordedPage, totalPages)
    }

    fun incrementFlipCount() {
        flipCount++
    }

    fun resetFlipCount() {
        flipCount = 0
    }
}
