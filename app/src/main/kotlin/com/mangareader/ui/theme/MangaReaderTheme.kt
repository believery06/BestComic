package com.mangareader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.mangareader.data.AppTheme
import com.mangareader.data.ThemeAutoMode
import java.time.LocalDateTime
import kotlinx.coroutines.delay

/**
 * 应用级 Material3 主题。
 * 根据 [AppTheme] 预设生成对应的 ColorScheme，保持整体风格统一。
 * 支持 [ThemeAutoMode] 自动切换日/夜配色。
 */
@Composable
fun MangaReaderTheme(
    theme: AppTheme = AppTheme.INK,
    customSeedColor: String? = null,
    themeAutoMode: ThemeAutoMode = ThemeAutoMode.OFF,
    content: @Composable () -> Unit
) {
    var timeTick by remember { mutableIntStateOf(0) }
    // 仅定时切换模式需要每分钟刷新，其他模式避免空转
    if (themeAutoMode == ThemeAutoMode.SCHEDULE) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(60_000L)
                timeTick++
            }
        }
    }
    val isSystemDark = isSystemInDarkTheme()
    val isNightScheduled = remember(timeTick) {
        val hour = LocalDateTime.now().hour
        hour >= 22 || hour < 6
    }
    val isDark = when (themeAutoMode) {
        ThemeAutoMode.FOLLOW_SYSTEM -> isSystemDark
        ThemeAutoMode.SCHEDULE -> isNightScheduled
        else -> theme.isDark
    }
    val seed = if (theme == AppTheme.CUSTOM && !customSeedColor.isNullOrBlank()) {
        try {
            Color(android.graphics.Color.parseColor(customSeedColor))
        } catch (_: IllegalArgumentException) {
            Color(theme.seedColor)
        }
    } else {
        Color(theme.seedColor)
    }
    val surface = if (isDark) Color(0xFF121212) else Color(0xFFF5F5F5)
    val onSurface = if (isDark) Color(0xFFE0E0E0) else Color(0xFF1C1B1F)
    val surfaceVariant = if (isDark) Color(0xFF1E1E1E) else Color(0xFFE0E0E0)

    val error = if (isDark) Color(0xFFCF6679) else Color(0xFFB3261E)
    val onError = if (isDark) Color.Black else Color.White
    val outline = onSurface.copy(alpha = 0.2f)
    val scrim = Color.Black.copy(alpha = 0.6f)
    val surfaceTint = seed.copy(alpha = 0.05f)

    // 基于主色与界面底色推导缺失的 Material3 色槽，避免组件回退到默认配色。
    val primaryContainer = seed.copy(alpha = if (isDark) 0.22f else 0.14f)
    val onPrimaryContainer = if (isDark) Color.White else Color.Black
    val secondaryContainer = seed.copy(alpha = if (isDark) 0.15f else 0.10f)
    val onSecondaryContainer = onSurface
    val tertiary = seed.copy(alpha = 0.7f)
    val onTertiary = Color.White
    val tertiaryContainer = seed.copy(alpha = if (isDark) 0.12f else 0.08f)
    val onTertiaryContainer = onSurface
    val errorContainer = error.copy(alpha = 0.2f)
    val onErrorContainer = onError
    val surfaceBright = if (isDark) surface else surfaceVariant
    val surfaceDim = if (isDark) surfaceVariant else surface
    val surfaceContainerLowest = if (isDark) Color(0xFF0C0C0C) else Color(0xFFFFFFFF)
    val surfaceContainerLow = if (isDark) Color(0xFF141414) else Color(0xFFF0F0F0)
    val surfaceContainer = if (isDark) Color(0xFF181818) else Color(0xFFEBEBEB)
    val surfaceContainerHigh = if (isDark) Color(0xFF222222) else Color(0xFFE6E6E6)
    val surfaceContainerHighest = if (isDark) Color(0xFF2C2C2C) else Color(0xFFE0E0E0)

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = seed,
            onPrimary = Color.White,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = seed.copy(alpha = 0.8f),
            onSecondary = Color.White,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = surface,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurface.copy(alpha = 0.7f),
            surfaceTint = surfaceTint,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = surfaceContainer,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = onSurface.copy(alpha = 0.1f),
            scrim = scrim,
            inverseSurface = surfaceVariant,
            inverseOnSurface = onSurface,
            inversePrimary = seed.copy(alpha = 0.8f)
        )
    } else {
        lightColorScheme(
            primary = seed,
            onPrimary = Color.White,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = seed.copy(alpha = 0.8f),
            onSecondary = Color.White,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = surface,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurface.copy(alpha = 0.7f),
            surfaceTint = surfaceTint,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = surfaceContainer,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = onSurface.copy(alpha = 0.1f),
            scrim = scrim,
            inverseSurface = surfaceVariant,
            inverseOnSurface = onSurface,
            inversePrimary = seed.copy(alpha = 0.8f)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
