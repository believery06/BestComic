package com.mangareader.reader

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.BatteryManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.util.Log
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import com.mangareader.R
import com.mangareader.Constants
import com.mangareader.data.BackgroundTexture
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp as lerpOffset
import androidx.compose.ui.util.lerp as lerpFloat
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangareader.data.GestureConfig
import com.mangareader.data.OrientationLock
import com.mangareader.data.ScrollMode
import com.mangareader.data.SettingsRepository
import com.mangareader.data.TapZone
import com.mangareader.data.ZoomMode
import com.mangareader.data.getTapZone
import com.mangareader.utils.rememberBrightnessSensorManager
import com.mangareader.utils.rememberWindowSizeClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "ReaderScreen"

/**
 * 阅读器浮层/遮罩统一取色：
 * - 背景使用 surface 半透明，确保在不同阅读主题下都能自然融入；
 * - 内容色使用 onSurface 半透明，避免浅色主题下纯白文字刺眼；
 * - 遮罩使用 scrim 半透明，替代硬编码黑色。
 */
@Composable
private fun readerOverlayBackground(): Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)

@Composable
private fun readerOverlayContentColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)

@Composable
private fun readerOverlayMutedColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

@Composable
private fun readerScrimColor(): Color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)

/**
 * 阅读页顶部状态栏：显示漫画标题、页码、电量、时间与本次阅读时长，
 * 仅在非菜单/分镜状态下出现，不影响主要内容触控。
 */
