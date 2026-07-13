package com.mangareader

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangareader.bookshelf.BookshelfScreen
import com.mangareader.browser.BrowserScreen
import com.mangareader.chapters.ChapterBrowserScreen
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicHolder
import com.mangareader.data.ComicType
import com.mangareader.data.SettingsRepository
import com.mangareader.onboarding.OnboardingHost
import com.mangareader.reader.ReaderScreen
import com.mangareader.settings.SettingsScreen
import com.mangareader.ui.theme.MangaReaderTheme
import com.mangareader.utils.CrashHandler
import com.mangareader.utils.CrashLogDialog

/**
 * Navigation routes. The Reader route takes no arguments — the ComicEntry
 * is passed via [ComicHolder] to avoid corrupting SAF content:// URIs
 * through Navigation's Uri.decode() (which turns %2F → / and %3A → :).
 */
sealed class Screen(val route: String) {
    data object Bookshelf : Screen("bookshelf")
    data object Browser : Screen("browser")
    data object ChapterBrowser : Screen("chapter_browser")
    data object Reader : Screen("reader")
    data object Settings : Screen("settings")
}

@Composable
fun MangaReaderApp(startUri: Uri? = null) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val appTheme by settingsRepository.readerSettings
        .collectAsStateWithLifecycle(initialValue = com.mangareader.data.ReaderSettings())
    var showHelpManually by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 启动时检测上次是否有异常退出，若有则记录待展示
    var pendingCrashLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        val logs = CrashHandler.consumeCrashLogs()
        if (logs.isNotEmpty()) {
            pendingCrashLogs = logs
        }
    }

    LaunchedEffect(startUri) {
        startUri?.let { uri ->
            var type = com.mangareader.parser.ParserFactory.detectType(uri.lastPathSegment ?: "")
            if (type == ComicType.UNKNOWN) {
                val mime = context.contentResolver.getType(uri)
                type = when (mime) {
                    "application/zip", "application/x-cbz" -> ComicType.CBZ
                    "application/x-rar-compressed", "application/vnd.rar" -> ComicType.CBR
                    "application/x-7z-compressed" -> ComicType.CB7
                    "application/epub+zip" -> ComicType.EPUB
                    "application/pdf" -> ComicType.PDF
                    else -> ComicType.UNKNOWN
                }
            }
            if (type != ComicType.UNKNOWN) {
                ComicHolder.entry = ComicEntry(
                    uri = uri,
                    title = uri.lastPathSegment ?: "漫画",
                    type = type
                )
                // 外部打开（分享/Intent）直接阅读，不经过分卷浏览
                navController.navigate(Screen.Reader.route)
            }
        }
    }

    val openComic: (ComicEntry) -> Unit = { entry ->
        ComicHolder.entry = entry
        navController.navigate(Screen.ChapterBrowser.route)
    }

    OnboardingHost(
        onOpenHelp = { showHelpManually = true },
        settingsRepository = settingsRepository,
    ) {
        MangaReaderTheme(theme = appTheme.appTheme) {
            NavHost(navController = navController, startDestination = Screen.Bookshelf.route) {
            composable(Screen.Bookshelf.route) {
                BookshelfScreen(
                    onOpenComic = openComic,
                    onOpenBrowser = { navController.navigate(Screen.Browser.route) },
                    onShowHelpCallback = { showHelpManually = true },
                    onOpenSettings = { navController.navigate(Screen.Settings.route) }
                )
            }
            composable(Screen.Browser.route) {
                BrowserScreen(
                    onOpenComic = openComic,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.ChapterBrowser.route) {
                val entry = remember { ComicHolder.entry }
                if (entry != null) {
                    ChapterBrowserScreen(
                        comicEntry = entry,
                        onOpenReader = { navController.navigate(Screen.Reader.route) },
                        onBack = { navController.popBackStack() }
                    )
                } else {
                    LaunchedEffect(Unit) {
                        navController.navigate(Screen.Bookshelf.route) {
                            popUpTo(Screen.ChapterBrowser.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            }
            composable(Screen.Reader.route) {
                val entry = remember { ComicHolder.entry }
                ReaderScreen(
                    comicEntry = entry,
                    onBack = {
                        ComicHolder.chapter = null
                        if (!navController.popBackStack()) {
                            navController.navigate(Screen.Bookshelf.route) {
                                popUpTo(Screen.Reader.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    },
                    onOpenSettings = {
                        navController.navigate(Screen.Settings.route) { launchSingleTop = true }
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
        }
    }

    if (showHelpManually) {
        com.mangareader.onboarding.ManualHelpDialog(
            onClose = { showHelpManually = false },
            onResetHelpPreference = {
                scope.launch {
                    settingsRepository.setHelpDontShow(false)
                }
                showHelpManually = false
            }
        )
    }

    if (pendingCrashLogs.isNotEmpty()) {
        CrashLogDialog(
            logs = pendingCrashLogs,
            onDismiss = { pendingCrashLogs = emptyList() }
        )
    }
}
