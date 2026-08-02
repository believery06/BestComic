package com.mangareader.reader

import com.mangareader.data.ScrollMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 自动翻页控制器：管理自动翻页任务、运行状态与翻页间隔。
 * 从 ReaderViewModel 中提取，使 ViewModel 专注于状态聚合。
 */
class AutoPager(
    private val scope: CoroutineScope
) {
    private var autoPageJob: Job? = null
    var autoPageWasRunning: Boolean = false
        private set

    fun isRunning(): Boolean = autoPageJob?.isActive == true

    fun startAutoPage(
        seconds: Int,
        isMenuVisible: () -> Boolean,
        getState: () -> ReaderViewModel.ReaderUiState,
        loadPageBitmap: suspend (Int) -> Unit,
        jumpToNextChapter: () -> Unit,
        setCurrentPage: (Int) -> Unit,
        onStop: () -> Unit
    ) {
        stopAutoPage()
        autoPageJob = scope.launch {
            var chapterJustChanged = false
            while (isActive) {
                delay(seconds * 1000L * if (chapterJustChanged) 2 else 1)
                chapterJustChanged = false
                if (isMenuVisible()) continue
                val state = getState()
                if (state.totalPages <= 0) continue
                val step = when {
                    !state.settings.dualPage -> 1
                    state.settings.dualPageStartOne -> {
                        val offset = state.settings.dualPageOffset.coerceIn(0, 5)
                        val shifted = (state.currentPage - offset).coerceAtLeast(0)
                        if (shifted % 2 == 0) 2 else 1
                    }
                    else -> {
                        val offset = state.settings.dualPageOffset.coerceIn(0, 5)
                        val shifted = (state.currentPage - offset).coerceAtLeast(0)
                        when (shifted) {
                            0 -> 1
                            else -> if (shifted % 2 == 0) 1 else 2
                        }
                    }
                }
                val next = state.currentPage + step
                if (next >= state.totalPages) {
                    // 到达末尾时尝试进入下一章节，没有下一章再停止
                    jumpToNextChapter()
                    if (getCurrentPage(getState()) == state.currentPage) {
                        stopAutoPage()
                        onStop()
                        break
                    }
                    // 进入新章节后多等待一倍时间，让用户看清封面/标题页。
                    chapterJustChanged = true
                } else {
                    // 先预加载目标页再切换，避免自动翻页时闪烁
                    try {
                        loadPageBitmap(next)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                    setCurrentPage(next)
                }
            }
        }
    }

    fun stopAutoPage() {
        autoPageJob?.cancel()
        autoPageJob = null
    }

    fun setWasRunning(wasRunning: Boolean) {
        autoPageWasRunning = wasRunning
    }

    private fun getCurrentPage(state: ReaderViewModel.ReaderUiState): Int = state.currentPage
}