@Composable
private fun ReaderStatusBar(
    state: ReaderViewModel.ReaderUiState,
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var timeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        // 进入时立即刷新一次，避免显示进入前缓存的旧时间；
        // 之后对齐到下一整分钟刷新，保证分钟切换及时。
        timeTick++
        while (true) {
            val now = System.currentTimeMillis()
            val nextMinute = ((now / 60_000L) + 1) * 60_000L
            delay(nextMinute - now)
            timeTick++
        }
    }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()) }
    val timeStr = remember(timeTick) {
        timeFormatter.format(LocalDateTime.now())
    }
    val contentColor = readerOverlayContentColor()
    val mutedColor = readerOverlayMutedColor()
    var battery by remember { mutableIntStateOf(-1) }
    DisposableEffect(Unit) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                battery = if (level >= 0 && scale > 0) {
                    (level * 100 / scale)
                } else {
                    bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                }
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }
        }.onFailure {
            android.util.Log.w("ReaderStatusBar", "注册电量广播失败", it)
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(readerOverlayBackground())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = state.title,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (state.currentChapter.isNotEmpty()) {
                "${state.currentChapter} ${state.currentPage + 1}/${state.totalPages}"
            } else {
                "${state.currentPage + 1}/${state.totalPages}"
            },
            color = mutedColor,
            style = MaterialTheme.typography.labelSmall
        )
        val sessionDurationMs = remember(timeTick) { viewModel.currentSessionDurationMs() }
        if (sessionDurationMs > 0L) {
            Text(
                text = formatSessionDuration(sessionDurationMs),
                color = mutedColor,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        if (battery >= 0) {
            Text(
                text = "$battery%",
                color = mutedColor,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        Text(
            text = timeStr,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * 将阅读会话持续毫秒数格式化为紧凑字符串。
 * 不足 1 小时显示 MM:SS；1 小时及以上显示 H:MM:SS。
 */
private fun formatSessionDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}


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
        // 使用 gestureConfig 的 swipe 映射，使滑动手势随预设（日漫/国漫）切换
        .pointerInput(scale, gestureConfig) {
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
                        // 检测拖动方向：左滑（负方向）或右滑（正方向）
                        val isDragLeft = totalDrag < -100f || velocity.x < -flingThreshold
                        val isDragRight = totalDrag > 100f || velocity.x > flingThreshold

                        // 根据手势配置的 swipe 映射决定动作
                        val action = when {
                            isDragLeft -> gestureConfig.swipeLeft
                            isDragRight -> gestureConfig.swipeRight
                            else -> GestureConfig.GestureAction.NOTHING
                        }

                        when (action) {
                            GestureConfig.GestureAction.NEXT_PAGE -> onTapNext()
                            GestureConfig.GestureAction.PREV_PAGE -> onTapPrev()
                            GestureConfig.GestureAction.TOGGLE_MENU -> onTapMenu()
                            GestureConfig.GestureAction.BACK -> onAction(GestureConfig.GestureAction.BACK)
                            GestureConfig.GestureAction.BOOKMARK -> onAction(GestureConfig.GestureAction.BOOKMARK)
                            GestureConfig.GestureAction.NEXT_CHAPTER -> onAction(GestureConfig.GestureAction.NEXT_CHAPTER)
                            GestureConfig.GestureAction.PREV_CHAPTER -> onAction(GestureConfig.GestureAction.PREV_CHAPTER)
                            GestureConfig.GestureAction.AUTO_CROP_TOGGLE -> onAction(GestureConfig.GestureAction.AUTO_CROP_TOGGLE)
                            GestureConfig.GestureAction.ROTATE -> onAction(GestureConfig.GestureAction.ROTATE)
                            GestureConfig.GestureAction.SLIDESHOW_TOGGLE -> onAction(GestureConfig.GestureAction.SLIDESHOW_TOGGLE)
                            GestureConfig.GestureAction.BRIGHTNESS_UP -> onAction(GestureConfig.GestureAction.BRIGHTNESS_UP)
                            GestureConfig.GestureAction.BRIGHTNESS_DOWN -> onAction(GestureConfig.GestureAction.BRIGHTNESS_DOWN)
                            else -> {}
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

@Composable
private fun PageViewer(
    currentPage: Int,
    totalPages: Int,
    dualPage: Boolean,
    autoDualPageInLandscape: Boolean = false,
    rtl: Boolean,
    dualPageStartOne: Boolean,
    dualPageOffset: Int,
    dualPageSpacing: Int = 0,
    pageShadow: Boolean = false,
    zoomMode: ZoomMode,
    pageAnimation: com.mangareader.data.PageAnimation,
    randomAnimation: Boolean = false,
    pageAnimSpeed: Float = 1.0f,
    enableZoom: Boolean,
    tapZoneSize: com.mangareader.data.TapZoneSize,
    magnifierEnabled: Boolean,
    antiMistouch: Boolean = false,
    gestureConfig: GestureConfig,
    settingsVersion: Int,
    loadPage: suspend (Int) -> Bitmap?,
    flipSoundEnabled: Boolean,
    flipVibrationEnabled: Boolean,
    onTapNext: () -> Unit,
    onTapPrev: () -> Unit,
    onTapMenu: () -> Unit,
    onPinchZoom: (Float, Offset) -> Unit,
    onZoomTo: (Float, Offset) -> Unit,
    onPan: (Offset) -> Unit = {},
    onAction: (GestureConfig.GestureAction) -> Unit = {},
    scale: Float,
    offset: Offset,
    brightnessOverlay: Float = 1f
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val effectiveDualPage = dualPage || (autoDualPageInLandscape && isLandscape)

    fun computeDisplayIndices(page: Int): List<Int> {
        val safeTotal = (totalPages - 1).coerceAtLeast(0)
        if (!effectiveDualPage) {
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

        // 返回的逻辑顺序始终是 [当前页, 下一页]；RTL 日漫的左右视觉排布由 DualPageLayout 负责，
        // 避免此处反转后再被 DualPageLayout 的 rtl 分支二次交换，导致日漫双页顺序错误。
        return indices
    }

    var magnifierPos by remember { mutableStateOf<Offset?>(null) }
    var magnifierBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var magnifierCrop by remember { mutableStateOf<Bitmap?>(null) }
    // 标记 magnifierBitmap 是否为我们创建的独立副本（OOM 回退时可能是缓存中的 rawBmp，不可回收）。
    var magnifierBitmapOwned by remember { mutableStateOf(false) }

    // 阅读器退出时回收放大镜位图，避免内存泄漏；仅回收自己创建的副本，不碰缓存位图。
    DisposableEffect(Unit) {
        onDispose {
            if (magnifierBitmapOwned) {
                magnifierBitmap?.takeIf { !it.isRecycled }?.let { runCatching { it.recycle() } }
            }
            magnifierCrop?.takeIf { !it.isRecycled }?.let { runCatching { it.recycle() } }
            magnifierBitmap = null
            magnifierCrop = null
            magnifierBitmapOwned = false
        }
    }

    val triggerFlipFeedback = remember(flipVibrationEnabled, flipSoundEnabled) {
        {
            if (flipVibrationEnabled || flipSoundEnabled) {
                try {
                    val vibrator = context.getSystemService(android.os.Vibrator::class.java)
                    if (vibrator != null && vibrator.hasVibrator()) {
                        vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                            if (flipVibrationEnabled) 20 else 8,
                            if (flipVibrationEnabled) android.os.VibrationEffect.DEFAULT_AMPLITUDE else 60
                        ))
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val realOnTapNext = remember(triggerFlipFeedback, onTapNext) {
        {
            triggerFlipFeedback()
            onTapNext()
        }
    }
    val realOnTapPrev = remember(triggerFlipFeedback, onTapPrev) {
        {
            triggerFlipFeedback()
            onTapPrev()
        }
    }

    val renderPage: @Composable (Int) -> Unit = { page ->
        val displayIndices = computeDisplayIndices(page)
        if (effectiveDualPage && displayIndices.size > 1) {
            DualPageLayout(
                indices = displayIndices,
                totalPages = totalPages,
                loadPage = loadPage,
                zoomMode = zoomMode,
                settingsVersion = settingsVersion,
                spacingDp = dualPageSpacing,
                pageShadow = pageShadow,
                rtl = rtl,
                brightnessOverlay = brightnessOverlay
            )
        } else {
            PageImage(
                index = displayIndices.firstOrNull() ?: 0,
                totalPages = totalPages,
                loadPage = loadPage,
                zoomMode = zoomMode,
                fillMaxSize = true,
                settingsVersion = settingsVersion,
                brightnessOverlay = brightnessOverlay
            )
        }
    }

    ReaderGestureLayer(
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
        ) {
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
        // 用于稳定 AnimatedContent 的 key，避免动画状态混乱
        val animationKey = remember(currentPage, effectiveAnimation) { currentPage to effectiveAnimation }
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
                val speed = pageAnimSpeed.coerceIn(0.2f, 3.0f)
                val slideDur = (220 / speed).toInt()
                val fadeDur = (200 / speed).toInt()
                AnimatedContent(
                    targetState = animationKey,
                    transitionSpec = {
                        val isForward = targetState.first > initialState.first
                        // LTR（正常/国漫）：下一页从右滑入，当前页向左滑出
                        // RTL（日漫）：下一页从左滑入，当前页向右滑出
                        val enterOffset: (Int) -> Int
                        val exitOffset: (Int) -> Int
                        if (rtl) {
                            if (isForward) {
                                enterOffset = { w -> -w }
                                exitOffset = { w -> w }
                            } else {
                                enterOffset = { w -> w }
                                exitOffset = { w -> -w }
                            }
                        } else {
                            if (isForward) {
                                enterOffset = { w -> w }
                                exitOffset = { w -> -w }
                            } else {
                                enterOffset = { w -> -w }
                                exitOffset = { w -> w }
                            }
                        }
                        val enter = slideInHorizontally(tween(slideDur), initialOffsetX = enterOffset) +
                            fadeIn(tween(fadeDur))
                        val exit = slideOutHorizontally(tween(slideDur), targetOffsetX = exitOffset) +
                            fadeOut(tween(fadeDur))
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
                ) { pageState -> renderPage(pageState.first) }
            }
            com.mangareader.data.PageAnimation.FADE -> {
                val fadeDur = (250 / pageAnimSpeed.coerceIn(0.2f, 3.0f)).toInt()
                AnimatedContent(
                    targetState = animationKey,
                    transitionSpec = {
                        fadeIn(tween(fadeDur)) togetherWith fadeOut(tween(fadeDur))
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
                ) { pageState -> renderPage(pageState.first) }
            }
            com.mangareader.data.PageAnimation.PIXEL -> {
                val speed = pageAnimSpeed.coerceIn(0.2f, 3.0f)
                val pixelDur = (260 / speed).toInt()
                AnimatedContent(
                    targetState = animationKey,
                    transitionSpec = {
                        val forward = targetState.first > initialState.first
                        val enter = scaleIn(
                            initialScale = if (forward) 0.85f else 1.15f,
                            animationSpec = tween(pixelDur)
                        ) + fadeIn(tween(pixelDur))
                        val exit = scaleOut(
                            targetScale = if (forward) 1.15f else 0.85f,
                            animationSpec = tween(pixelDur)
                        ) + fadeOut(tween(pixelDur))
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
                    label = "pagePixel"
                ) { pageState -> renderPage(pageState.first) }
            }
            com.mangareader.data.PageAnimation.BLINDS -> {
                val speed = pageAnimSpeed.coerceIn(0.2f, 3.0f)
                val blindsDur = (280 / speed).toInt()
                AnimatedContent(
                    targetState = animationKey,
                    transitionSpec = {
                        val forward = targetState.first > initialState.first
                        val enter = slideInVertically(tween(blindsDur)) { if (forward) -it / 2 else it / 2 } +
                            fadeIn(tween(blindsDur))
                        val exit = slideOutVertically(tween(blindsDur)) { if (forward) it / 2 else -it / 2 } +
                            fadeOut(tween(blindsDur))
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
                    label = "pageBlinds"
                ) { pageState -> renderPage(pageState.first) }
            }
            com.mangareader.data.PageAnimation.SHATTER -> {
                val speed = pageAnimSpeed.coerceIn(0.2f, 3.0f)
                val shatterDur = (300 / speed).toInt()
                AnimatedContent(
                    targetState = animationKey,
                    transitionSpec = {
                        val enter = scaleIn(initialScale = 1.1f, animationSpec = tween(shatterDur)) +
                            fadeIn(tween(shatterDur))
                        val exit = shrinkOut(
                            shrinkTowards = Alignment.Center,
                            targetSize = { fullSize -> IntSize(fullSize.width / 3, fullSize.height / 3) },
                            animationSpec = tween(shatterDur)
                        ) + fadeOut(tween(shatterDur))
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
                    label = "pageShatter"
                ) { pageState -> renderPage(pageState.first) }
            }
            com.mangareader.data.PageAnimation.DOTS -> {
                val speed = pageAnimSpeed.coerceIn(0.2f, 3.0f)
                val dotsDur = (280 / speed).toInt()
                AnimatedContent(
                    targetState = animationKey,
                    transitionSpec = {
                        val enter = expandIn(
                            expandFrom = Alignment.Center,
                            initialSize = { IntSize(0, 0) },
                            animationSpec = tween(dotsDur)
                        ) + fadeIn(tween(dotsDur))
                        val exit = fadeOut(tween(dotsDur))
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
                    label = "pageDots"
                ) { pageState -> renderPage(pageState.first) }
            }
        }

        if (magnifierEnabled) {
            magnifierPos?.let { pos ->
                val densityVal = density.density
                LaunchedEffect(pos, currentPage, settingsVersion, scale, offset, containerW, containerH, densityVal) {
                    val rawBmp = loadPage(computeDisplayIndices(currentPage).firstOrNull() ?: 0)
                    // 复制一份独立副本，避免缓存回收导致放大镜位图失效；优先复用原配置降低内存峰值。
                    // OOM 时若无法复制则回退到 rawBmp，此时不拥有该位图，不可回收。
                    var owned = false
                    val bmp = rawBmp?.takeIf { !it.isRecycled }?.let {
                        try {
                            owned = true
                            it.copy(it.config, false)
                        } catch (_: OutOfMemoryError) {
                            try {
                                owned = true
                                it.copy(Bitmap.Config.RGB_565, false)
                            } catch (_: OutOfMemoryError) {
                                owned = false
                                it
                            }
                        }
                    }
                    magnifierBitmap?.takeIf { !it.isRecycled && it !== rawBmp && it !== bmp }?.let {
                        runCatching { it.recycle() }
                    }
                    magnifierBitmap = bmp
                    magnifierBitmapOwned = owned
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
                            if (magnifierBitmapOwned) {
                                magnifierBitmap?.takeIf { !it.isRecycled }?.let { runCatching { it.recycle() } }
                            }
                            magnifierBitmap = null
                            magnifierBitmapOwned = false
                            magnifierCrop?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                            magnifierCrop = null
                        }
                    )
                }
            }
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
        // 防止异常位图或零尺寸导致除零/越界崩溃
        if (bmpW <= 0f || bmpH <= 0f || viewW <= 0f || viewH <= 0f) return null
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
                .border(3.dp, Color.White.copy(alpha = 0.4f), CircleShape)
        ) {
            Image(
                bitmap = imageBmp,
                contentDescription = stringResource(R.string.reader_magnifier),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun DualPageLayout(
    indices: List<Int>,
    totalPages: Int,
    loadPage: suspend (Int) -> Bitmap?,
    zoomMode: ZoomMode = ZoomMode.FIT_SCREEN,
    settingsVersion: Int = 0,
    spacingDp: Int = 0,
    pageShadow: Boolean = false,
    rtl: Boolean = false,
    brightnessOverlay: Float = 1f
) {
    if (indices.size < 2) {
        PageImage(
            index = indices.firstOrNull() ?: 0,
            totalPages = totalPages,
            loadPage = loadPage,
            zoomMode = zoomMode,
            fillMaxSize = true,
            settingsVersion = settingsVersion,
            brightnessOverlay = brightnessOverlay
        )
        return
    }

    // RTL 日漫双页：视觉顺序应为右页在前、左页在后
    val leftIndex = if (rtl) indices[1] else indices[0]
    val rightIndex = if (rtl) indices[0] else indices[1]
    val spacing = spacingDp.coerceIn(0, 64).dp

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopEnd
        ) {
            PageImage(
                index = leftIndex,
                totalPages = totalPages,
                loadPage = loadPage,
                zoomMode = zoomMode,
                fillMaxSize = true,
                imageAlignment = Alignment.TopEnd,
                settingsVersion = settingsVersion,
                brightnessOverlay = brightnessOverlay
            )
        }
        if (spacing > 0.dp) {
            // 双页间距 + 可选分页阴影，模拟书脊折痕并消除生硬黑缝
            Box(
                modifier = Modifier
                    .width(spacing)
                    .fillMaxHeight()
                    .background(
                        if (pageShadow) {
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.35f),
                                    Color.Black.copy(alpha = 0.05f),
                                    Color.Black.copy(alpha = 0.35f)
                                )
                            )
                        } else {
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Transparent
                                )
                            )
                        }
                    )
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopStart
        ) {
            PageImage(
                index = rightIndex,
                totalPages = totalPages,
                loadPage = loadPage,
                zoomMode = zoomMode,
                fillMaxSize = true,
                imageAlignment = Alignment.TopStart,
                settingsVersion = settingsVersion,
                brightnessOverlay = brightnessOverlay
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
    antiMistouch: Boolean = false,
    settingsVersion: Int = 0,
    loadPage: suspend (Int) -> Bitmap?,
    onTapMenu: () -> Unit,
    onPageChanged: (Int) -> Unit,
    brightnessOverlay: Float = 1f
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)

    LaunchedEffect(startPage) {
        if (startPage in 0 until totalPages) {
            try {
                listState.animateScrollToItem(startPage)
            } catch (_: Exception) {}
        }
    }

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
            .pointerInput(antiMistouch) {
                if (antiMistouch) {
                    detectTapGestures(
                        onDoubleTap = { onTapMenu() }
                    )
                } else {
                    detectTapGestures { tapOffset ->
                        val width = size.width.toFloat()
                        val x = tapOffset.x
                        if (x > width * 0.35f && x < width * 0.65f) {
                            onTapMenu()
                        }
                    }
                }
            },
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items(totalPages, key = { it }) { idx ->
            PageImage(
                index = idx,
                totalPages = totalPages,
                loadPage = loadPage,
                fillMaxSize = true,
                settingsVersion = settingsVersion,
                brightnessOverlay = brightnessOverlay
            )
        }
    }
}

@Composable
private fun HorizontalScrollViewer(
    totalPages: Int,
    startPage: Int,
    rtl: Boolean = false,
    antiMistouch: Boolean = false,
    settingsVersion: Int = 0,
    loadPage: suspend (Int) -> Bitmap?,
    onTapMenu: () -> Unit,
    onPageChanged: (Int) -> Unit,
    brightnessOverlay: Float = 1f
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)

    LaunchedEffect(startPage) {
        if (startPage in 0 until totalPages) {
            try {
                listState.animateScrollToItem(startPage)
            } catch (_: Exception) {}
        }
    }

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
            .pointerInput(rtl, antiMistouch) {
                if (antiMistouch) {
                    detectTapGestures(
                        onDoubleTap = { onTapMenu() }
                    )
                } else {
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
                }
            },
        contentPadding = PaddingValues(0.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items(totalPages, key = { it }) { idx ->
            PageImage(
                index = idx,
                totalPages = totalPages,
                loadPage = loadPage,
                fillMaxSize = true,
                settingsVersion = settingsVersion,
                brightnessOverlay = brightnessOverlay
            )
        }
    }
}

@Composable
private fun PageImage(
    index: Int,
    totalPages: Int,
    loadPage: suspend (Int) -> Bitmap?,
    fillMaxSize: Boolean = false,
    fillMaxWidth: Boolean = false,
    fillMaxHeight: Boolean = false,
    zoomMode: ZoomMode = ZoomMode.FIT_SCREEN,
    imageAlignment: Alignment = Alignment.Center,
    settingsVersion: Int = 0,
    brightnessOverlay: Float = 1f
) {
    var bitmap by remember(index, settingsVersion) { mutableStateOf<Bitmap?>(null) }
    var error by remember(index, settingsVersion) { mutableStateOf(false) }

    // 加载位图并复制一份独立副本给 UI 使用，避免缓存回收导致绘制时崩溃
    LaunchedEffect(index, settingsVersion) {
        // 回收旧副本
        bitmap?.takeIf { !it.isRecycled }?.let { runCatching { it.recycle() } }
        bitmap = null
        error = false

        repeat(3) { attempt ->
            try {
                val loaded = loadPage(index)
                if (loaded != null && !loaded.isRecycled) {
                    // 复制位图，让 UI 持有独立引用，缓存可自由回收。
                    // 优先复用原图配置降低内存峰值；若原配置复制也 OOM，再逐级降级。
                    val copy = try {
                        loaded.copy(loaded.config, false)
                    } catch (_: OutOfMemoryError) {
                        try {
                            loaded.copy(Bitmap.Config.RGB_565, false)
                        } catch (_: OutOfMemoryError) {
                            loaded // 极低内存时回退到缓存引用（仍可能崩溃，但好过直接失败）
                        }
                    }
                    bitmap = copy
                    return@LaunchedEffect
                }
            } catch (e: CancellationException) {
                // 组件离开组合或页面切换时取消，直接退出重试
                return@LaunchedEffect
            } catch (e: Exception) {
                Log.e(TAG, "加载页面图片失败", e)
            }
            if (attempt < 2) kotlinx.coroutines.delay(150)
        }
        error = true
    }

    // 组件销毁或 settingsVersion 变化时回收 UI 副本，释放内存
    DisposableEffect(index, settingsVersion) {
        onDispose {
            bitmap?.takeIf { !it.isRecycled }?.let { runCatching { it.recycle() } }
            bitmap = null
        }
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

    val currentBitmap = bitmap
    when {
        currentBitmap != null && !currentBitmap.isRecycled -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // 用 runCatching 兜底 asImageBitmap，防止位图在渲染瞬间被回收导致闪退
                val imageBitmap = runCatching { currentBitmap.asImageBitmap() }.getOrNull()
                if (imageBitmap != null && !currentBitmap.isRecycled) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = stringResource(R.string.page_content_description, index + 1, totalPages),
                        contentScale = contentScale,
                        alignment = imageAlignment,
                        colorFilter = if (brightnessOverlay == 1f) null else ColorFilter.colorMatrix(
                            ColorMatrix().apply { setToScale(brightnessOverlay, brightnessOverlay, brightnessOverlay, 1f) }
                        ),
                        modifier = when {
                            zoomMode == ZoomMode.FIT_WIDTH -> Modifier.fillMaxWidth().wrapContentHeight()
                            zoomMode == ZoomMode.FIT_HEIGHT -> Modifier.fillMaxHeight().wrapContentWidth()
                            else -> Modifier.fillMaxSize()
                        }
                    )
                } else {
                    Text(
                        text = stringResource(R.string.reader_loading),
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        error -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.reader_load_failed),
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
internal fun MenuOverlay(
    state: ReaderViewModel.ReaderUiState,
    viewModel: ReaderViewModel,
    bookmarks: List<Int>,
    onJumpToPage: (Int) -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    isLargeScreen: Boolean = false,
    isSharingSource: Boolean = false,
    onShareSource: () -> Unit = {}
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
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()

    // 菜单无操作 5 秒后自动隐藏；用户在菜单区域内任意触控时刷新计时
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastInteractionTime) {
        while (true) {
            val elapsed = System.currentTimeMillis() - lastInteractionTime
            val remaining = 5000L - elapsed
            if (remaining > 0) delay(remaining)
            if (System.currentTimeMillis() - lastInteractionTime >= 5000L) {
                onClose()
                break
            }
        }
    }

    LaunchedEffect(state.currentPage) {
        pageInput = viewModel.getPageInChapter().toString()
    }

    val scrimColor = readerScrimColor()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        lastInteractionTime = System.currentTimeMillis()
                    }
                }
            }
    ) {
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
                ReaderTopBar(
                    state = state,
                    viewModel = viewModel,
                    onOpenSettings = onOpenSettings,
                    onClose = onClose
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
                ReaderBottomMenu(
                    state = state,
                    viewModel = viewModel,
                    bookmarks = bookmarks,
                    onJumpDialog = { showJumpDialog = true },
                    onBookmarks = { showBookmarks = !showBookmarks },
                    onThumbnails = { showThumbnails = !showThumbnails },
                    onChapterList = { showChapterList = true },
                    onToggleStrip = { viewModel.toggleThumbnailStrip() },
                    isSharing = isSharing,
                    isSharingSource = isSharingSource,
                    isSpeaking = isSpeaking,
                    onShareSource = onShareSource,
                    onTts = { viewModel.toggleTts() },
                    onShareCard = {
                        if (isSharing) return@ReaderBottomMenu
                        isSharing = true
                        coroutineScope.launch {
                            val file = viewModel.createShareCard(state.currentPage)
                            isSharing = false
                            if (file != null) {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    context.packageName + Constants.FILE_PROVIDER_AUTHORITY_SUFFIX,
                                    file
                                )
                                val intent = android.content.Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_TEXT, context.getString(R.string.reader_share_card_text, context.getString(R.string.app_name)))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(intent, context.getString(R.string.reader_share_card_chooser))
                                    )
                                }.onFailure {
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.reader_share_card_failed_toast),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else {
                                android.widget.Toast.makeText(context, context.getString(R.string.reader_share_card_failed_toast), android.widget.Toast.LENGTH_SHORT).show()
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
                ReaderTopBar(
                    state = state,
                    viewModel = viewModel,
                    onOpenSettings = onOpenSettings,
                    onClose = onClose
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ReaderBottomMenu(
                    state = state,
                    viewModel = viewModel,
                    bookmarks = bookmarks,
                    onJumpDialog = { showJumpDialog = true },
                    onBookmarks = { showBookmarks = !showBookmarks },
                    onThumbnails = { showThumbnails = !showThumbnails },
                    onChapterList = { showChapterList = true },
                    onToggleStrip = { viewModel.toggleThumbnailStrip() },
                    isSharing = isSharing,
                    isSharingSource = isSharingSource,
                    isSpeaking = isSpeaking,
                    onShareSource = onShareSource,
                    onTts = { viewModel.toggleTts() },
                    onShareCard = {
                        if (isSharing) return@ReaderBottomMenu
                        isSharing = true
                        coroutineScope.launch {
                            val file = viewModel.createShareCard(state.currentPage)
                            isSharing = false
                            if (file != null) {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    context.packageName + Constants.FILE_PROVIDER_AUTHORITY_SUFFIX,
                                    file
                                )
                                val intent = android.content.Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_TEXT, context.getString(R.string.reader_share_card_text, context.getString(R.string.app_name)))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(intent, context.getString(R.string.reader_share_card_chooser))
                                    )
                                }.onFailure {
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.reader_share_card_failed_toast),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else {
                                android.widget.Toast.makeText(context, context.getString(R.string.reader_share_card_failed_toast), android.widget.Toast.LENGTH_SHORT).show()
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
            title = { Text(stringResource(R.string.reader_page_preview_title, state.totalPages)) },
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
                    items(state.totalPages, key = { it }) { idx ->
                        ThumbnailItem(
                            index = idx,
                            isCurrent = idx == state.currentPage,
                            loadPage = { viewModel.loadThumbnail(idx) },
                            onClick = {
                                onJumpToPage(idx + 1)
                                showThumbnails = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThumbnails = false }) { Text(stringResource(R.string.reader_close_label)) }
            }
        )
    }

    if (showBookmarks) {
        AlertDialog(
            onDismissRequest = { showBookmarks = false },
            title = { Text(stringResource(R.string.reader_bookmarks_title, bookmarks.size)) },
            text = {
                if (bookmarks.isEmpty()) {
                    Text(stringResource(R.string.reader_no_bookmarks), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    ) {
                        items(bookmarks.size, key = { bookmarks[it] }) { idx ->
                            val page = bookmarks[idx]
                            val chapterTitle = viewModel.getPageChapterTitle(page)
                            var thumb by remember(page) { mutableStateOf<Bitmap?>(null) }
                            LaunchedEffect(page) {
                                thumb = viewModel.loadThumbnail(page)
                            }
                            DisposableEffect(page) {
                                onDispose {
                                    thumb?.takeIf { !it.isRecycled }?.let { runCatching { it.recycle() } }
                                    thumb = null
                                }
                            }
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.reader_bookmark_page, page + 1)) },
                                supportingContent = {
                                    if (chapterTitle.isNotEmpty()) {
                                        Text(chapterTitle, style = MaterialTheme.typography.bodySmall)
                                    }
                                },
                                leadingContent = {
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        thumb?.let { b ->
                                            Image(
                                                bitmap = b.asImageBitmap(),
                                                contentDescription = stringResource(R.string.reader_bookmark_thumbnail),
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        } ?: Icon(
                                            imageVector = Icons.Default.Image,
                                            contentDescription = stringResource(R.string.reader_default_thumbnail),
                                            modifier = Modifier.size(28.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                trailingContent = {
                                    IconButton(onClick = { viewModel.removeBookmark(page) }) {
                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.reader_delete_bookmark))
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
                TextButton(onClick = { showBookmarks = false }) { Text(stringResource(R.string.reader_close_label)) }
            }
        )
    }

    if (showJumpDialog) {
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            title = { Text(stringResource(R.string.reader_jump_page_title)) },
            text = {
                Column {
                    if (state.currentChapter.isNotEmpty()) {
                        Text(stringResource(R.string.reader_current_chapter, state.currentChapter), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = { pageInput = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.reader_page_input_label, chapterPageCount)) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val targetInChapter = pageInput.toIntOrNull()?.coerceIn(1, chapterPageCount.coerceAtLeast(1)) ?: 1
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
                }) { Text(stringResource(R.string.reader_jump_label)) }
            },
            dismissButton = {
                TextButton(onClick = { showJumpDialog = false }) { Text(stringResource(R.string.reader_cancel_label)) }
            }
        )
    }

    if (showChapterList) {
        val chapters = viewModel.getChapterList()
        AlertDialog(
            onDismissRequest = { showChapterList = false },
            title = { Text(stringResource(R.string.reader_chapter_list_title)) },
            text = {
                if (chapters.isEmpty()) {
                    Text(stringResource(R.string.reader_no_chapters_detected), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                    ) {
                        items(
                            count = chapters.size,
                            key = { idx -> "${chapters[idx].first}-${chapters[idx].second}" }
                        ) { idx ->
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
                                    text = stringResource(R.string.reader_chapter_page_count, title, endIdx - startIdx + 1),
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChapterList = false }) { Text(stringResource(R.string.reader_close_label)) }
            }
        )
    }
    }
}

@Composable
private fun ReaderTopBar(
    state: ReaderViewModel.ReaderUiState,
    viewModel: ReaderViewModel,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit
) {
    val pageInChapter = viewModel.getPageInChapter()
    val chapterPageCount = viewModel.getChapterPageCount()
    val contentColor = readerOverlayContentColor()
    val mutedColor = readerOverlayMutedColor()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(readerOverlayBackground())
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.reader_back),
                tint = contentColor
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
                color = contentColor,
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
                color = mutedColor,
                style = MaterialTheme.typography.bodySmall
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.reader_settings), tint = contentColor)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderBottomMenu(
    state: ReaderViewModel.ReaderUiState,
    viewModel: ReaderViewModel,
    bookmarks: List<Int>,
    onJumpDialog: () -> Unit,
    onBookmarks: () -> Unit,
    onThumbnails: () -> Unit,
    onChapterList: () -> Unit,
    onToggleStrip: () -> Unit = {},
    isSharing: Boolean = false,
    isSharingSource: Boolean = false,
    isSpeaking: Boolean = false,
    onShareCard: () -> Unit = {},
    onShareSource: () -> Unit = {},
    onTts: () -> Unit = {}
) {
    val isBookmarked = bookmarks.contains(state.currentPage)
    val contentColor = readerOverlayContentColor()
    val mutedColor = readerOverlayMutedColor()
    var showMore by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(readerOverlayBackground())
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
                    Text(stringResource(R.string.reader_previous_chapter), color = mutedColor, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onChapterList) {
                    Text(stringResource(R.string.reader_chapter_list), color = mutedColor, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { viewModel.jumpToNextChapter() }) {
                    Text(stringResource(R.string.reader_next_chapter), color = mutedColor, style = MaterialTheme.typography.bodySmall)
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
                    contentDescription = stringResource(R.string.reader_previous_page),
                    tint = contentColor
                )
            }
            IconButton(onClick = onJumpDialog) {
                Icon(Icons.Default.Numbers, contentDescription = stringResource(R.string.reader_jump_page), tint = contentColor)
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
                    contentDescription = if (isBookmarked) stringResource(R.string.reader_bookmarked) else stringResource(R.string.reader_bookmark),
                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary else contentColor
                )
            }
            IconButton(onClick = onThumbnails) {
                Icon(Icons.Default.GridOn, contentDescription = stringResource(R.string.reader_page_preview), tint = contentColor)
            }
            IconButton(
                onClick = onShareCard,
                enabled = !isSharing
            ) {
                if (isSharing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = contentColor, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.reader_share_card), tint = contentColor)
                }
            }
            Box {
                IconButton(onClick = { showMore = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.reader_more_actions),
                        tint = contentColor
                    )
                }
                DropdownMenu(
                    expanded = showMore,
                    onDismissRequest = { showMore = false },
                    offset = DpOffset(0.dp, 0.dp),
                    modifier = Modifier.background(readerOverlayBackground())
                ) {
                    @Suppress("DEPRECATION")
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_toggle_thumbnail_strip)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.ViewList,
                                contentDescription = null,
                                tint = if (state.settings.thumbnailStripEnabled) MaterialTheme.colorScheme.primary else contentColor
                            )
                        },
                        onClick = {
                            showMore = false
                            onToggleStrip()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_share_source)) },
                        leadingIcon = {
                            if (isSharingSource) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = contentColor, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = contentColor)
                            }
                        },
                        enabled = !isSharingSource,
                        onClick = {
                            showMore = false
                            onShareSource()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isSpeaking) stringResource(R.string.reader_tts_stop) else stringResource(R.string.reader_tts_start)) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isSpeaking) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                contentDescription = null,
                                tint = if (isSpeaking) MaterialTheme.colorScheme.primary else contentColor
                            )
                        },
                        onClick = {
                            showMore = false
                            onTts()
                        }
                    )
                }
            }
            IconButton(onClick = { viewModel.nextPage() }) {
                Icon(
                    imageVector = if (state.settings.rtl) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.reader_next_page),
                    tint = contentColor
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            ) {
                previewBitmap?.let { bmp ->
                    if (!bmp.isRecycled) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.reader_page_preview),
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
                        text = stringResource(R.string.reader_page_count_format, previewPage + 1, totalPages),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

/**
 * 阅读器底部缩略图进度条：常驻显示当前分卷所有页面，点击跳转，当前页高亮。
 * 仅在页模式下作为快速进度参考；条带高度受限，避免遮挡主要内容。
 */
@Composable
private fun ThumbnailStrip(
    currentPage: Int,
    totalPages: Int,
    rtl: Boolean,
    loadPage: suspend (Int) -> Bitmap?,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val safePage = currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
    // RTL 切换时重建 LazyListState，避免滚动方向/对齐位置与当前布局方向不一致。
    val listState = remember(rtl, totalPages) { LazyListState(safePage) }
    LaunchedEffect(currentPage, totalPages, rtl) {
        if (totalPages > 0) {
            listState.animateScrollToItem(safePage)
        }
    }
    val layoutDirection = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection
    ) {
        LazyRow(
            state = listState,
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(72.dp)
                .background(readerOverlayBackground())
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(totalPages, key = { it }) { idx ->
                ThumbnailStripItem(
                    index = idx,
                    isCurrent = idx == safePage,
                    loadPage = loadPage,
                    onClick = { onPageSelected(idx) }
                )
            }
        }
    }
}

