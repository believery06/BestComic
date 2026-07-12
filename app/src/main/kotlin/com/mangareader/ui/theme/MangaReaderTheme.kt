package com.mangareader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mangareader.data.AppTheme

/**
 * 应用级 Material3 主题。
 * 根据 [AppTheme] 预设生成对应的 ColorScheme，保持整体风格统一。
 */
@Composable
fun MangaReaderTheme(
    theme: AppTheme = AppTheme.INK,
    content: @Composable () -> Unit
) {
    val isDark = theme.isDark || (theme == AppTheme.INK && isSystemInDarkTheme())
    val seed = Color(theme.seedColor)
    val surface = if (isDark) Color(0xFF121212) else Color(0xFFF5F5F5)
    val onSurface = if (isDark) Color(0xFFE0E0E0) else Color(0xFF1C1B1F)
    val surfaceVariant = if (isDark) Color(0xFF1E1E1E) else Color(0xFFE0E0E0)

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = seed,
            onPrimary = Color.White,
            secondary = seed.copy(alpha = 0.8f),
            onSecondary = Color.White,
            background = surface,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurface.copy(alpha = 0.7f)
        )
    } else {
        lightColorScheme(
            primary = seed,
            onPrimary = Color.White,
            secondary = seed.copy(alpha = 0.8f),
            onSecondary = Color.White,
            background = surface,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurface.copy(alpha = 0.7f)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
