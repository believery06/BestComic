package com.mangareader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {

    private val startUriState = mutableStateOf<Uri?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // 无论用户是否授权，阅读目标前台 Toast 仍可工作；
        // 后台通知仅在授权后生效。
    }

    companion object {
        private const val PREFS_NAME = "main_activity_prefs"
        private const val KEY_NOTIFICATION_REQUESTED = "notification_requested"
        private const val KEY_START_URI = "start_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 优先恢复已保存的启动 URI（进程重建场景），否则使用当前 Intent 数据；
        // 同时校验 scheme，过滤掉非法或不可读的外部 URI。
        val restoredUri = savedInstanceState.getStartUri()
        val intentUri = intent?.data?.takeIf { isValidExternalUri(it) }
        startUriState.value = restoredUri ?: intentUri
        enableEdgeToEdge()

        // Android 13+ 请求通知权限，用于阅读目标达成后台提醒。
        // 仅首次启动时请求一次，避免用户拒绝后每次启动都弹出权限框。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val alreadyRequested = prefs.getBoolean(KEY_NOTIFICATION_REQUESTED, false)
            if (!granted && !alreadyRequested) {
                prefs.edit().putBoolean(KEY_NOTIFICATION_REQUESTED, true).apply()
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            // 根背景交由 MangaReaderApp 内部的主题化 Surface 提供，
            // 此处不再包一层未主题化的 Material3 Surface（其默认浅色会导致退出白屏）。
            MangaReaderApp(startUri = startUriState.value)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        startUriState.value = intent?.data?.takeIf { isValidExternalUri(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 保存启动 URI，避免进程被系统回收后外部打开流程丢失。
        startUriState.value?.let { outState.putParcelable(KEY_START_URI, it) }
    }

    /**
     * 校验外部传入的 URI 是否可接受。
     * 仅允许 content://（其他应用通过 FileProvider 共享）和 file://（本地文件）。
     */
    private fun isValidExternalUri(uri: Uri): Boolean {
        return uri.scheme == "content" || (uri.scheme == "file" && !uri.path.isNullOrBlank())
    }

    /**
     * 从 savedInstanceState 中恢复启动 URI，兼容 API 33+ 的 Bundle#getParcelable 签名变化。
     */
    private fun Bundle?.getStartUri(): Uri? {
        if (this == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(KEY_START_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelable(KEY_START_URI)
        }
    }

    override fun onResume() {
        super.onResume()
        AppForegroundTracker.enterForeground()
    }

    override fun onPause() {
        super.onPause()
        AppForegroundTracker.enterBackground()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 仅当 Activity 真正结束时才清理音量键回调；配置变化导致的重建不应清空
        // 当前 ReaderScreen 注册的有效处理器，否则重建后音量键翻页会失效。
        if (isFinishing) {
            ReaderKeyEvents.clear()
        }
    }

    /**
     * 音量键翻页：当 ReaderScreen 启用该设置时，音量上=上一页，音量下=下一页。
     * ReaderScreen 通过 ReaderKeyEvents 单例注册处理器与开关。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 仅处理首次按下，忽略长按重复事件，避免长按音量键导致快速连翻
        if (event?.repeatCount != 0) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> if (ReaderKeyEvents.handleVolumeUp()) true else super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (ReaderKeyEvents.handleVolumeDown()) true else super.onKeyDown(keyCode, event)
            else -> super.onKeyDown(keyCode, event)
        }
    }
}

/**
 * 音量键翻页处理器接口，由 ReaderScreen 实现并通过 [ReaderKeyEvents] 弱引用注册。
 * 使用弱引用可在 ReaderScreen 离开 Composition 后自动释放，避免单例长期持有
 * ViewModel 闭包造成内存泄漏；同时 ReaderScreen 通过 remember 强引用本接口实例，
 * 确保阅读器显示期间处理器不会被 GC 回收。
 */
interface VolumeKeyHandler {
    fun onVolumeUp(): Boolean
    fun onVolumeDown(): Boolean
}

/**
 * 用于在 MainActivity（接收音量键事件）与 ReaderScreen（持有 ViewModel）之间
 * 传递翻页回调。ReaderScreen 在进入阅读时注册，退出时清除。
 *
 * 使用 [WeakReference] 持有 [VolumeKeyHandler]，避免单例长期强引用 ViewModel；
 * ReaderScreen 自身 remember 住处理器实例，保证阅读器显示期间翻页可用。
 */
object ReaderKeyEvents {
    @Volatile
    var volumeKeyNavEnabled: Boolean = false

    private var handlerRef: WeakReference<VolumeKeyHandler>? = null

    fun setHandler(handler: VolumeKeyHandler?) {
        handlerRef = handler?.let { WeakReference(it) }
    }

    fun clear() {
        volumeKeyNavEnabled = false
        handlerRef = null
    }

    fun handleVolumeUp(): Boolean {
        if (!volumeKeyNavEnabled) return false
        return handlerRef?.get()?.onVolumeUp() ?: false
    }

    fun handleVolumeDown(): Boolean {
        if (!volumeKeyNavEnabled) return false
        return handlerRef?.get()?.onVolumeDown() ?: false
    }
}