@Composable
private fun ThumbnailStripItem(
    index: Int,
    isCurrent: Boolean,
    loadPage: suspend (Int) -> Bitmap?,
    onClick: () -> Unit
) {
    // Lazy 的 items(key = { it }) 已保证每个 index 的组合边界；此处无需再手动按 index remember。
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index) {
        bitmap = loadPage(index)
    }
    DisposableEffect(index) {
        onDispose {
            bitmap?.takeIf { !it.isRecycled }?.let { runCatching { it.recycle() } }
            bitmap = null
        }
    }
    Box(
        modifier = Modifier
            .padding(2.dp)
        .width(42.dp)
        .height(60.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .border(
            2.dp,
            if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent,
            RoundedCornerShape(12.dp)
        )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.takeIf { !it.isRecycled }?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.reader_thumbnail, index + 1),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
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
    DisposableEffect(index) {
        onDispose {
            bitmap?.takeIf { !it.isRecycled }?.let { runCatching { it.recycle() } }
            bitmap = null
        }
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
                    contentDescription = stringResource(R.string.reader_thumbnail, index + 1),
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
        } else if (currentPanel != null) {
            val bmp = pageBitmap
            if (bmp != null && !bmp.isRecycled) {
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
                DisposableEffect(panelState.pageIndex, panelState.currentPanelIndex) {
                    onDispose {
                        cropped?.let { old -> if (!old.isRecycled && old !== pageBitmap) runCatching { old.recycle() } }
                        cropped = null
                    }
                }

                val safeCropped = cropped
                if (safeCropped != null && !safeCropped.isRecycled) {
                    val imageBitmap = runCatching { safeCropped.asImageBitmap() }.getOrNull()
                    if (imageBitmap != null && !safeCropped.isRecycled) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = stringResource(R.string.reader_panel_progress, panelState.currentPanelIndex + 1, panelState.panels.size),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        )
                    }
                }
            }

            // 分镜进度指示器
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .background(readerOverlayBackground(), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.reader_panel_progress, panelState.currentPanelIndex + 1, panelState.panels.size),
                    color = readerOverlayContentColor(),
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
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.reader_panel_exit), tint = Color.White)
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
                        contentDescription = stringResource(R.string.reader_previous_panel),
                        tint = Color.White
                    )
                }
            }
            Text(
                text = stringResource(R.string.reader_page_count_format, panelState.currentPanelIndex + 1, panelState.panels.size),
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
                        contentDescription = stringResource(R.string.reader_next_panel),
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
    val brightness = remember(state.brightnessOverride, state.settings.brightness) {
        if (state.brightnessOverride.isNaN()) state.settings.brightness else state.brightnessOverride
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    gestureConfig = state.settings.gestureConfig,
                    settingsVersion = state.settingsVersion,
                    loadPage = { index -> viewModel.loadPageBitmap(index) },
                    flipSoundEnabled = state.settings.flipSoundEnabled,
                    flipVibrationEnabled = state.settings.flipVibrationEnabled,
                    onTapNext = { viewModel.nextPage() },
                    onTapPrev = { viewModel.previousPage() },
                    onTapMenu = { viewModel.toggleMenu() },
                    onPinchZoom = onPinchZoom,
                    onZoomTo = onZoomTo,
                    onPan = onPan,
                    onAction = { action ->
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
                    },
                    scale = scale,
                    offset = offset,
                    brightnessOverlay = brightness
                )
                ScrollMode.VERTICAL -> VerticalScrollViewer(
                    totalPages = state.totalPages,
                    startPage = state.currentPage,
                    rtl = state.settings.rtl,
                    antiMistouch = state.settings.antiMistouch,
                    settingsVersion = state.settingsVersion,
                    loadPage = { index -> viewModel.loadPageBitmap(index) },
                    onTapMenu = { viewModel.toggleMenu() },
                    onPageChanged = { idx -> viewModel.setCurrentPage(idx) },
                    brightnessOverlay = brightness
                )
                ScrollMode.HORIZONTAL -> HorizontalScrollViewer(
                    totalPages = state.totalPages,
                    startPage = state.currentPage,
                    rtl = state.settings.rtl,
                    antiMistouch = state.settings.antiMistouch,
                    settingsVersion = state.settingsVersion,
                    loadPage = { index -> viewModel.loadPageBitmap(index) },
                    onTapMenu = { viewModel.toggleMenu() },
                    onPageChanged = { idx -> viewModel.setCurrentPage(idx) },
                    brightnessOverlay = brightness
                )
            }
        }

        if (state.settings.scrollMode == ScrollMode.PAGE && !showMenu && !panelState.isActive) {
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

        if (state.settings.readerStatusBar && !showMenu && !panelState.isActive) {
            ReaderStatusBar(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        if (state.settings.thumbnailStripEnabled && state.settings.scrollMode == ScrollMode.PAGE && !showMenu && !panelState.isActive) {
            ThumbnailStrip(
                currentPage = state.currentPage,
                totalPages = state.totalPages,
                rtl = state.settings.rtl,
                loadPage = { index -> viewModel.loadThumbnail(index) },
                onPageSelected = { idx -> viewModel.setCurrentPage(idx) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * 绘制阅读背景纹理。使用确定性伪随机图案，避免重组时闪烁。
 */
internal fun DrawScope.drawBackground(baseColor: Color, texture: BackgroundTexture) {
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
