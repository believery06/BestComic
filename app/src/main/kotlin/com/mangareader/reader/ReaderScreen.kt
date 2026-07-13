package com.mangareader.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import com.mangareader.data.BackgroundTexture
import com.mangareader.data.ComicHolder
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangareader.data.GestureConfig
import com.mangareader.data.ScrollMode
import com.mangareader.data.TapZone
import com.mangareader.data.ZoomMode
import com.mangareader.data.getTapZone
import com.mangareader.utils.rememberWindowSizeClass
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
    comicEntry: com.mangareader.data.ComicEntry? = null,
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: ReaderViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val panelState by viewModel.panelState.collectAsStateWithLifecycle()
    val windowSize = rememberWindowSizeClass()
    val isLargeScreen = windowSize != com.mangareader.utils.WindowSizeClass.PHONE_PORTRAIT

    // 通知 ViewModel 当前屏幕尺寸，用于 inSampleSize 按需采样
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = remember(density, configuration) {
        with(density) { configuration.screenWidthDp.dp.roundToPx() }
    }
    val screenHeightPx = remember(density, configuration) {
        with(density) { configuration.screenHeightDp.dp.roundToPx() }
    }
    LaunchedEffect(screenWidthPx, screenHeightPx) {
        viewModel.setScreenSize(screenWidthPx, screenHeightPx)
    }
    // 日漫(RTL)模式自动使用日漫手势预设：左半边=下一页、右半边=上一页
    val gestureConfig = remember(state.settings.rtl) {
        if (state.settings.rtl) GestureConfig.PRESET_MANGA else GestureConfig.PRESET_COMIC
    }

    LaunchedEffect(comicEntry) {
        comicEntry?.let {
            val chapterPages = ComicHolder.chapter?.pages
            viewModel.loadComic(it, chapterPages)
            ComicHolder.chapter = null
        }
    }

    LaunchedEffect(state.settings.volumeKeyNav) {
        com.mangareader.ReaderKeyEvents.volumeKeyNavEnabled = state.settings.volumeKeyNav
        com.mangareader.ReaderKeyEvents.onNextPage = { viewModel.nextPage() }
        com.mangareader.ReaderKeyEvents.onPreviousPage = { viewModel.previousPage() }
    }
    DisposableEffect(Unit) {
        onDispose {
            com.mangareader.ReaderKeyEvents.volumeKeyNavEnabled = false
            com.mangareader.ReaderKeyEvents.onNextPage = null
            com.mangareader.ReaderKeyEvents.onPreviousPage = null
            (context as? Activity)?.window?.insetsController?.show(WindowInsets.Type.systemBars())
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(state.currentPage, state.settings.dualPage, state.settings.rtl, state.settings.scrollMode, state.settingsVersion) {
        scale = 1f
        panOffset = Offset.Zero
    }

    LaunchedEffect(state.settings.immersive, state.showMenu) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window
        if (state.settings.immersive && !state.showMenu) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        }
    }

    val bgColor = remember(state.settings.backgroundColor) {
        runCatching { Color(android.graphics.Color.parseColor(state.settings.backgroundColor)) }.getOrDefault(Color.Black)
    }

    BackHandler {
        if (panelState.isActive) {
            viewModel.exitPanelView()
        } else if (state.showMenu) {
            viewModel.hideMenu()
        } else {
            onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawBackground(bgColor, state.settings.backgroundTexture) }
    ) {
        if (comicEntry == null) {
            Text(
                text = "无法打开漫画：地址无效",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
                textAlign = TextAlign.Center
            )
        } else if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
        } else if (state.error != null) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = state.error ?: "",
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = { viewModel.loadComic(comicEntry) }) {
                    Text("重试", color = MaterialTheme.colorScheme.primary)
                }
            }
        } else if (state.totalPages > 0) {
            // 分镜模式覆盖层
            if (panelState.isActive) {
                PanelViewer(
                    panelState = panelState,
                    rtl = state.settings.rtl,
                    loadPage = { index -> viewModel.loadPageBitmap(index) },
                    onPrevPanel = { viewModel.prevPanel() },
                    onNextPanel = { viewModel.nextPanel() },
                    onExit = { viewModel.exitPanelView() }
                )
            } else {
                when (state.settings.scrollMode) {
                    ScrollMode.PAGE -> PageViewer(
                        currentPage = state.currentPage,
                        totalPages = state.totalPages,
                        dualPage = state.settings.dualPage,
                        rtl = state.settings.rtl,
                        dualPageStartOne = state.settings.dualPageStartOne,
                        dualPageOffset = state.settings.dualPageOffset,
                        zoomMode = state.settings.zoomMode,
                        pageAnimation = state.settings.pageAnimation,
                        randomAnimation = state.settings.randomAnimation,
                        enableZoom = state.settings.enableZoom,
                        tapZoneSize = state.settings.tapZoneSize,
                        magnifierEnabled = state.settings.magnifierEnabled,
                        gestureConfig = gestureConfig,
                        settingsVersion = state.settingsVersion,
                        loadPage = { index -> viewModel.loadPageBitmap(index) },
                        onTapNext = { viewModel.nextPage() },
                        onTapPrev = { viewModel.previousPage() },
                        onTapMenu = { viewModel.toggleMenu() },
                        onPinchZoom = { zoomDelta, panDelta ->
                            val newScale = (scale * zoomDelta).coerceIn(1f, 5f)
                            val newOffset = panOffset + panDelta
                            scale = newScale
                            panOffset = newOffset
                        },
                        onDoubleTapZoom = {
                            if (scale > 1.1f) {
                                scale = 1f
                                panOffset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                        onPan = { delta -> panOffset = panOffset + delta },
                        onAction = { action ->
                            when (action) {
                                GestureConfig.GestureAction.BACK -> onBack()
                                GestureConfig.GestureAction.NEXT_CHAPTER -> viewModel.jumpToNextChapter()
                                GestureConfig.GestureAction.PREV_CHAPTER -> viewModel.jumpToPrevChapter()
                                GestureConfig.GestureAction.PANEL_VIEW -> viewModel.enterPanelView()
                                GestureConfig.GestureAction.BOOKMARK -> viewModel.toggleBookmark()
                                GestureConfig.GestureAction.AUTO_CROP_TOGGLE -> viewModel.toggleAutoCrop()
                                GestureConfig.GestureAction.ROTATE -> viewModel.rotate()
                                GestureConfig.GestureAction.BRIGHTNESS_UP -> viewModel.setBrightness(state.settings.brightness + 0.1f)
                                GestureConfig.GestureAction.BRIGHTNESS_DOWN -> viewModel.setBrightness(state.settings.brightness - 0.1f)
                                GestureConfig.GestureAction.SLIDESHOW_TOGGLE -> {
                                    val newInterval = if (state.settings.autoPageInterval > 0) 0 else 5
                                    viewModel.setAutoPageInterval(newInterval)
                                }
                                else -> {}
                            }
                        },
                        scale = scale,
                        offset = panOffset
                    )
                ScrollMode.VERTICAL -> VerticalScrollViewer(
                    totalPages = state.totalPages,
                    startPage = state.currentPage,
                    rtl = state.settings.rtl,
                    settingsVersion = state.settingsVersion,
                    loadPage = { index -> viewModel.loadPageBitmap(index) },
                    onTapMenu = { viewModel.toggleMenu() },
                    onPageChanged = { idx -> viewModel.setCurrentPage(idx) }
                )
                ScrollMode.HORIZONTAL -> HorizontalScrollViewer(
                    totalPages = state.totalPages,
                    startPage = state.currentPage,
                    rtl = state.settings.rtl,
                    settingsVersion = state.settingsVersion,
                    loadPage = { index -> viewModel.loadPageBitmap(index) },
                    onTapMenu = { viewModel.toggleMenu() },
                    onPageChanged = { idx -> viewModel.setCurrentPage(idx) }
                )
            }
            }
        }

        if (state.settings.scrollMode == ScrollMode.PAGE && !state.showMenu && !panelState.isActive) {
            // 非 RTL：从左边缘向右滑动退出；RTL：从右边缘向左滑动退出
            val edgeExitAlignment = if (state.settings.rtl) Alignment.CenterEnd else Alignment.CenterStart
            val edgeExitThreshold = if (state.settings.rtl) -100f else 100f
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(40.dp)
                    .align(edgeExitAlignment)
                    .pointerInput(state.settings.rtl) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                totalDrag += dragAmount
                            },
                            onDragEnd = {
                                if (state.settings.rtl) {
                                    if (totalDrag < edgeExitThreshold) onBack()
                                } else {
                                    if (totalDrag > edgeExitThreshold) onBack()
                                }
                            }
                        )
                    }
            )
        }

        if (state.showMenu) {
            MenuOverlay(
                state = state,
                viewModel = viewModel,
                onJumpToPage = { page -> viewModel.setCurrentPage(page - 1) },
                onClose = { viewModel.hideMenu() },
                onOpenSettings = onOpenSettings,
                isLargeScreen = isLargeScreen
            )
        }
    }
}

