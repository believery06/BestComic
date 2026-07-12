package com.mangareader

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                MangaReaderApp(startUri = intent?.data)
            }
        }
    }

    /**
     * 音量键翻页：当 ReaderScreen 启用该设置时，音量上=上一页，音量下=下一页。
     * ReaderScreen 通过 ReaderKeyEvents 单例注册回调与开关。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (ReaderKeyEvents.volumeKeyNavEnabled) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    ReaderKeyEvents.onPreviousPage?.invoke()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    ReaderKeyEvents.onNextPage?.invoke()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

/**
 * 用于在 MainActivity（接收音量键事件）与 ReaderScreen（持有 ViewModel）之间
 * 传递翻页回调。ReaderScreen 在进入阅读时注册，退出时清除。
 */
object ReaderKeyEvents {
    @Volatile
    var volumeKeyNavEnabled: Boolean = false
    @Volatile
    var onNextPage: (() -> Unit)? = null
    @Volatile
    var onPreviousPage: (() -> Unit)? = null
}
