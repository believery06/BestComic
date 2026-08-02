package com.mangareader.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

/**
 * 统一的封面异步图片组件。
 *
 * - 加载时显示与主题协调的 shimmer 占位
 * - 加载失败或数据为空时显示透明（保留背景上的默认图标）
 * - 使用 crossfade 渐显，并自动写入 memory/disk 缓存键
 */
@Composable
fun CoverAsyncImage(
    uri: Uri?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable (() -> Unit)? = null
) {
    val context = LocalContext.current
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val key = uri?.toString()
        if (uri != null && key != null) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(uri)
                    .crossfade(true)
                    .memoryCacheKey(key)
                    .diskCacheKey(key)
                    .build(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize(),
                loading = {
                    placeholder?.invoke() ?: Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(MaterialTheme.shapes.small)
                            .shimmerPlaceholder()
                    )
                },
                error = {
                    // 失败时保持背景透明，由调用方提供的默认图标兜底
                }
            )
        }
    }
}
