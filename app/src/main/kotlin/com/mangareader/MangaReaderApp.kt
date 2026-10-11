package com.mangareader

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.mangareader.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mangareader.bookshelf.BookshelfScreen
import com.mangareader.browser.BrowserScreen
import com.mangareader.chapters.ChapterBrowserScreen
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicType
import com.mangareader.data.SettingsRepository
import com.mangareader.navigation.Screen
import com.mangareader.onboarding.OnboardingHost
import com.mangareader.reader.ReaderScreen
import com.mangareader.settings.SettingsScreen
import com.mangareader.ui.theme.MangaReaderTheme
import com.mangareader.utils.CrashHandler
import com.mangareader.utils.CrashLogDialog
import com.mangareader.utils.FileManager

@Composable
fun MangaReaderApp(startUri: Uri? = null) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val appTheme by settingsRepository.readerSettings
        .collectAsStateWithLifecycle(initialValue = com.mangareader.data.ReaderSettings())
    var showHelpManually by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 已处理的外部启动 URI，避免配置变更或进程恢复后重复导航。
    var consumedStartUri by rememberSaveable { mutableStateOf<String?>(null) }

    // 启动时检测上次是否有异常退出，若有则记录待展示。文件读取在 IO 线程执行，避免阻塞主线程。
    var pendingCrashLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        val logs = withContext(Dispatchers.IO) { CrashHandler.readCrashLogs() }
        if (logs.isNotEmpty()) {
            pendingCrashLogs = logs
        }
    }

    LaunchedEffect(startUri) {
        startUri?.let { rawUri ->
            // 同一 URI 已在外部 Intent 处理流程中消费过，跳过避免重复导航。
            if (rawUri.toString() == consumedStartUri) return@let

            // 将外部 content:// URI 转换为可持续访问的 URI（持久权限或私有缓存），
            // 避免进程重建或权限过期后无法从历史记录再次打开。
            val uri = FileManager.resolveExternalUri(context, rawUri) ?: rawUri

            var type = com.mangareader.parser.ParserFactory.detectType(uri.lastPathSegment ?: "")
            if (type == ComicType.UNKNOWN) {
                val mime = context.contentResolver.getType(uri)
                type = when (mime) {
                    "application/zip", "application/x-cbz" -> ComicType.CBZ
                    "application/x-rar-compressed", "application/vnd.rar" -> ComicType.CBR
                    "application/x-7z-compressed" -> ComicType.CB7
                    "application/epub+zip" -> ComicType.EPUB
                    "application/pdf" -> ComicType.PDF
                    "application/x-mobipocket-ebook" -> ComicType.MOBI
                    else -> ComicType.UNKNOWN
                }
            }
            if (type != ComicType.UNKNOWN) {
                consumedStartUri = rawUri.toString()
                // 外部打开（分享/Intent）也应先进入章节浏览，与项目硬约束保持一致。
                navController.navigate(Screen.ChapterBrowser.createRoute(uri)) {
                    // 避免多个章节浏览页堆叠，返回时回到书架。
                    popUpTo(Screen.Bookshelf.route) { inclusive = false }
                    launchSingleTop = true
                }
            } else {
                android.widget.Toast.makeText(
                    context,
                    R.string.external_intent_unknown_type_toast,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val openComic: (ComicEntry) -> Unit = remember(navController) {
        { entry ->
            navController.navigate(Screen.ChapterBrowser.createRoute(entry.uri))
        }
    }

    val openReader: (ComicEntry) -> Unit = remember(navController) {
        { entry ->
            navController.navigate(Screen.Reader.createRoute(entry.uri))
        }
    }

    val onOpenBrowser = remember(navController) { { navController.navigate(Screen.Browser.route) } }
    val onShowHistory = remember(navController) { { navController.navigate(Screen.History.route) } }
    val onOpenSettings = remember(navController) { { navController.navigate(Screen.Settings.route) } }

    OnboardingHost(
        onOpenHelp = { showHelpManually = true },
        settingsRepository = settingsRepository,
    ) {
        MangaReaderTheme(
            theme = appTheme.appTheme,
            customSeedColor = appTheme.customSeedColor,
            themeAutoMode = appTheme.themeAutoMode
        ) {
            // 主题化根容器：统一应用深色/主题底色，避免此前位于主题之外、
            // 回退到 Material3 默认浅色（白）Surface 造成的退出白屏。
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                NavHost(navController = navController, startDestination = Screen.Bookshelf.route) {
            composable(Screen.Bookshelf.route) {
                BookshelfScreen(
                    onOpenComic = openComic,
                    onOpenReader = openReader,
                    onOpenBrowser = onOpenBrowser,
                    onShowHistory = onShowHistory,
                    onShowHelpCallback = { showHelpManually = true },
                    onOpenSettings = onOpenSettings
                )
            }
            composable(Screen.Browser.route) {
                BrowserScreen(
                    onOpenComic = openComic,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = Screen.ChapterBrowser.route,
                arguments = listOf(navArgument("comicUri") { type = NavType.StringType })
            ) { backStackEntry ->
                // NavType.StringType 路径参数已由 Navigation 自动 URLDecode 一次，
                // 不能再手动 Uri.decode，否则会把 SAF documentId 里的 %2F/%3A 二次解码，
                // 导致 content:// 文档 URI 被破坏、任何格式漫画都无法识别。
                val comicUriString = backStackEntry.arguments?.getString("comicUri")
                val comicUri = comicUriString?.let { Uri.parse(it) }
                if (comicUri != null) {
                    ChapterBrowserScreen(
                        comicUri = comicUri,
                        onOpenReader = { chapterIndex ->
                            navController.navigate(Screen.Reader.createRoute(comicUri, chapterIndex))
                        },
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
            composable(
                route = Screen.Reader.route,
                arguments = listOf(
                    navArgument("comicUri") { type = NavType.StringType },
                    navArgument("chapterIndex") { type = NavType.IntType; defaultValue = -1 }
                )
            ) { backStackEntry ->
                // 同上：路径参数已由 Navigation 自动解码，禁止二次 Uri.decode。
                val comicUriString = backStackEntry.arguments?.getString("comicUri")
                val chapterIndex = backStackEntry.arguments?.getInt("chapterIndex", -1) ?: -1
                val comicUri = comicUriString?.let { Uri.parse(it) }
                if (comicUri != null) {
                    ReaderScreen(
                        comicUri = comicUri,
                        chapterIndex = chapterIndex,
                        onBack = {
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
                } else {
                    LaunchedEffect(Unit) {
                        navController.navigate(Screen.Bookshelf.route) {
                            popUpTo(Screen.Reader.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCacheManagement = { navController.navigate(Screen.CacheManagement.route) },
                    onOpenStatistics = { navController.navigate(Screen.Statistics.route) },
                    onOpenGestureSettings = { navController.navigate(Screen.GestureSettings.route) }
                )
            }
            composable(Screen.GestureSettings.route) {
                com.mangareader.settings.GestureSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.History.route) {
                com.mangareader.history.HistoryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenComic = openComic
                )
            }
            composable(Screen.CacheManagement.route) {
                com.mangareader.settings.CacheManagementScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Statistics.route) {
                com.mangareader.stats.StatisticsScreen(
                    onBack = { navController.popBackStack() }
                )
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
                    onDismiss = {
                        CrashHandler.clearCrashLogs()
                        pendingCrashLogs = emptyList()
                    }
                )
            }
        }
    }
}