@Composable
private fun PageViewer(
    currentPage: Int,
    totalPages: Int,
    dualPage: Boolean,
    rtl: Boolean,
    dualPageStartOne: Boolean,
    dualPageOffset: Int,
    zoomMode: ZoomMode,
    pageAnimation: com.mangareader.data.PageAnimation,
    randomAnimation: Boolean = false,
    enableZoom: Boolean,
    tapZoneSize: com.mangareader.data.TapZoneSize,
    magnifierEnabled: Boolean,
    gestureConfig: GestureConfig,
    settingsVersion: Int,
    loadPage: suspend (Int) -> Bitmap?,
    onTapNext: () -> Unit,
    onTapPrev: () -> Unit,
    onTapMenu: () -> Unit,
    onPinchZoom: (Float, Offset) -> Unit,
    onDoubleTapZoom: () -> Unit,
    onPan: (Offset) -> Unit = {},
    onAction: (GestureConfig.GestureAction) -> Unit = {},
    scale: Float,
    offset: Offset
) {

    fun computeDisplayIndices(page: Int): List<Int> {
        val safeTotal = (totalPages - 1).coerceAtLeast(0)
        if (!dualPage) {
            return listOf(page.coerceIn(0, safeTotal))
        }

        val pageOffset = dualPageOffset.coerceIn(0, safeTotal)
        val shifted = (page - pageOffset).coerceAtLeast(0)

        val indices = mutableListOf<Int>()
        if (dualPageStartOne) {
            // 1+2 模式：[0,1], [2,3], [4,5] ...
            val pairStart = (shifted / 2) * 2
            val left = (pairStart + pageOffset).coerceAtMost(safeTotal)
            val right = (pairStart + pageOffset + 1).coerceAtMost(safeTotal)
            indices.add(left)
            if (right != left) indices.add(right)
        } else {
            // 封面单页模式：[cover], [1,2], [3,4] ...
            if (shifted == 0) {
                indices.add(pageOffset.coerceAtMost(safeTotal))
            } else {
                val pairStart = ((shifted - 1) / 2) * 2 + 1
                val left = (pairStart + pageOffset).coerceAtMost(safeTotal)
                val right = (pairStart + pageOffset + 1).coerceAtMost(safeTotal)
                indices.add(left)
                if (right != left) indices.add(right)
            }
        }

        return if (rtl && indices.size > 1) indices.reversed() else indices
    }

    var magnifierPos by remember { mutableStateOf<Offset?>(null) }
    var magnifierBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var magnifierCrop by remember { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            // 清理放大镜位图，防止配置变更或退出阅读时内存泄漏
            magnifierCrop?.let { crop -> if (!crop.isRecycled) runCatching { crop.recycle() } }
            magnifierCrop = null
            magnifierBitmap = null
        }
    }

    var curlTrigger by remember { mutableStateOf(false) }
    var curlForward by remember { mutableStateOf(true) }
    var isCurling by remember { mutableStateOf(false) }
    val realOnTapNext: () -> Unit = { if (pageAnimation == com.mangareader.data.PageAnimation.CURL) { curlForward = true; curlTrigger = !curlTrigger; isCurling = true } else onTapNext() }
    val realOnTapPrev: () -> Unit = { if (pageAnimation == com.mangareader.data.PageAnimation.CURL) { curlForward = false; curlTrigger = !curlTrigger; isCurling = true } else onTapPrev() }

    val transformModifier = Modifier
        .fillMaxSize()
        // 单指拖动平移：仅在放大状态下启用，避免遮挡未放大时的翻页手势
        .pointerInput(scale) {
            if (scale > 1.05f) {
                detectDragGestures(
                    onDrag = { change: PointerInputChange, dragAmount: Offset ->
                        change.consume()
                        onPan(dragAmount)
                    }
                )
            }
        }
        // 水平滑动翻页：仅在未放大且非 CURL 动画时启用
        .pointerInput(scale, rtl, isCurling, pageAnimation) {
            if (scale <= 1.05f && !isCurling && pageAnimation != com.mangareader.data.PageAnimation.CURL) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        totalDrag += dragAmount
                    },
                    onDragEnd = {
                        when {
                            // 向左滑动
                            totalDrag < -100f -> {
                                if (rtl) realOnTapNext() else realOnTapPrev()
                            }
                            // 向右滑动
                            totalDrag > 100f -> {
                                if (rtl) realOnTapPrev() else realOnTapNext()
                            }
                        }
                    }
                )
            }
        }
        .pointerInput(enableZoom) {
            if (enableZoom) {
                detectTransformGestures { _, pan, zoom, _ ->
                    // 仅在双指缩放（zoom != 1f）时处理，避免单指拖动与 detectDragGestures 冲突导致 panOffset 双倍叠加
                    if (zoom != 1f) {
                        onPinchZoom(zoom, pan)
                    }
                }
            }
        }
        .pointerInput(Unit, tapZoneSize, rtl, magnifierEnabled) {
            detectTapGestures(
                onDoubleTap = {
                    when (gestureConfig.doubleTap) {
                        GestureConfig.GestureAction.ZOOM_TOGGLE -> if (enableZoom) onDoubleTapZoom()
                        GestureConfig.GestureAction.PANEL_VIEW -> onAction(GestureConfig.GestureAction.PANEL_VIEW)
                        GestureConfig.GestureAction.NEXT_PAGE -> realOnTapNext()
                        GestureConfig.GestureAction.PREV_PAGE -> realOnTapPrev()
                        GestureConfig.GestureAction.TOGGLE_MENU -> onTapMenu()
                        GestureConfig.GestureAction.BOOKMARK -> onAction(GestureConfig.GestureAction.BOOKMARK)
                        GestureConfig.GestureAction.BACK -> onAction(GestureConfig.GestureAction.BACK)
                        GestureConfig.GestureAction.NEXT_CHAPTER -> onAction(GestureConfig.GestureAction.NEXT_CHAPTER)
                        GestureConfig.GestureAction.PREV_CHAPTER -> onAction(GestureConfig.GestureAction.PREV_CHAPTER)
                        GestureConfig.GestureAction.AUTO_CROP_TOGGLE -> onAction(GestureConfig.GestureAction.AUTO_CROP_TOGGLE)
                        GestureConfig.GestureAction.ROTATE -> onAction(GestureConfig.GestureAction.ROTATE)
                        GestureConfig.GestureAction.BRIGHTNESS_UP -> onAction(GestureConfig.GestureAction.BRIGHTNESS_UP)
                        GestureConfig.GestureAction.BRIGHTNESS_DOWN -> onAction(GestureConfig.GestureAction.BRIGHTNESS_DOWN)
                        GestureConfig.GestureAction.SLIDESHOW_TOGGLE -> onAction(GestureConfig.GestureAction.SLIDESHOW_TOGGLE)
                        GestureConfig.GestureAction.MAGNIFIER -> {}
                        GestureConfig.GestureAction.NOTHING -> {}
                    }
                },
                onLongPress = { pos ->
                    when (gestureConfig.longPress) {
                        GestureConfig.GestureAction.MAGNIFIER -> if (magnifierEnabled) magnifierPos = pos
                        GestureConfig.GestureAction.TOGGLE_MENU -> onTapMenu()
                        GestureConfig.GestureAction.PANEL_VIEW -> onAction(GestureConfig.GestureAction.PANEL_VIEW)
                        GestureConfig.GestureAction.BOOKMARK -> onAction(GestureConfig.GestureAction.BOOKMARK)
                        GestureConfig.GestureAction.BACK -> onAction(GestureConfig.GestureAction.BACK)
                        GestureConfig.GestureAction.NEXT_CHAPTER -> onAction(GestureConfig.GestureAction.NEXT_CHAPTER)
                        GestureConfig.GestureAction.PREV_CHAPTER -> onAction(GestureConfig.GestureAction.PREV_CHAPTER)
                        GestureConfig.GestureAction.AUTO_CROP_TOGGLE -> onAction(GestureConfig.GestureAction.AUTO_CROP_TOGGLE)
                        GestureConfig.GestureAction.ROTATE -> onAction(GestureConfig.GestureAction.ROTATE)
                        GestureConfig.GestureAction.ZOOM_TOGGLE -> if (enableZoom) onDoubleTapZoom()
                        else -> {}
                    }
                },
                onTap = { tapOffset ->
                    if (magnifierPos != null) {
                        magnifierPos = null
                        magnifierBitmap = null
                        magnifierCrop?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                        magnifierCrop = null
                        return@detectTapGestures
                    }
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    val x = tapOffset.x
                    val y = tapOffset.y
                    val zone = gestureConfig.getTapZone(x, width, y, height, tapZoneSize)
                    var action = when (zone) {
                        com.mangareader.data.TapZone.LEFT -> gestureConfig.tapLeft
                        com.mangareader.data.TapZone.RIGHT -> gestureConfig.tapRight
                        com.mangareader.data.TapZone.CENTER -> gestureConfig.tapCenter
                        com.mangareader.data.TapZone.TOP -> gestureConfig.tapTop
                        com.mangareader.data.TapZone.BOTTOM -> gestureConfig.tapBottom
                    }
                    // 死区 fallback：当 TOP/BOTTOM 区域未配置（NOTHING）时，回退到 CENTER 动作，
                    // 避免屏幕上下边缘成为"死区"无法呼出菜单或翻页
                    if (action == GestureConfig.GestureAction.NOTHING &&
                        (zone == com.mangareader.data.TapZone.TOP || zone == com.mangareader.data.TapZone.BOTTOM)) {
                        action = gestureConfig.tapCenter
                    }
                    when (action) {
                        GestureConfig.GestureAction.NEXT_PAGE -> realOnTapNext()
                        GestureConfig.GestureAction.PREV_PAGE -> realOnTapPrev()
                        GestureConfig.GestureAction.TOGGLE_MENU -> onTapMenu()
                        GestureConfig.GestureAction.BOOKMARK -> onAction(GestureConfig.GestureAction.BOOKMARK)
                        GestureConfig.GestureAction.BACK -> onAction(GestureConfig.GestureAction.BACK)
                        GestureConfig.GestureAction.ZOOM_TOGGLE -> if (enableZoom) onDoubleTapZoom()
                        GestureConfig.GestureAction.MAGNIFIER -> if (magnifierEnabled) magnifierPos = tapOffset
                        GestureConfig.GestureAction.PANEL_VIEW -> onAction(GestureConfig.GestureAction.PANEL_VIEW)
                        GestureConfig.GestureAction.NEXT_CHAPTER -> onAction(GestureConfig.GestureAction.NEXT_CHAPTER)
                        GestureConfig.GestureAction.PREV_CHAPTER -> onAction(GestureConfig.GestureAction.PREV_CHAPTER)
                        GestureConfig.GestureAction.AUTO_CROP_TOGGLE -> onAction(GestureConfig.GestureAction.AUTO_CROP_TOGGLE)
                        GestureConfig.GestureAction.ROTATE -> onAction(GestureConfig.GestureAction.ROTATE)
                        GestureConfig.GestureAction.BRIGHTNESS_UP -> onAction(GestureConfig.GestureAction.BRIGHTNESS_UP)
                        GestureConfig.GestureAction.BRIGHTNESS_DOWN -> onAction(GestureConfig.GestureAction.BRIGHTNESS_DOWN)
                        GestureConfig.GestureAction.SLIDESHOW_TOGGLE -> onAction(GestureConfig.GestureAction.SLIDESHOW_TOGGLE)
                        GestureConfig.GestureAction.NOTHING -> {}
                    }
                }
            )
        }

    val renderPage: @Composable (Int) -> Unit = { page ->
        val displayIndices = computeDisplayIndices(page)
        if (dualPage && displayIndices.size > 1) {
            DualPageLayout(
                indices = displayIndices,
                loadPage = loadPage,
                zoomMode = zoomMode,
                settingsVersion = settingsVersion
            )
        } else {
            PageImage(
                index = displayIndices.firstOrNull() ?: 0,
                loadPage = loadPage,
                zoomMode = zoomMode,
                fillMaxSize = true,
                settingsVersion = settingsVersion
            )
        }
    }

    BoxWithConstraints(modifier = transformModifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }
        // 随机翻页动效：每次 currentPage 变化时随机选择一种（排除 NONE）
        val effectiveAnimation = if (randomAnimation) {
            remember(currentPage) {
                com.mangareader.data.PageAnimation.entries
                    .filter { it != com.mangareader.data.PageAnimation.NONE }
                    .random()
            }
        } else pageAnimation
        when (effectiveAnimation) {
            com.mangareader.data.PageAnimation.NONE -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                ) { renderPage(currentPage) }
            }
            com.mangareader.data.PageAnimation.SLIDE -> {
                AnimatedContent(
                    targetState = currentPage,
                    transitionSpec = {
                        val forward = targetState > initialState
                        val enter = if (rtl) {
                            slideInHorizontally(tween(220)) { if (forward) -it else it } + fadeIn(tween(200))
                        } else {
                            slideInHorizontally(tween(220)) { if (forward) it else -it } + fadeIn(tween(200))
                        }
                        val exit = if (rtl) {
                            slideOutHorizontally(tween(220)) { if (forward) it else -it } + fadeOut(tween(200))
                        } else {
                            slideOutHorizontally(tween(220)) { if (forward) -it else it } + fadeOut(tween(200))
                        }
                        enter togetherWith exit
                    },
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    label = "pageSlide"
                ) { pageState -> renderPage(pageState) }
            }
            com.mangareader.data.PageAnimation.CURL -> {
                var currentBmp by remember { mutableStateOf<Bitmap?>(null) }
                var nextBmp by remember { mutableStateOf<Bitmap?>(null) }
                var prevBmp by remember { mutableStateOf<Bitmap?>(null) }

                LaunchedEffect(currentPage, settingsVersion) {
                    val indices = computeDisplayIndices(currentPage)
                    currentBmp = loadPage(indices.firstOrNull() ?: 0)
                    nextBmp = if (currentPage + 1 < totalPages) {
                        val nextIndices = computeDisplayIndices(currentPage + 1)
                        loadPage(nextIndices.firstOrNull() ?: (currentPage + 1))
                    } else null
                    prevBmp = if (currentPage > 0) {
                        val prevIndices = computeDisplayIndices((currentPage - 1).coerceAtLeast(0))
                        loadPage(prevIndices.firstOrNull() ?: 0)
                    } else null
                }

                PageCurlView(
                    currentBitmap = currentBmp,
                    nextBitmap = if (curlForward) nextBmp else prevBmp,
                    isForward = curlForward,
                    triggerFlip = curlTrigger,
                    onFlipComplete = {
                        isCurling = false
                        if (curlForward) onTapNext() else onTapPrev()
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            }
            com.mangareader.data.PageAnimation.FADE -> {
                AnimatedContent(
                    targetState = currentPage,
                    transitionSpec = {
                        fadeIn(tween(250)) togetherWith fadeOut(tween(250))
                    },
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    label = "pageFade"
                ) { pageState -> renderPage(pageState) }
            }
            else -> {
                // 像素/百叶窗/碎裂/网点等盲盒动效：先用淡入淡出兜底，避免 NoWhenBranchMatched 崩溃
                AnimatedContent(
                    targetState = currentPage,
                    transitionSpec = {
                        fadeIn(tween(260)) togetherWith fadeOut(tween(260))
                    },
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    label = "pageFallback"
                ) { pageState -> renderPage(pageState) }
            }
        }

        if (magnifierEnabled && magnifierPos != null) {
            val pos = magnifierPos!!
            val densityVal = density.density
            LaunchedEffect(pos, currentPage, settingsVersion, scale, offset, containerW, containerH, densityVal) {
                val bmp = loadPage(computeDisplayIndices(currentPage).firstOrNull() ?: 0)
                magnifierBitmap = bmp
                magnifierCrop?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                magnifierCrop = null
                if (bmp != null && !bmp.isRecycled) {
                    magnifierCrop = computeMagnifierCrop(bmp, pos, scale, offset, zoomMode, containerW, containerH, densityVal)
                }
            }
            magnifierCrop?.let { crop ->
                MagnifierOverlay(
                    bitmap = crop,
                    position = pos,
                    onDismiss = {
                        magnifierPos = null
                        magnifierBitmap = null
                        magnifierCrop?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                        magnifierCrop = null
                    }
                )
            }
        }
    }
}

