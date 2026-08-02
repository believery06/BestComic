package com.mangareader.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 封面/缩略图加载时的闪烁占位效果。
 * 基于 surface 与 surfaceVariant 的渐变扫光，不依赖具体形状，
 * 调用方自行裁剪（clip）即可适配圆角封面。
 */
fun Modifier.shimmerPlaceholder(
    enabled: Boolean = true,
    baseColor: Color = Color.Unspecified,
    highlightColor: Color = Color.Unspecified
): Modifier = composed {
    if (!enabled) return@composed this

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    ).value

    val base = baseColor.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val highlight = highlightColor.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    background(
        brush = Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(-200f + translate * 600f, 0f),
            end = Offset(0f + translate * 600f, 200f)
        )
    )
}
