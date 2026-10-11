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
        advancePage: () -> Boolean,
        getChapterTitle: () -> String,
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
                // 双页步长仅用于「同卷内下一对页」的预加载；真正的翻页/跨卷统一走
                // ViewModel.nextPage(autoContinue = true)，避免跨卷时跳过新卷首页。
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
                val titleBefore = getChapterTitle()
                // 先预加载目标页再切换，避免自动翻页时闪烁；跨卷时目标页未知则跳过预加载。
                if (next < state.totalPages) {
                    try {
                        loadPageBitmap(next)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                }
                val moved = advancePage()
                if (!moved) {
                    // 已到全书最后一卷末尾
                    stopAutoPage()
                    onStop()
                    break
                }
                // 进入新卷后多等待一倍时间，让用户看清封面/标题页。
                chapterJustChanged = titleBefore != getChapterTitle()
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
}
