#!/usr/bin/env python3
"""Split ReaderScreen.kt into ReaderScreen.kt (entry) and ReaderScreenComponents.kt (UI components)."""
import re
from pathlib import Path

root = Path('d:/manhua2222')
reader_file = root / 'app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt'
components_file = root / 'app/src/main/kotlin/com/mangareader/reader/ReaderScreenComponents.kt'

original = reader_file.read_text(encoding='utf-8')
lines = original.splitlines(keepends=True)

# Find the ReaderScreen function boundaries.
reader_screen_start = None
for i, line in enumerate(lines):
    if line.strip() == 'fun ReaderScreen(':
        # preceding line should be @Composable
        if i > 0 and lines[i-1].strip() == '@Composable':
            reader_screen_start = i - 1
            break

reader_screen_end = None
for i, line in enumerate(lines):
    if '* 阅读页顶部状态栏：' in line:
        reader_screen_end = i  # exclude doc line
        break

assert reader_screen_start is not None, 'ReaderScreen start not found'
assert reader_screen_end is not None, 'ReaderScreen end not found'
# Walk back to remove trailing blank lines before the doc
while reader_screen_end > reader_screen_start and lines[reader_screen_end - 1].strip() == '':
    reader_screen_end -= 1

components_lines = lines[:reader_screen_start] + lines[reader_screen_end:]
components = ''.join(components_lines)

# Make drawBackground internal so ReaderScreen can use it.
components = components.replace('private fun DrawScope.drawBackground(', 'internal fun DrawScope.drawBackground(')

# Make MenuOverlay internal (keep its signature).
components = components.replace(
    '@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun MenuOverlay(',
    '@OptIn(ExperimentalMaterial3Api::class)\n@Composable\ninternal fun MenuOverlay('
)

# Rename top/bottom bars inside the menu overlay.
components = components.replace('MinimalTopBar', 'ReaderTopBar')
components = components.replace('MinimalBottomBar', 'ReaderBottomMenu')

