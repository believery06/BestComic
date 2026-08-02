package com.mangareader.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android TTS 朗读管理器。
 *
 * 支持朗读当前页面文本、自动翻页朗读、语速/音调调整。
 * 当页面文本不存在时通过 [onNeedNextPage] 回调请求加载下一页。
 */
class TtsManager(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private val initialized = AtomicBoolean(false)
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()
    private val _initSuccess = MutableStateFlow<Boolean?>(null)
    /** TTS 引擎初始化结果：null 为未初始化，true/false 为成功/失败。 */
    val initSuccess: StateFlow<Boolean?> = _initSuccess.asStateFlow()

    private var onPageFinished: ((utteranceId: String) -> Unit)? = null
    private var onNeedNextPage: (() -> Unit)? = null
    private var pendingText: String? = null
    private var pendingUtteranceId: String? = null
    private var pendingSpeed: Float = 1.0f
    private var pendingPitch: Float = 1.0f

    init {
        try {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    initialized.set(true)
                    _initSuccess.value = true
                    tts?.language = Locale.getDefault()
                    setSpeed(pendingSpeed)
                    setPitch(pendingPitch)
                    setupProgressListener()
                    pendingText?.let { speakInternal(it, pendingUtteranceId ?: UUID.randomUUID().toString()) }
                    pendingText = null
                    pendingUtteranceId = null
                } else {
                    _initSuccess.value = false
                    Log.w(TAG, "TTS 初始化失败: $status")
                }
            }
        } catch (e: Exception) {
            _initSuccess.value = false
            Log.w(TAG, "TTS 初始化异常", e)
        }
    }

    private fun setupProgressListener() {
        try {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    // TTS 引擎回调可能运行在工作线程，切回主线程再触发 UI/ViewModel 操作。
                    // 将 utteranceId 回传，便于调用方过滤过期回调。
                    utteranceId?.let { id -> mainHandler.post { onPageFinished?.invoke(id) } }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post { _isSpeaking.value = false }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    // 高版本 Android 走此回调
                    mainHandler.post { _isSpeaking.value = false }
                }
            })
        } catch (_: Exception) {}
    }

    fun setCallbacks(
        onPageFinished: ((utteranceId: String) -> Unit)? = null,
        onNeedNextPage: (() -> Unit)? = null
    ) {
        this.onPageFinished = onPageFinished
        this.onNeedNextPage = onNeedNextPage
    }

    fun setSpeed(speed: Float) {
        pendingSpeed = speed.coerceIn(0.25f, 4.0f)
        val engine = tts
        if (initialized.get() && engine != null) {
            try {
                engine.setSpeechRate(pendingSpeed)
            } catch (_: Exception) {}
        }
    }

    fun setPitch(pitch: Float) {
        pendingPitch = pitch.coerceIn(0.25f, 4.0f)
        val engine = tts
        if (initialized.get() && engine != null) {
            try {
                engine.setPitch(pendingPitch)
            } catch (_: Exception) {}
        }
    }

    /**
     * 朗读指定文本。若文本为空则回调请求下一页。
     * @param utteranceId 调用方生成的本次朗读唯一标识，用于回调过滤；为空时内部生成。
     * @return 实际使用的 utteranceId
     */
    fun speak(text: String?, utteranceId: String? = null): String {
        val trimmed = text?.trim()
        val id = utteranceId ?: UUID.randomUUID().toString()
        if (trimmed.isNullOrEmpty()) {
            mainHandler.post { onNeedNextPage?.invoke() }
            return id
        }
        if (!initialized.get()) {
            pendingText = trimmed
            pendingUtteranceId = id
            return id
        }
        speakInternal(trimmed, id)
        return id
    }

    private fun speakInternal(text: String, utteranceId: String) {
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        try {
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            val speaking = result != -1
            mainHandler.post { _isSpeaking.value = speaking }
        } catch (e: Exception) {
            Log.w(TAG, "TTS 朗读失败", e)
            mainHandler.post { _isSpeaking.value = false }
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
        // 回调可能来自工作线程，统一 post 到主线程更新状态流。
        mainHandler.post { _isSpeaking.value = false }
    }

    fun shutdown() {
        stop()
        try {
            // 移除监听器，避免引擎内部继续回调已释放的闭包
            tts?.setOnUtteranceProgressListener(null)
            tts?.shutdown()
        } catch (_: Exception) {}
        initialized.set(false)
        // 释放回调引用与 TTS 实例，避免生命周期外被意外触发或泄漏
        onPageFinished = null
        onNeedNextPage = null
        pendingText = null
        tts = null
    }

    companion object {
        private const val TAG = "TtsManager"
    }
}
