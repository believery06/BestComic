package com.mangareader.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 3D翻页动画 - 模拟实体书翻页效果
 * 支持拖拽翻页和自动回弹
 */
@Composable
fun PageCurlView(
    currentBitmap: Bitmap?,
    nextBitmap: Bitmap?,
    isForward: Boolean, // true=向前翻, false=向后翻
    triggerFlip: Boolean, // 外部触发翻页
    onFlipComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf(0f) }
    var animTarget by remember { mutableStateOf(0f) }
    var isAnimating by remember { mutableStateOf(false) }
    val animatedProgress = animateFloatAsState(
        targetValue = animTarget,
        animationSpec = tween(if (isAnimating) 300 else 0),
        label = "pageCurl"
    )
    val progress = if (isDragging) dragProgress else animatedProgress.value

    // 翻页完成回调
    LaunchedEffect(progress) {
        if (!isDragging && !isAnimating && (progress >= 0.99f || progress <= -0.99f)) {
            onFlipComplete()
            animTarget = 0f
            dragProgress = 0f
        }
    }

    // 响应外部触发
    LaunchedEffect(triggerFlip) {
        if (triggerFlip && !isDragging) {
            isAnimating = true
            animTarget = if (isForward) 1f else -1f
            delay(400)
            isAnimating = false
            animTarget = 0f
            dragProgress = 0f
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        isDragging = true
                        dragProgress = 0f
                    },
                    onDragEnd = {
                        isDragging = false
                        isAnimating = true
                        if (abs(dragProgress) > 0.3f) {
                            // 完成翻页
                            animTarget = if (dragProgress > 0) 1f else -1f
                        } else {
                            // 回弹
                            animTarget = 0f
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        isAnimating = true
                        animTarget = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        dragProgress = (dragProgress + dragAmount / size.width)
                            .coerceIn(-1f, 1f)
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val p = progress
        val absP = abs(p)

        // 绘制下一页（翻页时露出的部分）
        if (nextBitmap != null && !nextBitmap.isRecycled && absP > 0.01f) {
            drawImage(
                image = nextBitmap.asImageBitmap()
            )
        }

        // 绘制当前页的翻页效果
        if (currentBitmap != null && !currentBitmap.isRecycled && absP < 0.99f) {
            val foldX = when {
                p > 0 -> w * (1f - absP) // 向前翻：从右往左
                else -> w * absP           // 向后翻：从左往右
            }

            // 阴影宽度
            val shadowWidth = (w * 0.1f).coerceAtLeast(10f)

            clipRect(
                left = if (p > 0) 0f else foldX + shadowWidth,
                top = 0f,
                right = if (p > 0) foldX + shadowWidth else w,
                bottom = h
            ) {
                drawImage(
                    image = currentBitmap.asImageBitmap()
                )
            }

            // 绘制翻页阴影
            val shadowAlpha = (absP * 0.6f).coerceAtMost(0.6f)
            val canvas = drawContext.canvas.nativeCanvas

            // 折叠线渐变阴影
            val shadowPaint = Paint().apply {
                isAntiAlias = true
                val gradientColors = intArrayOf(
                    Color.argb((shadowAlpha * 255).toInt(), 0, 0, 0),
                    Color.argb(0, 0, 0, 0)
                )
                val gradientPositions = floatArrayOf(0f, 1f)
                shader = LinearGradient(
                    if (p > 0) foldX else foldX + shadowWidth,
                    0f,
                    if (p > 0) foldX + shadowWidth else foldX,
                    0f,
                    gradientColors,
                    gradientPositions,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(
                if (p > 0) foldX else foldX,
                0f,
                if (p > 0) foldX + shadowWidth else foldX + shadowWidth,
                h,
                shadowPaint
            )

            // 书脊微光
            val spinePaint = Paint().apply {
                isAntiAlias = true
                color = Color.argb((absP * 40).toInt(), 255, 255, 255)
                strokeWidth = 2f
            }
            canvas.drawLine(foldX, 0f, foldX, h, spinePaint)
        }
    }
}