reader_page_content = r'''
/**
 * 阅读器页面内容主体：根据当前状态渲染分镜、页模式/滚动模式页面、状态栏与缩略图条。
 */
@Composable
internal fun ReaderPageContent(
    state: ReaderViewModel.ReaderUiState,
    viewModel: ReaderViewModel,
    panelState: ReaderViewModel.PanelViewState,
    showMenu: Boolean,
    scale: Float,
    offset: Offset,
    onPinchZoom: (Float, Offset) -> Unit,
    onZoomTo: (Float, Offset) -> Unit,
    onPan: (Offset) -> Unit,
    onBack: () -> Unit
) {
    // 自动亮度/亮度动画以 colorMatrix 实时叠加，避免每帧重处理位图
    val brightnessOverlay = if (state.brightnessOverride.isNaN()) {
        1f
    } else {
        val base = state.settings.brightness.takeIf { it > 0f } ?: 1f
        state.brightnessOverride / base
    }

    val gestureConfig = state.settings.gestureConfig

    if (panelState.isActive) {
        PanelViewer(
            panelState = panelState,
            rtl = state.settings.rtl,
            loadPage = remember(viewModel) { { index -> viewModel.loadPageBitmap(index) } },
            onPrevPanel = remember(viewModel) { { viewModel.prevPanel() } },
            onNextPanel = remember(viewModel) { { viewModel.nextPanel() } },
            onExit = remember(viewModel) { { viewModel.exitPanelView() } }
        )
    } else {
        when (state.settings.scrollMode) {
            ScrollMode.PAGE -> PageViewer(
                currentPage = state.currentPage,
                totalPages = state.totalPages,
                dualPage = state.settings.dualPage,
                autoDualPageInLandscape = state.settings.autoDualPageInLandscape,
                rtl = state.settings.rtl,
                dualPageStartOne = state.settings.dualPageStartOne,
                dualPageOffset = state.settings.dualPageOffset,
                dualPageSpacing = state.settings.dualPageSpacing,
                pageShadow = state.settings.pageShadow,
                zoomMode = state.settings.zoomMode,
                pageAnimation = state.settings.pageAnimation,
                randomAnimation = state.settings.randomAnimation,
                pageAnimSpeed = state.settings.pageAnimSpeed,
                enableZoom = state.settings.enableZoom,
                tapZoneSize = state.settings.tapZoneSize,
                magnifierEnabled = state.settings.magnifierEnabled,
                antiMistouch = state.settings.antiMistouch,
                gestureConfig = gestureConfig,
                settingsVersion = state.settingsVersion,
                loadPage = remember(viewModel) { { index -> viewModel.loadPageBitmap(index) } },
                flipSoundEnabled = state.settings.flipSoundEnabled,
                flipVibrationEnabled = state.settings.flipVibrationEnabled,
                onTapNext = remember(viewModel) { { viewModel.nextPage() } },
                onTapPrev = remember(viewModel) { { viewModel.previousPage() } },
                onTapMenu = remember(viewModel) { { viewModel.toggleMenu() } },
                onPinchZoom = onPinchZoom,
                onZoomTo = onZoomTo,
                onPan = onPan,
                onAction = remember(viewModel, onBack, state.settings.brightness, state.settings.autoPageInterval) { { action ->
                    when (action) {
                        GestureConfig.GestureAction.BACK -> onBack()
                        GestureConfig.GestureAction.NEXT_CHAPTER -> viewModel.jumpToNextChapter()
                        GestureConfig.GestureAction.PREV_CHAPTER -> viewModel.jumpToPrevChapter()
                        GestureConfig.GestureAction.PANEL_VIEW -> viewModel.enterPanelView()
                        GestureConfig.GestureAction.BOOKMARK -> viewModel.toggleBookmark()
                        GestureConfig.GestureAction.AUTO_CROP_TOGGLE -> viewModel.toggleAutoCrop()
                        GestureConfig.GestureAction.ROTATE -> viewModel.rotate()
                        GestureConfig.GestureAction.BRIGHTNESS_UP -> viewModel.setBrightness(state.settings.brightness + 0.1f, animate = true)
                        GestureConfig.GestureAction.BRIGHTNESS_DOWN -> viewModel.setBrightness(state.settings.brightness - 0.1f, animate = true)
                        GestureConfig.GestureAction.SLIDESHOW_TOGGLE -> {
                            val newInterval = if (state.settings.autoPageInterval > 0) 0 else 5
                            viewModel.setAutoPageInterval(newInterval)
                        }
                        else -> {}
                    }
                } },
                scale = scale,
                offset = offset,
                brightnessOverlay = brightnessOverlay
            )
            ScrollMode.VERTICAL -> VerticalScrollViewer(
                totalPages = state.totalPages,
                startPage = state.currentPage,
                rtl = state.settings.rtl,
                antiMistouch = state.settings.antiMistouch,
                settingsVersion = state.settingsVersion,
                loadPage = remember(viewModel) { { index -> viewModel.loadPageBitmap(index) } },
                onTapMenu = remember(viewModel) { { viewModel.toggleMenu() } },
                onPageChanged = remember(viewModel) { { idx -> viewModel.setCurrentPage(idx) } },
                brightnessOverlay = brightnessOverlay
            )
            ScrollMode.HORIZONTAL -> HorizontalScrollViewer(
                totalPages = state.totalPages,
                startPage = state.currentPage,
                rtl = state.settings.rtl,
                antiMistouch = state.settings.antiMistouch,
                settingsVersion = state.settingsVersion,
                loadPage = remember(viewModel) { { index -> viewModel.loadPageBitmap(index) } },
                onTapMenu = remember(viewModel) { { viewModel.toggleMenu() } },
                onPageChanged = remember(viewModel) { { idx -> viewModel.setCurrentPage(idx) } },
                brightnessOverlay = brightnessOverlay
            )
        }
    }

    if (state.settings.readerStatusBar && !showMenu && !panelState.isActive && state.totalPages > 0) {
        ReaderStatusBar(
            state = state,
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }

    if (state.settings.thumbnailStripEnabled && !showMenu && !panelState.isActive && state.totalPages > 0) {
        ThumbnailStrip(
            currentPage = state.currentPage,
            totalPages = state.totalPages,
            rtl = state.settings.rtl,
            loadPage = remember(viewModel) { { index -> viewModel.loadThumbnail(index, 120) } },
            onPageSelected = remember(viewModel) { { viewModel.setCurrentPage(it) } },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (state.settings.scrollMode == ScrollMode.PAGE && !showMenu && !panelState.isActive) {
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
}

'''