private fun computeMagnifierCrop(
    bitmap: Bitmap,
    touchPos: Offset,
    scale: Float,
    panOffset: Offset,
    zoomMode: ZoomMode,
    viewW: Float,
    viewH: Float,
    density: Float
): Bitmap? {
    return try {
        val bmpW = bitmap.width.toFloat()
        val bmpH = bitmap.height.toFloat()
        val magnifierRadiusPx = 90f * density
        val cropSrcRadius = magnifierRadiusPx / 2.8f

        val scaleFactorX: Float
        val scaleFactorY: Float
        when (zoomMode) {
            ZoomMode.FIT_WIDTH -> {
                scaleFactorX = viewW / bmpW
                scaleFactorY = scaleFactorX
            }
            ZoomMode.FIT_HEIGHT -> {
                scaleFactorY = viewH / bmpH
                scaleFactorX = scaleFactorY
            }
            else -> {
                val factor = minOf(viewW / bmpW, viewH / bmpH)
                scaleFactorX = factor
                scaleFactorY = factor
            }
        }
        val effScaleX = scaleFactorX * scale
        val effScaleY = scaleFactorY * scale

        val scaledW = bmpW * effScaleX
        val scaledH = bmpH * effScaleY

        val imageLeft = (viewW - scaledW) / 2f + panOffset.x
        val imageTop = (viewH - scaledH) / 2f + panOffset.y

        val srcX = ((touchPos.x - imageLeft) / effScaleX).coerceIn(0f, bmpW - 1f)
        val srcY = ((touchPos.y - imageTop) / effScaleY).coerceIn(0f, bmpH - 1f)

        val radiusX = (cropSrcRadius / effScaleX).coerceAtMost(bmpW / 4f)
        val radiusY = (cropSrcRadius / effScaleY).coerceAtMost(bmpH / 4f)
        val radius = minOf(radiusX, radiusY)

        val left = (srcX - radius).toInt().coerceIn(0, bitmap.width - 1)
        val top = (srcY - radius).toInt().coerceIn(0, bitmap.height - 1)
        val right = (srcX + radius).toInt().coerceIn(1, bitmap.width)
        val bottom = (srcY + radius).toInt().coerceIn(1, bitmap.height)
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        Bitmap.createBitmap(bitmap, left, top, w, h)
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun MagnifierOverlay(
    bitmap: Bitmap,
    position: Offset,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val magnifierSize = 180.dp
    val magnifierSizePx = with(density) { magnifierSize.toPx() }
    val magnifierOffsetY = with(density) { 80.dp.toPx() }
    val imageBmp = bitmap.asImageBitmap()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            }
    ) {
        Box(
            modifier = Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        (position.x - magnifierSizePx / 2).toInt(),
                        (position.y - magnifierSizePx / 2 - magnifierOffsetY).toInt()
                    )
                }
                .size(magnifierSize)
                .shadow(20.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.Black)
                .border(3.dp, Color(0x66FFFFFF), CircleShape)
        ) {
            Image(
                bitmap = imageBmp,
                contentDescription = "放大镜",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun DualPageLayout(
    indices: List<Int>,
    loadPage: suspend (Int) -> Bitmap?,
    zoomMode: ZoomMode = ZoomMode.FIT_SCREEN,
    settingsVersion: Int = 0
) {
    if (indices.size < 2) {
        PageImage(
            index = indices.firstOrNull() ?: 0,
            loadPage = loadPage,
            zoomMode = zoomMode,
            fillMaxSize = true,
            settingsVersion = settingsVersion
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopEnd
        ) {
            PageImage(
                index = indices[0],
                loadPage = loadPage,
                zoomMode = zoomMode,
                fillMaxSize = true,
                imageAlignment = Alignment.TopEnd,
                settingsVersion = settingsVersion
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopStart
        ) {
            PageImage(
                index = indices[1],
                loadPage = loadPage,
                zoomMode = zoomMode,
                fillMaxSize = true,
                imageAlignment = Alignment.TopStart,
                settingsVersion = settingsVersion
            )
        }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun VerticalScrollViewer(
    totalPages: Int,
    startPage: Int,
    rtl: Boolean = false,
    settingsVersion: Int = 0,
    loadPage: suspend (Int) -> Bitmap?,
    onTapMenu: () -> Unit,
    onPageChanged: (Int) -> Unit
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)

    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx ->
                if (idx >= 0 && idx < totalPages) onPageChanged(idx)
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    val width = size.width.toFloat()
                    val x = tapOffset.x
                    if (x > width * 0.35f && x < width * 0.65f) {
                        onTapMenu()
                    }
                }
            },
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items(totalPages) { idx ->
            PageImage(
                index = idx,
                loadPage = loadPage,
                fillMaxSize = true,
                fillMaxWidth = true,
                settingsVersion = settingsVersion
            )
        }
    }
}

@Composable
private fun HorizontalScrollViewer(
    totalPages: Int,
    startPage: Int,
    rtl: Boolean = false,
    settingsVersion: Int = 0,
    loadPage: suspend (Int) -> Bitmap?,
    onTapMenu: () -> Unit,
    onPageChanged: (Int) -> Unit
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)

    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx ->
                if (idx >= 0 && idx < totalPages) onPageChanged(idx)
            }
    }

    LazyRow(
        state = listState,
        reverseLayout = rtl,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(rtl) {
                detectTapGestures { tapOffset ->
                    val width = size.width.toFloat()
                    val x = tapOffset.x
                    when {
                        // 中间区域呼出菜单
                        x > width * 0.35f && x < width * 0.65f -> onTapMenu()
                        rtl -> {
                            if (x > width * 0.65f) onPageChanged((listState.firstVisibleItemIndex - 1).coerceAtLeast(0))
                            else if (x < width * 0.35f) onPageChanged((listState.firstVisibleItemIndex + 1).coerceAtMost(totalPages - 1))
                        }
                        else -> {
                            if (x > width * 0.65f) onPageChanged((listState.firstVisibleItemIndex + 1).coerceAtMost(totalPages - 1))
                            else if (x < width * 0.35f) onPageChanged((listState.firstVisibleItemIndex - 1).coerceAtLeast(0))
                        }
                    }
                }
            },
        contentPadding = PaddingValues(0.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items(totalPages) { idx ->
            PageImage(
                index = idx,
                loadPage = loadPage,
                fillMaxSize = true,
                fillMaxHeight = true,
                settingsVersion = settingsVersion
            )
        }
    }
}

