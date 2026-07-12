package com.mangareader.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Returns one of three size buckets so the UI can lay out differently on
 * phones vs large foldables / tablets.
 */
enum class WindowSizeClass {
    PHONE_PORTRAIT,   // 窄边
    PHONE_LANDSCAPE,  // 宽边手机
    TABLET            // sw >= 600dp
}

@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    val configuration = LocalConfiguration.current
    val width: Dp = configuration.screenWidthDp.dp
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    return when {
        width >= 600.dp -> WindowSizeClass.TABLET
        isLandscape -> WindowSizeClass.PHONE_LANDSCAPE
        else -> WindowSizeClass.PHONE_PORTRAIT
    }
}