# Insert ReaderPageContent before ReaderStatusBar doc.
components = components.replace(
    '/**\n * 阅读页顶部状态栏：显示漫画标题、页码、电量、时间与本次阅读时长，',
    reader_page_content + '/**\n * 阅读页顶部状态栏：显示漫画标题、页码、电量、时间与本次阅读时长，'
)

reader_gesture_layer = r'''
/**
 * 阅读器手势处理层：统一处理点击翻页、双击缩放、长按、滑动翻页与双指缩放。
 */
@Composable
private fun ReaderGestureLayer(
    scale: Float,
    offset: Offset,
    rtl: Boolean,
    gestureConfig: GestureConfig,
    tapZoneSize: com.mangareader.data.TapZoneSize,
    magnifierEnabled: Boolean,
    antiMistouch: Boolean,
    enableZoom: Boolean,
    onTapNext: () -> Unit,
    onTapPrev: () -> Unit,
    onTapMenu: () -> Unit,
    onPinchZoom: (Float, Offset) -> Unit,
    onZoomTo: (Float, Offset) -> Unit,
    onPan: (Offset) -> Unit,
    onAction: (GestureConfig.GestureAction) -> Unit,
    onMagnifier: (Offset) -> Unit,
    magnifierActive: Boolean = false,
    onDismissMagnifier: () -> Unit = {},
    content: @Composable () -> Unit
) {
    val gestureModifier = Modifier
        .fillMaxSize()
        // 单指拖动平移：仅在放大状态下启用，避免遮挡未放大时的翻页手势
        // key 包含 gestureConfig，保证手势配置变更后重新注册
        .pointerInput(scale, gestureConfig) {
            if (scale > 1.05f) {
                detectDragGestures(
                    onDrag = { change: PointerInputChange, dragAmount: Offset ->
                        change.consume()
                        onPan(dragAmount)
                    }
                )
            }
        }
        // 水平滑动翻页：仅在未放大时启用
        // 增加轻拂（fling）检测：即使滑动距离短，只要速度够快也能翻页
        .pointerInput(scale, rtl, gestureConfig) {
            if (scale <= 1.05f) {
                var totalDrag = 0f
                val velocityTracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        totalDrag = 0f
                        velocityTracker.resetTracking()
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        totalDrag += dragAmount
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                    },
                    onDragEnd = {
                        val velocity = velocityTracker.calculateVelocity()
                        val flingThreshold = 2000f
                        // RTL 日漫从右向左阅读：向右滑动翻到下一页，向左滑动返回上一页
                        val isNext = if (rtl) {
                            totalDrag > 100f || velocity.x > flingThreshold
                        } else {
                            totalDrag < -100f || velocity.x < -flingThreshold
                        }
                        val isPrev = if (rtl) {
                            totalDrag < -100f || velocity.x < -flingThreshold
                        } else {
                            totalDrag > 100f || velocity.x > flingThreshold
                        }
                        when {
                            isNext -> onTapNext()
                            isPrev -> onTapPrev()
                        }
                    }
                )
            }
        }
        .pointerInput(enableZoom, gestureConfig) {
            if (enableZoom) {
                detectTransformGestures { _, pan, zoom, _ ->
                    // 仅在双指缩放（zoom != 1f）时处理，避免单指拖动与 detectDragGestures 冲突导致 panOffset 双倍叠加
                    if (zoom != 1f) {
                        onPinchZoom(zoom, pan)
                    }
                }
            }
        }
        .pointerInput(gestureConfig, tapZoneSize, rtl, magnifierEnabled) {
            val toggleZoom: (Offset) -> Unit = { tap ->
                if (enableZoom) {
                    val containerW = size.width.toFloat()
                    val containerH = size.height.toFloat()
                    val targetScale = if (scale > 1.1f) 1f else 2.5f
                    val targetOffset = if (targetScale == 1f) {
                        Offset.Zero
                    } else {
                        val tapRelX = tap.x - containerW / 2f
                        val tapRelY = tap.y - containerH / 2f
                        val imgX = (tapRelX - offset.x) / scale
                        val imgY = (tapRelY - offset.y) / scale
                        val maxOffsetX = containerW * (targetScale - 1f) / 2f
                        val maxOffsetY = containerH * (targetScale - 1f) / 2f
                        Offset(
                            x = (tapRelX - imgX * targetScale).coerceIn(-maxOffsetX, maxOffsetX),
                            y = (tapRelY - imgY * targetScale).coerceIn(-maxOffsetY, maxOffsetY)
                        )
                    }
                    onZoomTo(targetScale, targetOffset)
                }
            }
            detectTapGestures(
                onDoubleTap = { pos ->
                    when (gestureConfig.doubleTap) {
                        GestureConfig.GestureAction.ZOOM_TOGGLE -> toggleZoom(pos)
                        GestureConfig.GestureAction.PANEL_VIEW -> onAction(GestureConfig.GestureAction.PANEL_VIEW)
                        GestureConfig.GestureAction.NEXT_PAGE -> onTapNext()
                        GestureConfig.GestureAction.PREV_PAGE -> onTapPrev()
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
                        GestureConfig.GestureAction.MAGNIFIER -> if (magnifierEnabled) onMagnifier(pos)
                        GestureConfig.GestureAction.TOGGLE_MENU -> onTapMenu()
                        GestureConfig.GestureAction.PANEL_VIEW -> onAction(GestureConfig.GestureAction.PANEL_VIEW)
                        GestureConfig.GestureAction.BOOKMARK -> onAction(GestureConfig.GestureAction.BOOKMARK)
                        GestureConfig.GestureAction.BACK -> onAction(GestureConfig.GestureAction.BACK)
                        GestureConfig.GestureAction.NEXT_CHAPTER -> onAction(GestureConfig.GestureAction.NEXT_CHAPTER)
                        GestureConfig.GestureAction.PREV_CHAPTER -> onAction(GestureConfig.GestureAction.PREV_CHAPTER)
                        GestureConfig.GestureAction.AUTO_CROP_TOGGLE -> onAction(GestureConfig.GestureAction.AUTO_CROP_TOGGLE)
                        GestureConfig.GestureAction.ROTATE -> onAction(GestureConfig.GestureAction.ROTATE)
                        GestureConfig.GestureAction.ZOOM_TOGGLE -> toggleZoom(pos)
                        else -> {}
                    }
                },
                onTap = { tapOffset ->
                    if (magnifierActive) {
                        onDismissMagnifier()
                        return@detectTapGestures
                    }
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    val x = tapOffset.x
                    val y = tapOffset.y
                    // 防误触：仅忽略屏幕最边缘的小范围误触，保留中心区正常操作
                    if (antiMistouch) {
                        val edgeMarginX = width * 0.04f
                        val edgeMarginY = height * 0.03f
                        if (x < edgeMarginX || x > width - edgeMarginX ||
                            y < edgeMarginY || y > height - edgeMarginY) {
                            return@detectTapGestures
                        }
                    }
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
                        GestureConfig.GestureAction.NEXT_PAGE -> onTapNext()
                        GestureConfig.GestureAction.PREV_PAGE -> onTapPrev()
                        GestureConfig.GestureAction.TOGGLE_MENU -> onTapMenu()
                        GestureConfig.GestureAction.BOOKMARK -> onAction(GestureConfig.GestureAction.BOOKMARK)
                        GestureConfig.GestureAction.BACK -> onAction(GestureConfig.GestureAction.BACK)
                        GestureConfig.GestureAction.ZOOM_TOGGLE -> toggleZoom(tapOffset)
                        GestureConfig.GestureAction.MAGNIFIER -> if (magnifierEnabled) onMagnifier(tapOffset)
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
    Box(modifier = gestureModifier) { content() }
}

'''

