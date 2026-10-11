package com.mangareader.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp as lerpOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as lerpFloat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mangareader.R
import com.mangareader.data.OrientationLock
import com.mangareader.data.ScrollMode
import com.mangareader.data.SettingsRepository
import com.mangareader.utils.rememberBrightnessSensorManager
import com.mangareader.utils.rememberWindowSizeClass
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
    comicUri: Uri,
    chapterIndex: Int = -1,
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: ReaderViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val panelState by viewModel.panelState.collectAsStateWithLifecycle()
    val showMenu by viewModel.showMenu.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val ttsInitSuccess = viewModel.ttsInitSuccess

    val windowSize = rememberWindowSizeClass()
    // 仅宽度 ≥ 600dp 的平板/折叠屏展开态使用大屏幕侧边菜单；
    // 横屏手机高度有限，继续使用底部弹出菜单更合适。
    val isLargeScreen = windowSize == com.mangareader.utils.WindowSizeClass.TABLET

    // 通知 ViewModel 当前屏幕尺寸，用于 inSampleSize 按需采样。
    // remember key 使用稳定的 Int 值（dp），避免 Density 对象变化导致不必要的重组。
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val screenHeightDp = configuration.screenHeightDp
    val screenWidthPx = remember(screenWidthDp) {
        with(density) { screenWidthDp.dp.roundToPx() }
    }
    val screenHeightPx = remember(screenHeightDp) {
        with(density) { screenHeightDp.dp.roundToPx() }
    }
    LaunchedEffect(screenWidthPx, screenHeightPx) {
        viewModel.setScreenSize(screenWidthPx, screenHeightPx)
    }
    // TTS 引擎初始化失败时提示用户检查系统 TTS/语言包。
    LaunchedEffect(ttsInitSuccess, state.settings.ttsEnabled) {
        if (state.settings.ttsEnabled && ttsInitSuccess == false) {
            Toast.makeText(
                context,
                context.getString(R.string.reader_tts_init_failed),
                Toast.LENGTH_LONG
            ).show()
        }
    }
    // 记录当前漫画是否已展示过卷末对话框，切换漫画时重置；使用 rememberSaveable 避免配置变更后重复弹出。
    var chapterEndShown by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(comicUri, chapterIndex) {
        chapterEndShown = false
        viewModel.loadComicFromUri(comicUri, chapterIndex)
    }

    val volumeKeyHandler = remember(viewModel) {
        object : com.mangareader.VolumeKeyHandler {
            override fun onVolumeUp(): Boolean {
                viewModel.previousPage()
                return true
            }
            override fun onVolumeDown(): Boolean {
                viewModel.nextPage()
                return true
            }
        }
    }
    LaunchedEffect(state.settings.volumeKeyNav) {
        com.mangareader.ReaderKeyEvents.volumeKeyNavEnabled = state.settings.volumeKeyNav
    }
    DisposableEffect(volumeKeyHandler) {
        com.mangareader.ReaderKeyEvents.setHandler(volumeKeyHandler)
        onDispose {
            com.mangareader.ReaderKeyEvents.setHandler(null)
            com.mangareader.ReaderKeyEvents.volumeKeyNavEnabled = false
            // 使用 Compat 版本确保低版本能正确恢复系统栏
            val window = (context as? Activity)?.window
            if (window != null) {
                androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
            // 释放阅读器缓存，避免退出后大量占用内存和磁盘
            viewModel.releaseCache()
        }
    }

    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val exitConfirmDisabled by settingsRepository.getReaderExitConfirmDisabled()
        .collectAsStateWithLifecycle(initialValue = false)

    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    val offsetSaver = remember {
        Saver<Offset, List<Float>>(
            save = { listOf(it.x, it.y) },
            restore = { Offset(it[0], it[1]) }
        )
    }
    var panOffset by rememberSaveable(stateSaver = offsetSaver) { mutableStateOf(Offset.Zero) }
    val readerScope = rememberCoroutineScope()
    val animatedZoomTo: (Float, Offset) -> Unit = remember { { targetScale: Float, targetOffset: Offset ->
        val startScale = scale
        val startOffset = panOffset
        readerScope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(180)
            ) { value, _ ->
                scale = lerpFloat(startScale, targetScale, value)
                panOffset = lerpOffset(startOffset, targetOffset, value)
            }
        }
        Unit
    } }
    var showExitConfirm by rememberSaveable { mutableStateOf(false) }
    var showChapterEndDialog by rememberSaveable { mutableStateOf(false) }

    // 手动翻页到达卷末时，ViewModel 发送事件，弹出"跳转下一卷/已是最后一卷"对话框。
    // 自动翻页与 TTS 连续朗读不经过此事件，避免打断 hands-free 阅读。
    LaunchedEffect(Unit) {
        viewModel.readerEvents.collect {
            showChapterEndDialog = true
        }
    }

    // 卷末跳转对话框：derivedStateOf 只在 showChapterEndDialog 变化时重新计算，
    // 避免对话框显示期间频繁读取 ViewModel 状态导致不必要的计算。
    // checkChapterEnd() 返回 Pair(是否在卷末, 是否最后一卷)，这里需要的是第二个分量。
    val chapterEndInfo by remember {
        derivedStateOf {
            if (showChapterEndDialog) {
                viewModel.checkChapterEnd().second to viewModel.getNextChapterTitle()
            } else {
                false to null
            }
        }
    }
    if (showChapterEndDialog) {
        val (isLastChapter, nextChapterTitle) = chapterEndInfo
        AlertDialog(
            onDismissRequest = { showChapterEndDialog = false },
            icon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.reader_chapter_end_icon)) },
            title = {
                Text(
                    if (isLastChapter) stringResource(R.string.reader_chapter_end_last_title)
                    else stringResource(R.string.reader_chapter_end_done_title)
                )
            },
            text = {
                Text(
                    if (isLastChapter) stringResource(R.string.reader_chapter_end_last_message)
                    else stringResource(
                        R.string.reader_chapter_end_next,
                        nextChapterTitle ?: stringResource(R.string.reader_chapter_end_next_unknown)
                    )
                )
            },
            confirmButton = {
                if (!isLastChapter) {
                    TextButton(onClick = {
                        showChapterEndDialog = false
                        viewModel.jumpToNextChapter()
                    }) { Text(stringResource(R.string.reader_chapter_end_jump)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showChapterEndDialog = false }) {
                    Text(
                        if (isLastChapter) stringResource(R.string.reader_chapter_end_confirm)
                        else stringResource(R.string.reader_chapter_end_cancel)
                    )
                }
            }
        )
    }

    // 退出阅读确认对话框
    if (showExitConfirm) {
        var dontAskAgain by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = stringResource(R.string.reader_exit_icon)) },
            title = { Text(stringResource(R.string.reader_exit_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.reader_exit_message))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { dontAskAgain = !dontAskAgain }
                    ) {
                        Checkbox(
                            checked = dontAskAgain,
                            onCheckedChange = { dontAskAgain = it }
                        )
                        Text(
                            text = stringResource(R.string.reader_exit_dont_ask_again),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    if (dontAskAgain) {
                        readerScope.launch { settingsRepository.setReaderExitConfirmDisabled(true) }
                    }
                    onBack()
                }) { Text(stringResource(R.string.reader_exit_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text(stringResource(R.string.reader_exit_cancel)) }
            }
        )
    }

    LaunchedEffect(state.currentPage, state.settings.dualPage, state.settings.rtl, state.settings.scrollMode, state.settingsVersion) {
        scale = 1f
        panOffset = Offset.Zero
    }

    LaunchedEffect(state.settings.immersive, showMenu) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window
        // 使用 WindowInsetsControllerCompat 兼容 API 21+，低版本自动降级
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        if (state.settings.immersive && !showMenu) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 屏幕方向锁定
    LaunchedEffect(state.settings.orientationLock) {
        val activity = context as? Activity ?: return@LaunchedEffect
        activity.requestedOrientation = when (state.settings.orientationLock) {
            OrientationLock.FOLLOW_SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationLock.AUTO_ROTATE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? Activity
            // 退出阅读器后恢复跟随系统，避免影响书架等其他页面
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // 退出阅读器后清除屏幕常亮标志，避免影响其他页面
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 阅读时屏幕常亮：跟随用户设置动态添加/清除窗口标志
    LaunchedEffect(state.settings.keepScreenOn) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window
        if (state.settings.keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ReaderPageContent(
            state = state,
            viewModel = viewModel,
            panelState = panelState,
            showMenu = showMenu,
            scale = scale,
            offset = panOffset,
            onPinchZoom = { s, o ->
                scale = s
                panOffset = o
            },
            onZoomTo = animatedZoomTo,
            onPan = { panOffset += it },
            onBack = {
                if (exitConfirmDisabled) {
                    onBack()
                } else {
                    showExitConfirm = true
                }
            }
        )

        // 菜单覆盖层
        if (showMenu) {
            MenuOverlay(
                state = state,
                viewModel = viewModel,
                bookmarks = bookmarks,
                onJumpToPage = { viewModel.setCurrentPage(it) },
                onClose = { viewModel.toggleMenu() },
                onOpenSettings = onOpenSettings,
                isLargeScreen = isLargeScreen
            )
        }
    }
}
