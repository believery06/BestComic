package com.mangareader.navigation

import android.net.Uri

/**
 * 应用级导航路由定义。
 *
 * ChapterBrowser 与 Reader 通过 Navigation 路由参数传递漫画 URI，
 * 使用 Uri.encode/Uri.decode 对 SAF content:// URI 进行安全编码，
 * 避免特殊字符（/、:）在路由字符串中损坏。
 */
sealed class Screen(val route: String) {
    data object Bookshelf : Screen("bookshelf")
    data object Browser : Screen("browser")
    data object ChapterBrowser : Screen("chapter_browser/{comicUri}") {
        fun createRoute(comicUri: Uri): String =
            "chapter_browser/${Uri.encode(comicUri.toString())}"
    }
    data object Reader : Screen("reader/{comicUri}?chapterIndex={chapterIndex}") {
        /**
         * @param chapterIndex 指定打开的分卷在分卷列表中的下标；-1 表示整本/续读模式，
         * 由阅读器根据已保存的阅读进度恢复页码。
         */
        fun createRoute(comicUri: Uri, chapterIndex: Int = -1): String =
            "reader/${Uri.encode(comicUri.toString())}?chapterIndex=$chapterIndex"
    }
    data object Settings : Screen("settings")
    data object History : Screen("history")
    data object CacheManagement : Screen("cache_management")
    data object Statistics : Screen("statistics")
    data object GestureSettings : Screen("gesture_settings")
}