# Insert ReaderGestureLayer before PageViewer.
components = components.replace(
    '@Composable\nprivate fun PageViewer(',
    reader_gesture_layer + '@Composable\nprivate fun PageViewer('
)

# Remove the old transformModifier block from PageViewer.
components = re.sub(
    r'(?s)\n    val transformModifier = Modifier.*?\n    }\n\n    val renderPage:',
    '\n    val renderPage:',
    components,
    count=1
)

# Replace BoxWithConstraints opening with ReaderGestureLayer wrapper.
old_box_open = '''    BoxWithConstraints(
        modifier = transformModifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(context.getString(R.string.reader_previous_page)) { realOnTapPrev(); true },
                CustomAccessibilityAction(context.getString(R.string.reader_next_page)) { realOnTapNext(); true },
                CustomAccessibilityAction(context.getString(R.string.reader_open_menu)) { onTapMenu(); true }
            )
        },
        contentAlignment = Alignment.Center
    ) {'''
new_box_open = '''    ReaderGestureLayer(
        scale = scale,
        offset = offset,
        rtl = rtl,
        gestureConfig = gestureConfig,
        tapZoneSize = tapZoneSize,
        magnifierEnabled = magnifierEnabled,
        antiMistouch = antiMistouch,
        enableZoom = enableZoom,
        onTapNext = realOnTapNext,
        onTapPrev = realOnTapPrev,
        onTapMenu = onTapMenu,
        onPinchZoom = onPinchZoom,
        onZoomTo = onZoomTo,
        onPan = onPan,
        onAction = onAction,
        onMagnifier = { magnifierPos = it },
        magnifierActive = magnifierPos != null,
        onDismissMagnifier = {
            magnifierPos = null
            magnifierBitmap = null
            magnifierCrop?.let { if (!it.isRecycled) runCatching { it.recycle() } }
            magnifierCrop = null
        }
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(context.getString(R.string.reader_previous_page)) { realOnTapPrev(); true },
                        CustomAccessibilityAction(context.getString(R.string.reader_next_page)) { realOnTapNext(); true },
                        CustomAccessibilityAction(context.getString(R.string.reader_open_menu)) { onTapMenu(); true }
                    )
                },
            contentAlignment = Alignment.Center
        ) {'''

