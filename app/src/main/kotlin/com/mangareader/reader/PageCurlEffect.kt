package com.mangareader.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

@Composable
fun PageCurlView(
    currentBitmap: Bitmap?,
    nextBitmap: Bitmap?,
    direction: Int,
    triggerKey: Long,
    onFlipComplete: () -> Unit,
    onFlipCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var targetProgress by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var isAnimating by remember { mutableStateOf(false) }
    var flipDirection by remember { mutableIntStateOf(direction) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 350),
        finishedListener = { value ->
            isAnimating = false
            if (!isDragging && abs(value) >= 0.99f) {
                onFlipComplete()
                targetProgress = 0f
            }
        },
        label = "curlProgress"
    )

    val displayProgress = when {
        isDragging -> dragProgress
        else -> animatedProgress * flipDirection
    }

    LaunchedEffect(triggerKey) {
        if (triggerKey > 0 && !isDragging) {
            flipDirection = direction
            isAnimating = true
            targetProgress = 1f
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(triggerKey) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        if (!isAnimating) {
                            isDragging = true
                            dragProgress = 0f
                            flipDirection = direction
                        }
                    },
                    onDragEnd = {
                        if (isDragging) {
                            isDragging = false
                            isAnimating = true
                            flipDirection = if (dragProgress > 0) 1 else -1
                            targetProgress = if (abs(dragProgress) > 0.3f) 1f else 0f
                            if (abs(dragProgress) <= 0.3f) onFlipCancel()
                        }
                    },
                    onDragCancel = {
                        if (isDragging) {
                            isDragging = false
                            isAnimating = true
                            targetProgress = 0f
                            onFlipCancel()
                        }
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        if (isDragging) {
                            dragProgress = (dragProgress + dragAmount / size.width)
                                .coerceIn(-1f, 1f)
                        }
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val p = displayProgress
        val absP = abs(p)

        if (nextBitmap != null && !nextBitmap.isRecycled && absP > 0.01f) {
            drawImage(image = nextBitmap.asImageBitmap())
        }

        if (currentBitmap != null && !currentBitmap.isRecycled && absP < 0.99f) {
            val foldX = if (p > 0) w * (1f - absP) else w * absP
            val shadowWidth = (w * 0.12f).coerceAtLeast(15f)

            clipRect(
                left = if (p > 0) 0f else foldX + shadowWidth,
                top = 0f,
                right = if (p > 0) foldX + shadowWidth else w,
                bottom = h
            ) {
                drawImage(image = currentBitmap.asImageBitmap())
            }

            val shadowAlpha = (absP * 0.5f).coerceAtMost(0.5f)
            val nativeCanvas = drawContext.canvas.nativeCanvas

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
            nativeCanvas.drawRect(
                if (p > 0) foldX else foldX,
                0f,
                if (p > 0) foldX + shadowWidth else foldX + shadowWidth,
                h,
                shadowPaint
            )

            val spinePaint = Paint().apply {
                isAntiAlias = true
                color = Color.argb((absP * 35).toInt(), 255, 255, 255)
                strokeWidth = 2f
            }
            nativeCanvas.drawLine(foldX, 0f, foldX, h, spinePaint)
        }
    }
}
