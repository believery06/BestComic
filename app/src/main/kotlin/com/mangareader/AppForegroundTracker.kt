package com.mangareader

/**
 * 简单应用前台状态追踪器。
 *
 * [MainActivity] 在 onResume/onPause 中更新该标志，供业务逻辑判断
 * 当前是否处于前台，以决定使用 Toast 还是通知。
 */
object AppForegroundTracker {

    @Volatile
    var isInForeground: Boolean = false
        private set

    fun enterForeground() {
        isInForeground = true
    }

    fun enterBackground() {
        isInForeground = false
    }
}