@Composable
private fun PageImage(
    index: Int,
    loadPage: suspend (Int) -> Bitmap?,
    fillMaxSize: Boolean = false,
    fillMaxWidth: Boolean = false,
    fillMaxHeight: Boolean = false,
    zoomMode: ZoomMode = ZoomMode.FIT_SCREEN,
    imageAlignment: Alignment = Alignment.Center,
    settingsVersion: Int = 0
) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    var error by remember(index) { mutableStateOf(false) }

    LaunchedEffect(index, settingsVersion) {
        bitmap = null
        error = false
        // 失败时最多重试 2 次，避免大图解码偶发错误导致一直转圈
        repeat(3) { attempt ->
            try {
                val loaded = loadPage(index)
                if (loaded != null && !loaded.isRecycled) {
                    bitmap = loaded
                    error = false
                    return@LaunchedEffect
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (attempt < 2) kotlinx.coroutines.delay(150)
        }
        error = true
    }

    val contentScale = when {
        fillMaxHeight -> ContentScale.FillHeight
        fillMaxWidth -> ContentScale.FillWidth
        zoomMode == ZoomMode.FIT_WIDTH -> ContentScale.FillWidth
        zoomMode == ZoomMode.FIT_HEIGHT -> ContentScale.FillHeight
        zoomMode == ZoomMode.FIT_SCREEN -> ContentScale.Fit
        zoomMode == ZoomMode.FREE -> ContentScale.Fit
        fillMaxSize -> ContentScale.Fit
        else -> ContentScale.Fit
    }

    when {
        bitmap != null && bitmap?.isRecycled == false -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "第 $index 页",
                    contentScale = contentScale,
                    alignment = imageAlignment,
                    modifier = when {
                        zoomMode == ZoomMode.FIT_WIDTH -> Modifier.fillMaxWidth().wrapContentHeight()
                        zoomMode == ZoomMode.FIT_HEIGHT -> Modifier.fillMaxHeight().wrapContentWidth()
                        else -> Modifier.fillMaxSize()
                    }
                )
            }
        }
        error -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "加载失败",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        else -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuOverlay(
    state: ReaderViewModel.ReaderUiState,
    viewModel: ReaderViewModel,
    onJumpToPage: (Int) -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    isLargeScreen: Boolean = false
) {
    var showJumpDialog by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showThumbnails by remember { mutableStateOf(false) }
    var showChapterList by remember { mutableStateOf(false) }
    val pageInChapter = viewModel.getPageInChapter()
    val chapterPageCount = viewModel.getChapterPageCount()
    var pageInput by remember { mutableStateOf(pageInChapter.toString()) }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentPage)
    val context = LocalContext.current
    var isSharing by remember { mutableStateOf(false) }

    LaunchedEffect(state.currentPage) {
        pageInput = viewModel.getPageInChapter().toString()
    }

    val scrimColor = Color(0x52000000)
    val panelColor = Color(0xCC121212)

    if (isLargeScreen) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimColor)
                .pointerInput(Unit) { detectTapGestures { onClose() } }
        ) {
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .padding(16.dp)
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                MinimalTopBar(
                    state = state,
                    viewModel = viewModel,
                    onOpenSettings = onOpenSettings,
                    onClose = onClose,
                    backgroundColor = panelColor
                )
            }
            Spacer(
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) { detectTapGestures { onClose() } }
            )
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .padding(16.dp)
                    .pointerInput(Unit) { detectTapGestures { } },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                MinimalBottomBar(
                    state = state,
                    viewModel = viewModel,
                    onJumpDialog = { showJumpDialog = true },
                    onBookmarks = { showBookmarks = !showBookmarks },
                    onThumbnails = { showThumbnails = !showThumbnails },
                    onChapterList = { showChapterList = true },
                    isSharing = isSharing,
                    backgroundColor = panelColor,
                    onShareCard = {
                        if (isSharing) return@MinimalBottomBar
                        isSharing = true
                        coroutineScope.launch {
                            val file = viewModel.createShareCard(state.currentPage)
                            isSharing = false
                            if (file != null) {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val intent = android.content.Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_TEXT, "来自 ${com.mangareader.Constants.APP_NAME}")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(intent, "分享漫画卡片")
                                )
                            } else {
                                android.widget.Toast.makeText(context, "生成卡片失败", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimColor)
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 16.dp)
            ) {
                MinimalTopBar(
                    state = state,
                    viewModel = viewModel,
                    onOpenSettings = onOpenSettings,
                    onClose = onClose,
                    backgroundColor = panelColor
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MinimalBottomBar(
                    state = state,
                    viewModel = viewModel,
                    onJumpDialog = { showJumpDialog = true },
                    onBookmarks = { showBookmarks = !showBookmarks },
                    onThumbnails = { showThumbnails = !showThumbnails },
                    onChapterList = { showChapterList = true },
                    isSharing = isSharing,
                    backgroundColor = panelColor,
                    onShareCard = {
                        if (isSharing) return@MinimalBottomBar
                        isSharing = true
                        coroutineScope.launch {
                            val file = viewModel.createShareCard(state.currentPage)
                            isSharing = false
                            if (file != null) {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val intent = android.content.Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_TEXT, "来自 ${com.mangareader.Constants.APP_NAME}")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(intent, "分享漫画卡片")
                                )
                            } else {
                                android.widget.Toast.makeText(context, "生成卡片失败", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
    }

    if (showThumbnails) {
        AlertDialog(
            onDismissRequest = { showThumbnails = false },
            title = { Text("页面预览 (${state.totalPages})") },
            text = {
                LaunchedEffect(state.currentPage) {
                    if (state.totalPages > 0) {
                        coroutineScope.launch {
                            listState.animateScrollToItem(state.currentPage)
                        }
                    }
                }
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                ) {
                    items(state.totalPages) { idx ->
                        ThumbnailItem(
                            index = idx,
                            isCurrent = idx == state.currentPage,
                            loadPage = { viewModel.loadPageBitmap(idx) },
                            onClick = {
                                onJumpToPage(idx + 1)
                                showThumbnails = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThumbnails = false }) { Text("关闭") }
            }
        )
    }

    if (showBookmarks) {
        AlertDialog(
            onDismissRequest = { showBookmarks = false },
            title = { Text("书签 (${state.bookmarks.size})") },
            text = {
                if (state.bookmarks.isEmpty()) {
                    Text("暂无书签", color = Color.Gray)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    ) {
                        items(state.bookmarks.size, key = { state.bookmarks[it] }) { idx ->
                            val page = state.bookmarks[idx]
                            val chapterTitle = viewModel.getPageChapterTitle(page)
                            var thumb by remember(page) { mutableStateOf<Bitmap?>(null) }
                            LaunchedEffect(page) {
                                thumb = viewModel.loadThumbnail(page)
                            }
                            ListItem(
                                headlineContent = { Text("第 ${page + 1} 页") },
                                supportingContent = {
                                    if (chapterTitle.isNotEmpty()) {
                                        Text(chapterTitle, style = MaterialTheme.typography.bodySmall)
                                    }
                                },
                                leadingContent = {
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        thumb?.let { b ->
                                            Image(
                                                bitmap = b.asImageBitmap(),
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        } ?: Icon(
                                            imageVector = Icons.Default.Image,
                                            contentDescription = null,
                                            modifier = Modifier.size(28.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                trailingContent = {
                                    IconButton(onClick = { viewModel.removeBookmark(page) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "删除书签")
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onJumpToPage(page + 1)
                                        showBookmarks = false
                                    }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBookmarks = false }) { Text("关闭") }
            }
        )
    }

    if (showJumpDialog) {
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            title = { Text("跳页") },
            text = {
                Column {
                    if (state.currentChapter.isNotEmpty()) {
                        Text("当前分卷：${state.currentChapter}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = { pageInput = it.filter { c -> c.isDigit() } },
                        label = { Text("页数 (1-$chapterPageCount)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val targetInChapter = pageInput.toIntOrNull()?.coerceIn(1, chapterPageCount) ?: 1
                    val chapters = viewModel.getChapterList()
                    val currentChapterTitle = state.currentChapter
                    val chapterInfo = chapters.find { it.first == currentChapterTitle }
                    if (chapterInfo != null) {
                        val globalPage = chapterInfo.second + targetInChapter - 1
                        viewModel.setCurrentPage(globalPage)
                    } else {
                        viewModel.setCurrentPage(targetInChapter - 1)
                    }
                    showJumpDialog = false
                }) { Text("跳转") }
            },
            dismissButton = {
                TextButton(onClick = { showJumpDialog = false }) { Text("取消") }
            }
        )
    }

    if (showChapterList) {
        val chapters = viewModel.getChapterList()
        AlertDialog(
            onDismissRequest = { showChapterList = false },
            title = { Text("分卷列表") },
            text = {
                if (chapters.isEmpty()) {
                    Text("未检测到分卷", color = Color.Gray)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                    ) {
                        items(chapters.size) { idx ->
                            val (title, startIdx, endIdx) = chapters[idx]
                            val isCurrent = title == state.currentChapter
                            TextButton(
                                onClick = {
                                    viewModel.setCurrentPage(startIdx)
                                    showChapterList = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "$title (${endIdx - startIdx + 1}P)",
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChapterList = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun MinimalTopBar(
    state: ReaderViewModel.ReaderUiState,
    viewModel: ReaderViewModel,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    backgroundColor: Color = Color(0xCC121212)
) {
    val pageInChapter = viewModel.getPageInChapter()
    val chapterPageCount = viewModel.getChapterPageCount()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = state.title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
            Text(
                text = buildString {
                    if (state.currentChapter.isNotEmpty()) {
                        append("${state.currentChapter}  ${pageInChapter}/${chapterPageCount}P")
                    } else {
                        append("${state.currentPage + 1}/${state.totalPages}P")
                    }
                },
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.White)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MinimalBottomBar(
    state: ReaderViewModel.ReaderUiState,
    viewModel: ReaderViewModel,
    onJumpDialog: () -> Unit,
    onBookmarks: () -> Unit,
    onThumbnails: () -> Unit,
    onChapterList: () -> Unit,
    isSharing: Boolean = false,
    backgroundColor: Color = Color(0xCC121212),
    onShareCard: () -> Unit = {}
) {
    val isBookmarked = state.bookmarks.contains(state.currentPage)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (state.currentChapter.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { viewModel.jumpToPrevChapter() }) {
                    Text("上一卷", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onChapterList) {
                    Text("分卷列表", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { viewModel.jumpToNextChapter() }) {
                    Text("下一卷", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        PageScrubberWithThumbnail(
            currentPage = state.currentPage,
            totalPages = state.totalPages,
            chapterPage = if (state.currentChapter.isNotEmpty()) viewModel.getPageInChapter() else state.currentPage + 1,
            hasChapter = state.currentChapter.isNotEmpty(),
            loadThumbnail = { viewModel.loadThumbnail(it, 160) },
            onPageSelected = { viewModel.setCurrentPage(it) }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 日漫(RTL)模式下，"上一页"在视觉右侧，"下一页"在视觉左侧
            IconButton(onClick = { viewModel.previousPage() }) {
                Icon(
                    imageVector = if (state.settings.rtl) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "上一页",
                    tint = Color.White
                )
            }
            IconButton(onClick = onJumpDialog) {
                Icon(Icons.Default.Numbers, contentDescription = "跳页", tint = Color.White)
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .combinedClickable(
                        onClick = { viewModel.toggleBookmark() },
                        onLongClick = onBookmarks
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = if (isBookmarked) "已收藏" else "收藏",
                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary else Color.White
                )
            }
            IconButton(onClick = onThumbnails) {
                Icon(Icons.Default.GridView, contentDescription = "页面预览", tint = Color.White)
            }
            IconButton(
                onClick = onShareCard,
                enabled = !isSharing
            ) {
                if (isSharing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Share, contentDescription = "分享卡", tint = Color.White)
                }
            }
            IconButton(onClick = { viewModel.nextPage() }) {
                Icon(
                    imageVector = if (state.settings.rtl) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "下一页",
                    tint = Color.White
                )
            }
        }
    }
}

/**
 * 缩略图跳页条：拖动底部滑块时实时显示当前位置页面缩略图与页码。
 */
@Composable
private fun PageScrubberWithThumbnail(
    currentPage: Int,
    totalPages: Int,
    chapterPage: Int,
    hasChapter: Boolean,
    loadThumbnail: suspend (Int) -> Bitmap?,
    onPageSelected: (Int) -> Unit
) {
    var previewPage by remember(currentPage) { mutableIntStateOf(currentPage) }
    var isDragging by remember { mutableStateOf(false) }
    var previewBitmap by remember(previewPage) { mutableStateOf<Bitmap?>(null) }

    // previewPage 变化时回收旧的缩略图，避免内存泄漏
    DisposableEffect(previewPage) {
        onDispose {
            previewBitmap?.let { bmp ->
                if (!bmp.isRecycled) runCatching { bmp.recycle() }
                previewBitmap = null
            }
        }
    }

    LaunchedEffect(previewPage, isDragging) {
        if (isDragging) {
            previewBitmap = loadThumbnail(previewPage)
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (hasChapter) "$chapterPage" else "${currentPage + 1}",
                color = Color.White,
                modifier = Modifier.width(40.dp),
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = currentPage.toFloat(),
                onValueChange = {
                    previewPage = it.toInt().coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                    isDragging = true
                },
                onValueChangeFinished = {
                    isDragging = false
                    onPageSelected(previewPage)
                },
                valueRange = 0f..(totalPages - 1).coerceAtLeast(0).toFloat(),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$totalPages",
                color = Color.White,
                modifier = Modifier.width(40.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        // 拖动时显示的缩略图预览卡片
        if (isDragging && totalPages > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-100).dp)
                    .width(80.dp)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            ) {
                previewBitmap?.let { bmp ->
                    if (!bmp.isRecycled) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = "${previewPage + 1} / $totalPages",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

@Composable
private fun ThumbnailItem(
    index: Int,
    isCurrent: Boolean,
    loadPage: suspend (Int) -> Bitmap?,
    onClick: () -> Unit
) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index) {
        bitmap = loadPage(index)
    }
    val borderColor = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier = Modifier
            .padding(3.dp)
            .width(90.dp)
            .fillMaxHeight()
            .aspectRatio(0.7f)
            .background(Color.DarkGray)
            .border(2.dp, borderColor)
            .pointerInput(Unit) {
                detectTapGestures { onClick() }
            }
    ) {
        bitmap?.let {
            if (!it.isRecycled) {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "缩略图 $index",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color(0xDD000000))
                .padding(3.dp)
        ) {
            Text(
                text = "${index + 1}",
                color = if (isCurrent) Color.Yellow else Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/**
 * AI智能分镜阅读器
 * 将检测到的漫画分镜逐格放大显示
 */
@Composable
private fun PanelViewer(
    panelState: ReaderViewModel.PanelViewState,
    rtl: Boolean = false,
    loadPage: suspend (Int) -> Bitmap?,
    onPrevPanel: () -> Unit,
    onNextPanel: () -> Unit,
    onExit: () -> Unit
) {
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    val currentPanel = panelState.panels.getOrNull(panelState.currentPanelIndex)

    DisposableEffect(panelState.pageIndex) {
        val job = scope.launch {
            val loaded = loadPage(panelState.pageIndex)
            // 复制一份，避免与阅读器位图缓存共用（缓存可能在内存紧张时被回收导致闪退）。
            // 尽量复用原图配置，RGB_565 源不强制升到 ARGB_8888，降低内存峰值。
            val newBmp = loaded?.let {
                if (it.isRecycled) null else it.copy(it.config, false)
            }
            pageBitmap?.let { old -> if (!old.isRecycled && old !== newBmp) runCatching { old.recycle() } }
            pageBitmap = newBmp
        }
        onDispose {
            job.cancel()
            pageBitmap?.let { old -> if (!old.isRecycled) runCatching { old.recycle() } }
            pageBitmap = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (panelState.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        } else if (pageBitmap != null && currentPanel != null) {
            // 裁剪显示当前分镜区域
            val bmp = pageBitmap!!
            val bounds = currentPanel.bounds
            val cropLeft = bounds.left.coerceIn(0, bmp.width)
            val cropTop = bounds.top.coerceIn(0, bmp.height)
            val cropWidth = (bounds.width()).coerceIn(1, bmp.width - cropLeft)
            val cropHeight = (bounds.height()).coerceIn(1, bmp.height - cropTop)

            var cropped by remember { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(panelState.pageIndex, panelState.currentPanelIndex, pageBitmap) {
                val b = pageBitmap ?: return@LaunchedEffect
                if (b.isRecycled || cropWidth <= 0 || cropHeight <= 0) return@LaunchedEffect
                val newCropped = runCatching {
                    Bitmap.createBitmap(b, cropLeft, cropTop, cropWidth, cropHeight)
                }.getOrNull()
                cropped?.let { old -> if (!old.isRecycled && old !== b) runCatching { old.recycle() } }
                cropped = newCropped
            }
            DisposableEffect(Unit) {
                onDispose {
                    cropped?.let { old -> if (!old.isRecycled && old !== pageBitmap) runCatching { old.recycle() } }
                    pageBitmap?.let { old -> if (!old.isRecycled) runCatching { old.recycle() } }
                    pageBitmap = null
                }
            }

            if (cropped != null && !cropped!!.isRecycled) {
                Image(
                    bitmap = cropped!!.asImageBitmap(),
                    contentDescription = "分镜 ${panelState.currentPanelIndex + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )
            }

            // 分镜进度指示器
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "分镜 ${panelState.currentPanelIndex + 1} / ${panelState.panels.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        // 底部导航按钮
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(
                onClick = onExit,
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(0xAA333333), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "退出分镜", tint = Color.White)
            }
            if (panelState.currentPanelIndex > 0) {
                IconButton(
                    onClick = onPrevPanel,
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color(0xAA333333), CircleShape)
                ) {
                    Icon(
                        imageVector = if (rtl) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "上一格",
                        tint = Color.White
                    )
                }
            }
            Text(
                text = "${panelState.currentPanelIndex + 1}/${panelState.panels.size}",
                color = Color.White,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            if (panelState.currentPanelIndex < panelState.panels.size - 1) {
                IconButton(
                    onClick = onNextPanel,
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color(0xAA333333), CircleShape)
                ) {
                    Icon(
                        imageVector = if (rtl) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "下一格",
                        tint = Color.White
                    )
                }
            }
        }

        // 点击翻页区域：日漫(RTL)从左向右读分镜，点击左侧下一格、右侧上一格
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(rtl) {
                    detectTapGestures { tapOffset ->
                        val width = size.width.toFloat()
                        if (rtl) {
                            if (tapOffset.x < width * 0.3f) {
                                onNextPanel()
                            } else if (tapOffset.x > width * 0.7f) {
                                onPrevPanel()
                            }
                        } else {
                            if (tapOffset.x < width * 0.3f) {
                                onPrevPanel()
                            } else if (tapOffset.x > width * 0.7f) {
                                onNextPanel()
                            }
                        }
                    }
                }
        )
    }
}

/**
 * 绘制阅读背景纹理。使用确定性伪随机图案，避免重组时闪烁。
 */
private fun DrawScope.drawBackground(baseColor: Color, texture: BackgroundTexture) {
    drawRect(baseColor)
    if (texture == BackgroundTexture.NONE) return

    val w = size.width
    val h = size.height
    // 基于坐标生成稳定的伪随机数
    fun pseudoRandom(seed: Int): Float {
        var x = seed * 374761393
        x = (x shl 13) xor x
        return 1f - ((x * (x * x * 15731 + 789221) + 1376312589) and 0x7fffffff) / 1073741824f
    }

    when (texture) {
        BackgroundTexture.KRAFT -> {
            drawRect(Color(0xFFD7C49E))
            val color = Color(0x20FFFFFF)
            for (i in 0 until 300) {
                val x = pseudoRandom(i * 3) * w
                val y = pseudoRandom(i * 7) * h
                drawCircle(color, radius = 1.2f, center = Offset(x, y))
            }
            for (i in 0 until 20) {
                val y = pseudoRandom(i * 11) * h
                drawLine(Color(0x15FFFFFF), start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
            }
        }
        BackgroundTexture.GLASS -> {
            val cell = 48f
            val lineColor = Color(0x18FFFFFF)
            var x = 0f
            while (x < w) {
                drawLine(lineColor, start = Offset(x, 0f), end = Offset(x, h), strokeWidth = 1f)
                x += cell
            }
            var y = 0f
            while (y < h) {
                drawLine(lineColor, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
                y += cell
            }
            for (i in 0 until 80) {
                val px = pseudoRandom(i * 5) * w
                val py = pseudoRandom(i * 13) * h
                drawCircle(Color(0x12FFFFFF), radius = 2f + pseudoRandom(i) * 6f, center = Offset(px, py))
            }
        }
        BackgroundTexture.STARS -> {
            drawRect(Color(0xFF050510))
            for (i in 0 until 200) {
                val px = pseudoRandom(i * 17) * w
                val py = pseudoRandom(i * 23) * h
                val r = 0.6f + pseudoRandom(i * 31) * 2.2f
                val alpha = 0.3f + pseudoRandom(i * 41) * 0.7f
                drawCircle(Color.White.copy(alpha = alpha), radius = r, center = Offset(px, py))
            }
        }
        BackgroundTexture.WOOD -> {
            drawRect(Color(0xFF5D4037))
            for (i in 0 until 40) {
                val y = pseudoRandom(i * 29) * h
                val stroke = 1f + pseudoRandom(i * 37) * 3f
                drawLine(Color(0x28FFFFFF), start = Offset(0f, y), end = Offset(w, y), strokeWidth = stroke)
            }
            for (i in 0 until 100) {
                val x = pseudoRandom(i * 43) * w
                val y = pseudoRandom(i * 47) * h
                drawCircle(Color(0x18FFFFFF), radius = 0.8f, center = Offset(x, y))
            }
        }
        else -> {}
    }
}
