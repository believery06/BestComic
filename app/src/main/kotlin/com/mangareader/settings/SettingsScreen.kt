package com.mangareader.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangareader.Constants
import com.mangareader.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 独立设置页：按功能分组，把阅读器菜单中杂乱的配置迁移到这里，
 * 让阅读界面保持「内容优先，UI 隐形」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    repository: SettingsRepository? = null
) {
    val context = LocalContext.current
    val repo = repository ?: remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val settings by repo.readerSettings.collectAsStateWithLifecycle(initialValue = ReaderSettings())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── 阅读设置 ──
            SettingsGroup(title = "阅读设置") {
                EnumSelector(
                    label = "翻页方式",
                    description = "页模式适合普通漫画，垂直/水平滚动适合条漫",
                    options = ScrollMode.entries.map { it.chineseName() to it },
                    selected = settings.scrollMode,
                    onSelect = { value -> repo.saveAsync(scope) { it.copy(scrollMode = value) } }
                )
                SwitchItem(
                    label = "从右向左翻页（日漫模式）",
                    description = "日漫默认从右向左阅读",
                    checked = settings.rtl,
                    onCheckedChange = { repo.saveAsync(scope) { s -> s.copy(rtl = it) } }
                )
                SwitchItem(
                    label = "双页模式",
                    description = "横屏时并排显示两页",
                    checked = settings.dualPage,
                    onCheckedChange = { repo.saveAsync(scope) { s -> s.copy(dualPage = it) } }
                )
                if (settings.dualPage) {
                    SwitchItem(
                        label = "双页 1+2 起始",
                        description = "关闭时封面单独显示一页",
                        checked = settings.dualPageStartOne,
                        onCheckedChange = { repo.saveAsync(scope) { s -> s.copy(dualPageStartOne = it) } }
                    )
                }
                SwitchItem(
                    label = "音量键翻页",
                    description = "音量上=上一页，音量下=下一页",
                    checked = settings.volumeKeyNav,
                    onCheckedChange = { repo.saveAsync(scope) { s -> s.copy(volumeKeyNav = it) } }
                )
                EnumSelector(
                    label = "翻页动画",
                    description = "推荐「滑动」或「淡入淡出」，「翻页」更耗电",
                    options = PageAnimation.entries.map { it.chineseName() to it },
                    selected = settings.pageAnimation,
                    onSelect = { value -> repo.saveAsync(scope) { it.copy(pageAnimation = value, randomAnimation = false) } }
                )
                EnumSelector(
                    label = "点击翻页区域大小",
                    description = "小区域：中间菜单区更大；大区域：翻页热区更大",
                    options = TapZoneSize.entries.map { it.chineseName() to it },
                    selected = settings.tapZoneSize,
                    onSelect = { value -> repo.saveAsync(scope) { it.copy(tapZoneSize = value) } }
                )
            }

            // ── 图像设置 ──
            SettingsGroup(title = "图像设置") {
                EnumSelector(
                    label = "缩放模式",
                    description = "自由缩放支持双指捏合",
                    options = ZoomMode.entries.map { it.chineseName() to it },
                    selected = settings.zoomMode,
                    onSelect = { value -> repo.saveAsync(scope) { it.copy(zoomMode = value) } }
                )
                EnumSelector(
                    label = "缩放算法",
                    description = "高质量锐利缩放画质最好但更耗电，双线性缩放速度最快",
                    options = ScaleFilter.entries.map { it.chineseName() to it },
                    selected = settings.scaleFilter,
                    onSelect = { value -> repo.saveAsync(scope) { it.copy(scaleFilter = value) } }
                )
                SwitchItem(
                    label = "自动裁白边",
                    description = "自动识别并切除扫描版漫画的白边",
                    checked = settings.autoCrop,
                    onCheckedChange = { repo.saveAsync(scope) { s -> s.copy(autoCrop = it) } }
                )
                SwitchItem(
                    label = "灰度",
                    description = "将画面转为黑白",
                    checked = settings.grayscale,
                    onCheckedChange = { repo.saveAsync(scope) { s -> s.copy(grayscale = it) } }
                )
                SwitchItem(
                    label = "锐化",
                    description = "增强线条边缘",
                    checked = settings.sharpen,
                    onCheckedChange = { repo.saveAsync(scope) { s -> s.copy(sharpen = it) } }
                )
                SwitchItem(
                    label = "降噪",
                    description = "减少扫描颗粒感",
                    checked = settings.denoise,
                    onCheckedChange = { repo.saveAsync(scope) { s -> s.copy(denoise = it) } }
                )
                SwitchItem(
                    label = "镜像",
                    description = "水平翻转画面",
                    checked = settings.mirror,
                    onCheckedChange = { repo.saveAsync(scope) { s -> s.copy(mirror = it) } }
                )
                SwitchItem(
                    label = "夜间模式",
                    description = "降低画面亮度，适合暗光环境阅读",
                    checked = settings.nightMode,
                    onCheckedChange = { repo.saveAsync(scope) { s -> s.copy(nightMode = it) } }
                )
                SwitchItem(
                    label = "护眼模式",
                    description = "减少蓝光，使用更柔和的暖色画面",
                    checked = settings.eyeCare,
                    onCheckedChange = { repo.saveAsync(scope) { s -> s.copy(eyeCare = it) } }
                )
                ListItem(
                    headlineContent = { Text("旋转画面") },
                    supportingContent = { Text("每次顺时针旋转 90°", style = MaterialTheme.typography.bodySmall) },
                    trailingContent = {
                        Text("${settings.rotation}°")
                    },
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        repo.saveAsync(scope) { s -> s.copy(rotation = (s.rotation + 90) % 360) }
                    }
                )
                SliderItem(
                    label = "亮度",
                    value = settings.brightness,
                    range = 0.5f..2f,
                    onValueChange = { repo.saveAsync(scope) { s -> s.copy(brightness = it) } }
                )
                SliderItem(
                    label = "对比度",
                    value = settings.contrast,
                    range = 0.5f..2f,
                    onValueChange = { repo.saveAsync(scope) { s -> s.copy(contrast = it) } }
                )
                SliderItem(
                    label = "伽马",
                    value = settings.gamma,
                    range = 0.5f..2.5f,
                    onValueChange = { repo.saveAsync(scope) { s -> s.copy(gamma = it) } }
                )
                SliderItem(
                    label = "饱和度",
                    value = settings.saturation,
                    range = 0f..2f,
                    onValueChange = { repo.saveAsync(scope) { s -> s.copy(saturation = it) } }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("重置所有滤镜") },
                    supportingContent = { Text("恢复亮度、颜色、旋转、裁边及全部图像效果", style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                    modifier = Modifier.clickable {
                        repo.saveAsync(scope) { it.resetImageFilters() }
                        Toast.makeText(context, "所有滤镜已重置", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // ── 手势与按键 ──
            SettingsGroup(title = "手势与按键") {
                SwitchItem(
                    label = "启用缩放",
                    description = "双指缩放与双击缩放",
                    checked = settings.enableZoom,
                    onCheckedChange = { repo.saveAsync(scope) { s -> s.copy(enableZoom = it) } }
                )
                SwitchItem(
                    label = "放大镜",
                    description = "长按屏幕局部放大",
                    checked = settings.magnifierEnabled,
                    onCheckedChange = { repo.saveAsync(scope) { s -> s.copy(magnifierEnabled = it) } }
                )
            }

            // ── 外观主题 ──
            SettingsGroup(title = "外观主题") {
                Text(
                    "主题皮肤",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                AppTheme.entries.forEach { theme ->
                    ListItem(
                        headlineContent = { Text(theme.displayName) },
                        leadingContent = {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = androidx.compose.ui.graphics.Color(theme.seedColor),
                                modifier = Modifier.size(28.dp)
                            ) {}
                        },
                        trailingContent = {
                            if (settings.appTheme == theme) {
                                Icon(Icons.Default.Check, contentDescription = null)
                            }
                        },
                        modifier = Modifier.clickable {
                            repo.saveAsync(scope) { s ->
                                s.copy(
                                    appTheme = theme,
                                    backgroundColor = theme.readerBackground,
                                    backgroundTexture = theme.readerTexture
                                )
                            }
                        }
                    )
                }
            }

            // ── 书库设置 ──
            SettingsGroup(title = "书库设置") {
                val cacheDir = context.cacheDir
                var cacheSize by remember { mutableStateOf(formatSize(calcCacheSize(cacheDir))) }
                ListItem(
                    headlineContent = { Text("清除缓存") },
                    supportingContent = { Text("当前缓存：$cacheSize") },
                    modifier = Modifier.clickable {
                        scope.launch(Dispatchers.IO) {
                            clearCache(cacheDir)
                            cacheSize = formatSize(calcCacheSize(cacheDir))
                            Toast.makeText(context, "缓存已清除", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            // ── 关于 ──
            SettingsGroup(title = "关于") {
                ListItem(
                    headlineContent = { Text("版本") },
                    supportingContent = { Text(tryGetVersion(context)) }
                )
                ListItem(
                    headlineContent = { Text("联系作者") },
                    supportingContent = { Text(Constants.AUTHOR_CONTACT) },
                    modifier = Modifier.clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("作者联系方式", Constants.AUTHOR_CONTACT))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                )
                ListItem(
                    headlineContent = { Text("开源声明") },
                    supportingContent = { Text("本项目使用 Jetpack Compose、Coil、junrar 等开源库") },
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com"))
                        runCatching { context.startActivity(intent) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun SwitchItem(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
}

@Composable
private fun <T> EnumSelector(
    label: String,
    description: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            TextButton(onClick = { expanded = true }) {
                Text(options.find { it.second == selected }?.first ?: "")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (name, value) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        },
                        trailingIcon = {
                            if (value == selected) {
                                Icon(Icons.Default.Check, contentDescription = null)
                            }
                        }
                    )
                }
            }
        },
        modifier = Modifier.clickable { expanded = true }
    )
}

@Composable
private fun SliderItem(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun SettingsRepository.saveAsync(
    scope: kotlinx.coroutines.CoroutineScope,
    transform: (ReaderSettings) -> ReaderSettings
) {
    scope.launch(Dispatchers.IO) {
        val current = readerSettings.first()
        saveReaderSettings(transform(current))
    }
}

private fun ReaderSettings.resetImageFilters(): ReaderSettings = copy(
    autoCrop = false,
    brightness = 1f,
    contrast = 1f,
    rotation = 0,
    grayscale = false,
    sharpen = false,
    denoise = false,
    mirror = false,
    gamma = 1f,
    saturation = 1f,
    nightMode = false,
    eyeCare = false,
    sharpenStrength = 1f,
    denoiseStrength = 1f
)

private fun ScrollMode.chineseName(): String = when (this) {
    ScrollMode.PAGE -> "单页或双页"
    ScrollMode.VERTICAL -> "垂直滚动"
    ScrollMode.HORIZONTAL -> "水平滚动"
}

private fun ZoomMode.chineseName(): String = when (this) {
    ZoomMode.FREE -> "自由缩放"
    ZoomMode.FIT_WIDTH -> "适合宽度"
    ZoomMode.FIT_HEIGHT -> "适合高度"
    ZoomMode.FIT_SCREEN -> "适合屏幕"
}

private fun PageAnimation.chineseName(): String = when (this) {
    PageAnimation.NONE -> "无动画"
    PageAnimation.SLIDE -> "滑动"
    PageAnimation.CURL -> "仿真翻页"
    PageAnimation.FADE -> "淡入淡出"
    PageAnimation.PIXEL -> "像素渐变"
    PageAnimation.BLINDS -> "百叶窗"
    PageAnimation.SHATTER -> "碎裂"
    PageAnimation.DOTS -> "漫画网点"
}

private fun TapZoneSize.chineseName(): String = when (this) {
    TapZoneSize.SMALL -> "小"
    TapZoneSize.MEDIUM -> "中"
    TapZoneSize.LARGE -> "大"
}

private fun ScaleFilter.chineseName(): String = when (this) {
    ScaleFilter.BILINEAR -> "双线性（快速）"
    ScaleFilter.BICUBIC -> "双三次（平滑）"
    ScaleFilter.LANCZOS3 -> "高质量锐利缩放"
}

private fun calcCacheSize(dir: java.io.File): Long {
    return dir.walkBottomUp().filter { it.isFile }.map { it.length() }.sum()
}

private fun clearCache(dir: java.io.File) {
    dir.listFiles()?.forEach { child ->
        if (child.isDirectory) child.deleteRecursively() else child.delete()
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024 * 1024f))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }
}

private fun tryGetVersion(context: Context): String {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
        }
    }.getOrDefault("未知")
}
