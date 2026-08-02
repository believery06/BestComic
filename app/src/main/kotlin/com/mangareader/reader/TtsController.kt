package com.mangareader.reader

import android.util.Log
import com.mangareader.utils.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * TTS 控制器：封装 TTS 引擎包装、朗读当前页、空页跳过计数与 utterance ID 跟踪。
 * 从 ReaderViewModel 中提取，使 ViewModel 专注于状态聚合。
 */
class TtsController(
    private val ttsManager: TtsManager,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TtsController"
        private const val MAX_TTS_EMPTY_SKIP = 10
    }

    val isSpeaking: kotlinx.coroutines.flow.StateFlow<Boolean> get() = ttsManager.isSpeaking
    val ttsInitSuccess: Boolean? get() = ttsManager.initSuccess.value

    private val _currentTtsUtteranceId = AtomicReference<String?>(null)
    val currentTtsUtteranceId: AtomicReference<String?> get() = _currentTtsUtteranceId

    private val _ttsEmptySkipCount = AtomicInteger(0)
    val ttsEmptySkipCount: AtomicInteger get() = _ttsEmptySkipCount

    private var ttsJob: Job? = null

    fun stop() {
        ttsManager.stop()
    }

    fun shutdown() {
        ttsManager.stop()
        ttsManager.setCallbacks(null, null)
        ttsManager.shutdown()
    }

    fun setCallbacks(
        onPageFinished: ((String) -> Unit)? = null,
        onNeedNextPage: (() -> Unit)? = null
    ) {
        ttsManager.setCallbacks(onPageFinished, onNeedNextPage)
    }

    fun reset() {
        ttsJob?.cancel()
        currentTtsUtteranceId.set(null)
        ttsEmptySkipCount.set(0)
    }

    /**
     * 朗读当前页文本。
     * @param currentPage 当前页码
     * @param getPageText 获取页面文本的挂起函数
     * @param onSkipPage 翻页回调（当当前页无文本时）
     */
    fun speakCurrentPage(
        currentPage: Int,
        getPageText: suspend (Int) -> String?,
        onSkipPage: () -> Unit
    ) {
        ttsJob?.cancel()
        currentTtsUtteranceId.set(null)
        ttsJob = scope.launch(start = CoroutineStart.LAZY) {
            val text = withContext(Dispatchers.IO) { getPageText(currentPage) }
            if (!isActive) return@launch
            if (!text.isNullOrBlank()) {
                ttsEmptySkipCount.set(0)
                currentTtsUtteranceId.set(ttsManager.speak(text))
            } else {
                // 当前页无文本，尝试继续翻到下一页；连续空页过多时停止，防止死循环
                if (ttsEmptySkipCount.get() >= MAX_TTS_EMPTY_SKIP) {
                    ttsManager.stop()
                    Log.w(TAG, "TTS 连续 $MAX_TTS_EMPTY_SKIP 页无文本，已自动停止")
                    return@launch
                }
                ttsEmptySkipCount.incrementAndGet()
                onSkipPage()
            }
        }.also { job ->
            job.invokeOnCompletion { throwable ->
                if (throwable != null) {
                    Log.w(TAG, "TTS 朗读取消或失败: ${throwable.message}")
                }
            }
            job.start()
        }
    }

    fun cancelJob() {
        ttsJob?.cancel()
        ttsJob = null
    }
}