components = components.replace(old_box_open, new_box_open)

# Close the ReaderGestureLayer after BoxWithConstraints.
components = components.replace(
    '    }\n}\n\nprivate fun computeMagnifierCrop(',
    '    }\n    }\n}\n\nprivate fun computeMagnifierCrop(',
    1
)

components_file.write_text(components, encoding='utf-8')

# Build new ReaderScreen.kt.
reader_screen_content = r'''package com.mangareader.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
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
import com.mangareader.data.ComicHolder
import com.mangareader.data.GestureConfig
import com.mangareader.data.OrientationLock
import com.mangareader.data.ScrollMode
import com.mangareader.data.SettingsRepository
import com.mangareader.utils.rememberBrightnessSensorManager
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
    val showMenu by viewModel.showMenu.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val ttsInitSuccess by viewModel.ttsInitSuccess.collectAsStateWithLifecycle()

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
    // 使用用户自定义手势配置；未自定义时默认根据 RTL 选择日漫/国漫预设
    val gestureConfig = state.settings.gestureConfig

    // 保存打开 Reader 时的分卷列表，重试时使用
    val rememberedAllChapters = remember { ComicHolder.chapters }
    // 记录当前漫画是否已展示过卷末对话框，切换漫画时重置；使用 rememberSaveable 避免配置变更后重复弹出。
    var chapterEndShown by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(comicEntry) {
        chapterEndShown = false
        comicEntry?.let {
            // 优先使用 LaunchedEffect 触发时 ComicHolder 内的最新值，
            // 若已被清空则回退到 remember 捕获的值（如配置变更后重组场景）。
            val chapterPages = ComicHolder.chapter?.pages
            val allChapters = ComicHolder.chapters ?: rememberedAllChapters
            viewModel.loadComic(it, chapterPages, allChapters)
            ComicHolder.chapter = null
        }
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

    // 卷末跳转对话框：derivedStateOf 只在 showChapterEndDialog 变化时重新计算，
    // 避免对话框显示期间频繁读取 ViewModel 状态导致不必要的计算。
    val chapterEndInfo by remember {
        derivedStateOf {
            if (showChapterEndDialog) {
                viewModel.checkChapterEnd().first to viewModel.getNextChapterTitle()
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

    // 自动亮度：根据环境光传感器实时调整阅读亮度（仅作为临时覆盖，不写入持久化设置）
    val autoBrightness by rememberBrightnessSensorManager(state.settings.autoBrightness)
    LaunchedEffect(autoBrightness, state.settings.autoBrightness) {
        if (state.settings.autoBrightness) {
            viewModel.setAutoBrightness(autoBrightness)
        } else {
            viewModel.clearAutoBrightness()
        }
    }

    // 卷末检测：当翻到当前卷最后一页时，弹出跳转对话框（每卷仅提示一次）。
    // 仅在 currentPage 到达或超过最后一页时执行检测，避免普通翻页也频繁访问 ViewModel。
    LaunchedEffect(state.currentPage, state.totalPages) {
        if (!chapterEndShown &&
            state.settings.scrollMode == ScrollMode.PAGE &&
            state.totalPages > 0 &&
            state.currentPage >= state.totalPages - 1) {
            val (isLastChapter, nextIdx) = viewModel.checkChapterEnd()
            if (isLastChapter || nextIdx != null) {
                chapterEndShown = true
                showChapterEndDialog = true
            }
        }
    }

    // 切换卷后重置卷末提示，避免跨卷后重复弹窗或该弹时不弹
    LaunchedEffect(state.currentChapter) {
        chapterEndShown = false
    }

    val fallbackBg = MaterialTheme.colorScheme.background
    val bgColor = remember(state.settings.backgroundColor, fallbackBg) {
        runCatching { Color(android.graphics.Color.parseColor(state.settings.backgroundColor)) }.getOrDefault(fallbackBg)
    }

    val currentPanelActive by rememberUpdatedState(panelState.isActive)
    val currentShowMenu by rememberUpdatedState(showMenu)
    val currentExitConfirmDisabled by rememberUpdatedState(exitConfirmDisabled)
    val backHandler = remember {
        {
            if (currentPanelActive) {
                viewModel.exitPanelView()
            } else if (currentShowMenu) {
                viewModel.hideMenu()
            } else if (currentExitConfirmDisabled) {
                onBack()
            } else {
                showExitConfirm = true
            }
        }
    }
    BackHandler(onBack = backHandler)

    val onPinchZoom: (Float, Offset) -> Unit = remember(scale, panOffset) { { zoomDelta, panDelta ->
        val newScale = (scale * zoomDelta).coerceIn(1f, 5f)
        val newOffset = panOffset + panDelta
        scale = newScale
        panOffset = newOffset
    } }
    val onPan: (Offset) -> Unit = remember(panOffset) { { delta -> panOffset = panOffset + delta } }
    val onAction: (GestureConfig.GestureAction) -> Unit = remember(viewModel, onBack, state.settings.brightness, state.settings.autoPageInterval) { { action ->
        when (action) {
            GestureConfig.GestureAction.BACK -> onBack()
            GestureConfig.GestureAction.NEXT_CHAPTER -> viewModel.jumpToNextChapter()
            GestureConfig.GestureAction.PREV_CHAPTER -> viewModel.jumpToPrevChapter()
            GestureConfig.GestureAction.PANEL_VIEW -> viewModel.enterPanelView()
            GestureConfig.GestureAction.BOOKMARK -> viewModel.toggleBookmark()
            GestureConfig.GestureAction.AUTO_CROP_TOGGLE -> viewModel.toggleAutoCrop()
            GestureConfig.GestureAction.ROTATE -> viewModel.rotate()
            GestureConfig.GestureAction.BRIGHTNESS_UP -> viewModel.setBrightness(state.settings.brightness + 0.1f, animate = true)
            GestureConfig.GestureAction.BRIGHTNESS_DOWN -> viewModel.setBrightness(state.settings.brightness - 0.1f, animate = true)
            GestureConfig.GestureAction.SLIDESHOW_TOGGLE -> {
                val newInterval = if (state.settings.autoPageInterval > 0) 0 else 5
                viewModel.setAutoPageInterval(newInterval)
            }
            else -> {}
        }
    } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawBackground(bgColor, state.settings.backgroundTexture) }
    ) {
        val readerStateColor = MaterialTheme.colorScheme.onSurface
        when {
            comicEntry == null -> {
                Text(
                    text = stringResource(R.string.reader_invalid_comic),
                    color = readerStateColor,
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = TextAlign.Center
                )
            }
            state.isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = readerStateColor)
            }
            state.error != null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.error ?: "",
                        color = readerStateColor,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val onRetry = remember(comicEntry, rememberedAllChapters, viewModel) {
                        { viewModel.loadComic(comicEntry, chapterPages = null, allChapters = rememberedAllChapters) }
                    }
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.reader_retry), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            state.totalPages > 0 -> {
                ReaderPageContent(
                    state = state,
                    viewModel = viewModel,
                    panelState = panelState,
                    showMenu = showMenu,
                    scale = scale,
                    offset = panOffset,
                    onPinchZoom = onPinchZoom,
                    onZoomTo = animatedZoomTo,
                    onPan = onPan,
                    onBack = onBack
                )
            }
        }

        if (showMenu) {
            val shareSourceScope = rememberCoroutineScope()
            var isSharingSource by remember { mutableStateOf(false) }
            val onJumpToPage = remember(viewModel) { { page: Int -> viewModel.setCurrentPage(page - 1) } }
            val onCloseMenu = remember(viewModel) { { viewModel.hideMenu() } }
            val onShareSource = remember(viewModel, shareSourceScope, context, state.title, isSharingSource) {
                {
                    if (!isSharingSource) {
                        isSharingSource = true
                        shareSourceScope.launch {
                            val file = viewModel.shareSourceFile()
                            isSharingSource = false
                            if (file != null) {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    context.packageName + com.mangareader.Constants.FILE_PROVIDER_AUTHORITY_SUFFIX,
                                    file
                                )
                                val type = context.contentResolver.getType(uri) ?: "*/*"
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    setType(type)
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, state.title)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching {
                                    context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.reader_share_source_chooser)))
                                }.onFailure {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.reader_share_source_failed_toast),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else {
                                Toast.makeText(context, context.getString(R.string.reader_share_source_failed_toast), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            MenuOverlay(
                state = state,
                viewModel = viewModel,
                bookmarks = bookmarks,
                onJumpToPage = onJumpToPage,
                onClose = onCloseMenu,
                onOpenSettings = onOpenSettings,
                isLargeScreen = isLargeScreen,
                isSharingSource = isSharingSource,
                onShareSource = onShareSource
            )
        }
    }
}
'''

reader_file.write_text(reader_screen_content, encoding='utf-8')

print('Done splitting ReaderScreen.')
