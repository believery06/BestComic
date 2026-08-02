package com.mangareader.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangareader.Constants
import com.mangareader.R
import com.mangareader.data.*
import com.mangareader.utils.CrashHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 独立设置页：按功能分组，把阅读器菜单中杂乱的配置迁移到这里，
 * 让阅读界面保持「内容优先，UI 隐形」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCacheManagement: () -> Unit = {},
    onOpenStatistics: () -> Unit = {},
    onOpenGestureSettings: () -> Unit = {},
    repository: SettingsRepository? = null
) {
    val context = LocalContext.current
    val repo = repository ?: remember { SettingsRepository.getInstance(context) }
    val scope = rememberCoroutineScope()
    val settings by repo.readerSettings.collectAsStateWithLifecycle(initialValue = ReaderSettings())
    val readingGoals by repo.getReadingGoals().collectAsStateWithLifecycle(initialValue = com.mangareader.data.ReadingGoals())

    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val json = repo.exportAllData()
                    val output = context.contentResolver.openOutputStream(it)
                    if (output == null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.settings_backup_failed_toast, "无法写入目标位置"), Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.settings_backup_saved_toast), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("SettingsScreen", "备份失败", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.settings_backup_failed_toast, e.message ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    val openBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val maxBytes = 50 * 1024 * 1024L // 备份文件上限 50MB
                    val size = context.contentResolver.openFileDescriptor(it, "r")?.use { fd -> fd.statSize } ?: 0L
                    if (size > maxBytes) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_restore_too_large_toast, maxBytes / 1024 / 1024),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return@launch
                    }
                    val json = context.contentResolver.openInputStream(it)?.use { input ->
                        input.bufferedReader(Charsets.UTF_8).readText()
                    } ?: ""
                    if (json.isBlank()) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                R.string.settings_restore_failed_parse_toast,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        return@launch
                    }
                    val success = repo.importAllData(json, overwrite = false)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            if (success) context.getString(R.string.settings_restore_success_toast) else context.getString(R.string.settings_restore_failed_parse_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("SettingsScreen", "恢复失败", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.settings_restore_failed_toast, e.message ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
            SettingsGroup(title = stringResource(R.string.settings_group_reading)) {
                EnumSelector(
                    label = stringResource(R.string.settings_scroll_mode),
                    description = stringResource(R.string.settings_scroll_mode_desc),
                    options = ScrollMode.entries.map { it.chineseName(context) to it },
                    selected = settings.scrollMode,
                    onSelect = { value -> repo.saveAsync(settings.copy(scrollMode = value)) }
                )
                SwitchItem(
                    label = stringResource(R.string.settings_rtl),
                    description = stringResource(R.string.settings_rtl_desc),
                    checked = settings.rtl,
                    onCheckedChange = { value ->
                        // 切换 RTL 时同步更新手势预设，避免"正常模式向右翻页页数减少"的问题
                        val newGesture = when {
                            value -> GestureConfig.PRESET_MANGA
                            settings.gestureConfig.presetKey() == "simple" -> GestureConfig.PRESET_SIMPLE
                            else -> GestureConfig.PRESET_COMIC
                        }
                        repo.saveAsync(settings.copy(rtl = value, gestureConfig = newGesture))
                    }
                )
                SwitchItem(
                    label = stringResource(R.string.settings_dual_page),
                    description = stringResource(R.string.settings_dual_page_desc),
                    checked = settings.dualPage,
                    onCheckedChange = { repo.saveAsync(settings.copy(dualPage = it)) }
                )
                SwitchItem(
                    label = stringResource(R.string.settings_auto_dual_page),
                    description = stringResource(R.string.settings_auto_dual_page_desc),
                    checked = settings.autoDualPageInLandscape,
                    onCheckedChange = { repo.saveAsync(settings.copy(autoDualPageInLandscape = it)) }
                )
                if (settings.dualPage || settings.autoDualPageInLandscape) {
                    SwitchItem(
                        label = stringResource(R.string.settings_dual_page_start_one),
                        description = stringResource(R.string.settings_dual_page_start_one_desc),
                        checked = settings.dualPageStartOne,
                        onCheckedChange = { repo.saveAsync(settings.copy(dualPageStartOne = it)) }
                    )
                    SliderItem(
                        label = stringResource(R.string.settings_dual_page_spacing, settings.dualPageSpacing),
                        value = settings.dualPageSpacing.toFloat(),
                        range = 0f..64f,
                        onValueChange = { repo.saveAsync(settings.copy(dualPageSpacing = it.toInt())) }
                    )
                    SwitchItem(
                        label = stringResource(R.string.settings_page_shadow),
                        description = stringResource(R.string.settings_page_shadow_desc),
                        checked = settings.pageShadow,
                        onCheckedChange = { repo.saveAsync(settings.copy(pageShadow = it)) }
                    )
                }
                SwitchItem(
                    label = stringResource(R.string.settings_volume_key_nav),
                    description = stringResource(R.string.settings_volume_key_nav_desc),
                    checked = settings.volumeKeyNav,
                    onCheckedChange = { repo.saveAsync(settings.copy(volumeKeyNav = it)) }
                )
                EnumSelector(
                    label = stringResource(R.string.settings_page_animation),
                    description = stringResource(R.string.settings_page_animation_desc),
                    options = PageAnimation.entries.map { it.chineseName(context) to it },
                    selected = settings.pageAnimation,
                    onSelect = { value -> repo.saveAsync(settings.copy(pageAnimation = value, randomAnimation = false)) }
                )
                EnumSelector(
                    label = stringResource(R.string.settings_orientation_lock),
                    description = stringResource(R.string.settings_orientation_lock_desc),
                    options = OrientationLock.entries.map {
                        when (it) {
                            OrientationLock.FOLLOW_SYSTEM -> context.getString(R.string.orientation_lock_follow_system) to it
                            OrientationLock.PORTRAIT -> context.getString(R.string.orientation_lock_portrait) to it
                            OrientationLock.LANDSCAPE -> context.getString(R.string.orientation_lock_landscape) to it
                            OrientationLock.AUTO_ROTATE -> context.getString(R.string.orientation_lock_auto_rotate) to it
                        }
                    },
                    selected = settings.orientationLock,
                    onSelect = { value -> repo.saveAsync(settings.copy(orientationLock = value)) }
                )
                EnumSelector(
                    label = stringResource(R.string.settings_tap_zone_size),
                    description = stringResource(R.string.settings_tap_zone_size_desc),
                    options = TapZoneSize.entries.map { it.chineseName(context) to it },
                    selected = settings.tapZoneSize,
                    onSelect = { value -> repo.saveAsync(settings.copy(tapZoneSize = value)) }
                )
                SwitchItem(
                    label = stringResource(R.string.settings_auto_brightness),
                    description = stringResource(R.string.settings_auto_brightness_desc),
                    checked = settings.autoBrightness,
                    onCheckedChange = { repo.saveAsync(settings.copy(autoBrightness = it)) }
                )
                SwitchItem(
                    label = stringResource(R.string.settings_anti_mistouch),
                    description = stringResource(R.string.settings_anti_mistouch_desc),
                    checked = settings.antiMistouch,
                    onCheckedChange = { repo.saveAsync(settings.copy(antiMistouch = it)) }
                )
                SwitchItem(
                    label = stringResource(R.string.settings_reader_status_bar),
                    description = stringResource(R.string.settings_reader_status_bar_desc),
                    checked = settings.readerStatusBar,
                    onCheckedChange = { repo.saveAsync(settings.copy(readerStatusBar = it)) }
                )
                SwitchItem(
                    label = stringResource(R.string.settings_thumbnail_strip),
                    description = stringResource(R.string.settings_thumbnail_strip_desc),
                    checked = settings.thumbnailStripEnabled,
                    onCheckedChange = { repo.saveAsync(settings.copy(thumbnailStripEnabled = it)) }
                )
                SwitchItem(
                    label = stringResource(R.string.settings_keep_screen_on),
                    description = stringResource(R.string.settings_keep_screen_on_desc),
                    checked = settings.keepScreenOn,
                    onCheckedChange = { repo.saveAsync(settings.copy(keepScreenOn = it)) }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_reset_reader)) },
                    supportingContent = { Text(stringResource(R.string.settings_reset_reader_desc), style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(Icons.Default.RestartAlt, contentDescription = stringResource(R.string.settings_reset_reader)) },
                    modifier = Modifier.clickable {
                        scope.launch(Dispatchers.IO) {
                            repo.resetReaderSettings()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, context.getString(R.string.settings_reset_reader_toast), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }

            // ── 图像设置 ──
            SettingsGroup(title = stringResource(R.string.settings_group_image)) {
                EnumSelector(
                    label = stringResource(R.string.settings_zoom_mode),
                    description = stringResource(R.string.settings_zoom_mode_desc),
                    options = ZoomMode.entries.map { it.chineseName(context) to it },
                    selected = settings.zoomMode,
                    onSelect = { value -> repo.saveAsync(settings.copy(zoomMode = value)) }
                )
                EnumSelector(
                    label = stringResource(R.string.settings_scale_filter),
                    description = stringResource(R.string.settings_scale_filter_desc),
                    options = ScaleFilter.entries.map { it.chineseName(context) to it },
                    selected = settings.scaleFilter,
                    onSelect = { value -> repo.saveAsync(settings.copy(scaleFilter = value)) }
                )
                SwitchItem(
                    label = stringResource(R.string.settings_auto_crop),
                    description = stringResource(R.string.settings_auto_crop_desc),
                    checked = settings.autoCrop,
                    onCheckedChange = { repo.saveAsync(settings.copy(autoCrop = it)) }
                )
                val showCropMargins = settings.autoCrop ||
                        settings.cropMarginTop > 0 || settings.cropMarginBottom > 0 ||
                        settings.cropMarginLeft > 0 || settings.cropMarginRight > 0
                if (showCropMargins) {
                    SliderItem(
                        label = stringResource(R.string.settings_crop_margin_top, settings.cropMarginTop),
                        value = settings.cropMarginTop.toFloat(),
                        range = 0f..20f,
                        onValueChange = { repo.saveAsync(settings.copy(cropMarginTop = it.toInt())) }
                    )
                    SliderItem(
                        label = stringResource(R.string.settings_crop_margin_bottom, settings.cropMarginBottom),
                        value = settings.cropMarginBottom.toFloat(),
                        range = 0f..20f,
                        onValueChange = { repo.saveAsync(settings.copy(cropMarginBottom = it.toInt())) }
                    )
                    SliderItem(
                        label = stringResource(R.string.settings_crop_margin_left, settings.cropMarginLeft),
                        value = settings.cropMarginLeft.toFloat(),
                        range = 0f..20f,
                        onValueChange = { repo.saveAsync(settings.copy(cropMarginLeft = it.toInt())) }
                    )
                    SliderItem(
                        label = stringResource(R.string.settings_crop_margin_right, settings.cropMarginRight),
                        value = settings.cropMarginRight.toFloat(),
                        range = 0f..20f,
                        onValueChange = { repo.saveAsync(settings.copy(cropMarginRight = it.toInt())) }
                    )
                }
                SwitchItem(
                    label = stringResource(R.string.settings_grayscale),
                    description = stringResource(R.string.settings_grayscale_desc),
                    checked = settings.grayscale,
                    onCheckedChange = { repo.saveAsync(settings.copy(grayscale = it)) }
                )
                SwitchItem(
                    label = stringResource(R.string.settings_sharpen),
                    description = stringResource(R.string.settings_sharpen_desc),
                    checked = settings.sharpen,
                    onCheckedChange = { repo.saveAsync(settings.copy(sharpen = it)) }
                )
                SwitchItem(
                    label = stringResource(R.string.settings_denoise),
                    description = stringResource(R.string.settings_denoise_desc),
                    checked = settings.denoise,
                    onCheckedChange = { repo.saveAsync(settings.copy(denoise = it)) }
                )
                SwitchItem(
                    label = stringResource(R.string.settings_mirror),
                    description = stringResource(R.string.settings_mirror_desc),
                    checked = settings.mirror,
                    onCheckedChange = { repo.saveAsync(settings.copy(mirror = it)) }
                )
                SwitchItem(
                    label = stringResource(R.string.settings_night_mode),
                    description = stringResource(R.string.settings_night_mode_desc),
                    checked = settings.nightMode,
                    onCheckedChange = { repo.saveAsync(settings.copy(nightMode = it)) }
                )
                SwitchItem(
                    label = stringResource(R.string.settings_eye_care),
                    description = stringResource(R.string.settings_eye_care_desc),
                    checked = settings.eyeCare,
                    onCheckedChange = { repo.saveAsync(settings.copy(eyeCare = it)) }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_rotate_image)) },
                    supportingContent = { Text(stringResource(R.string.settings_rotate_image_desc), style = MaterialTheme.typography.bodySmall) },
                    trailingContent = {
                        Text(stringResource(R.string.settings_rotation_degrees, settings.rotation))
                    },
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = stringResource(R.string.settings_rotate_image))
                    },
                    modifier = Modifier.clickable {
                        repo.saveAsync(settings.copy(rotation = (settings.rotation + 90) % 360))
                    }
                )
                SliderItem(
                    label = stringResource(R.string.settings_brightness),
                    value = settings.brightness,
                    range = 0.5f..2f,
                    onValueChange = { repo.saveAsync(settings.copy(brightness = it)) }
                )
                SliderItem(
                    label = stringResource(R.string.settings_contrast),
                    value = settings.contrast,
                    range = 0.5f..2f,
                    onValueChange = { repo.saveAsync(settings.copy(contrast = it)) }
                )
                SliderItem(
                    label = stringResource(R.string.settings_gamma),
                    value = settings.gamma,
                    range = 0.5f..2.5f,
                    onValueChange = { repo.saveAsync(settings.copy(gamma = it)) }
                )
                SliderItem(
                    label = stringResource(R.string.settings_saturation),
                    value = settings.saturation,
                    range = 0f..2f,
                    onValueChange = { repo.saveAsync(settings.copy(saturation = it)) }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_reset_filters)) },
                    supportingContent = { Text(stringResource(R.string.settings_reset_filters_desc), style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(Icons.Default.RestartAlt, contentDescription = stringResource(R.string.settings_reset_filters)) },
                    modifier = Modifier.clickable {
                        repo.saveAsync(settings.resetImageFilters())
                        Toast.makeText(context, context.getString(R.string.settings_filters_reset_toast), Toast.LENGTH_SHORT).show()
                    }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_reset_image)) },
                    supportingContent = { Text(stringResource(R.string.settings_reset_image_desc), style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(Icons.Default.RestartAlt, contentDescription = stringResource(R.string.settings_reset_image)) },
                    modifier = Modifier.clickable {
                        scope.launch(Dispatchers.IO) {
                            repo.resetImageSettings()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, context.getString(R.string.settings_reset_image_toast), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }

            // ── 手势与按键 ──
            SettingsGroup(title = stringResource(R.string.settings_group_gesture)) {
                SwitchItem(
                    label = stringResource(R.string.settings_enable_zoom),
                    description = stringResource(R.string.settings_enable_zoom_desc),
                    checked = settings.enableZoom,
                    onCheckedChange = { repo.saveAsync(settings.copy(enableZoom = it)) }
                )
                SwitchItem(
                    label = stringResource(R.string.settings_magnifier),
                    description = stringResource(R.string.settings_magnifier_desc),
                    checked = settings.magnifierEnabled,
                    onCheckedChange = { repo.saveAsync(settings.copy(magnifierEnabled = it)) }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_custom_gestures)) },
                    supportingContent = { Text(stringResource(R.string.settings_custom_gestures_desc), style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(Icons.Default.TouchApp, contentDescription = stringResource(R.string.settings_custom_gestures)) },
                    modifier = Modifier.clickable { onOpenGestureSettings() }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_reset_gesture)) },
                    supportingContent = { Text(stringResource(R.string.settings_reset_gesture_desc), style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(Icons.Default.RestartAlt, contentDescription = stringResource(R.string.settings_reset_gesture)) },
                    modifier = Modifier.clickable {
                        scope.launch(Dispatchers.IO) {
                            repo.resetGestureSettings()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, context.getString(R.string.settings_reset_gesture_toast), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }

            // ── 文字朗读 ──
            SettingsGroup(title = stringResource(R.string.settings_group_tts)) {
                SwitchItem(
                    label = stringResource(R.string.settings_tts_enable),
                    description = stringResource(R.string.settings_tts_enable_desc),
                    checked = settings.ttsEnabled,
                    onCheckedChange = { repo.saveAsync(settings.copy(ttsEnabled = it)) }
                )
                if (settings.ttsEnabled) {
                    SliderItem(
                        label = stringResource(R.string.settings_tts_speed),
                        value = settings.ttsSpeed,
                        range = 0.25f..4f,
                        onValueChange = { repo.saveAsync(settings.copy(ttsSpeed = it)) }
                    )
                    SliderItem(
                        label = stringResource(R.string.settings_tts_pitch),
                        value = settings.ttsPitch,
                        range = 0.25f..4f,
                        onValueChange = { repo.saveAsync(settings.copy(ttsPitch = it)) }
                    )
                }
            }

            // ── 阅读目标 ──
            SettingsGroup(title = stringResource(R.string.settings_group_goals)) {
                var pagesText by remember(readingGoals.dailyPages) {
                    mutableStateOf(if (readingGoals.dailyPages > 0) readingGoals.dailyPages.toString() else "")
                }
                var minutesText by remember(readingGoals.dailyMinutes) {
                    mutableStateOf(if (readingGoals.dailyMinutes > 0) readingGoals.dailyMinutes.toString() else "")
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.settings_goals_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = pagesText,
                            onValueChange = {
                                pagesText = it.filter { ch -> ch.isDigit() }.take(4)
                            },
                            label = { Text(stringResource(R.string.settings_goals_daily_pages)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minutesText,
                            onValueChange = {
                                minutesText = it.filter { ch -> ch.isDigit() }.take(4)
                            },
                            label = { Text(stringResource(R.string.settings_goals_daily_minutes)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val pages = pagesText.toIntOrNull()?.coerceAtLeast(0) ?: 0
                            val minutes = minutesText.toIntOrNull()?.coerceAtLeast(0) ?: 0
                            scope.launch(Dispatchers.IO) {
                                repo.saveReadingGoals(com.mangareader.data.ReadingGoals(dailyPages = pages, dailyMinutes = minutes))
                            }
                            Toast.makeText(context, context.getString(R.string.settings_goals_saved_toast), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }

            // ── 外观主题 ──
            SettingsGroup(title = stringResource(R.string.settings_group_appearance)) {
                Text(
                    stringResource(R.string.settings_theme_skin),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                AppTheme.entries.forEach { theme ->
                    val previewColor = if (theme == AppTheme.CUSTOM) {
                        try {
                            androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(settings.customSeedColor))
                        } catch (_: Exception) {
                            androidx.compose.ui.graphics.Color(theme.seedColor)
                        }
                    } else androidx.compose.ui.graphics.Color(theme.seedColor)
                    ListItem(
                        headlineContent = { Text(theme.displayName(context)) },
                        leadingContent = {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = previewColor,
                                modifier = Modifier.size(28.dp)
                            ) {}
                        },
                        trailingContent = {
                            if (settings.appTheme == theme) {
                                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_ok))
                            }
                        },
                        modifier = Modifier.clickable {
                            repo.saveAsync(
                                settings.copy(
                                    appTheme = theme,
                                    backgroundColor = if (theme == AppTheme.CUSTOM) settings.backgroundColor else theme.readerBackground,
                                    backgroundTexture = if (theme == AppTheme.CUSTOM) settings.backgroundTexture else theme.readerTexture
                                )
                            )
                        }
                    )
                }

                EnumSelector(
                    label = stringResource(R.string.settings_theme_auto),
                    description = stringResource(R.string.settings_theme_auto_desc),
                    options = ThemeAutoMode.entries.map { it.chineseName(context) to it },
                    selected = settings.themeAutoMode,
                    onSelect = { value -> repo.saveAsync(settings.copy(themeAutoMode = value)) }
                )

                if (settings.appTheme == AppTheme.CUSTOM) {
                    val parsedColor = remember(settings.customSeedColor) {
                        try {
                            androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(settings.customSeedColor))
                        } catch (_: Exception) {
                            androidx.compose.ui.graphics.Color(0xFF5C7A94)
                        }
                    }
                    var r by remember { mutableStateOf((parsedColor.red * 255).toInt().coerceIn(0, 255).toString()) }
                    var g by remember { mutableStateOf((parsedColor.green * 255).toInt().coerceIn(0, 255).toString()) }
                    var b by remember { mutableStateOf((parsedColor.blue * 255).toInt().coerceIn(0, 255).toString()) }

                    LaunchedEffect(settings.customSeedColor) {
                        val c = try {
                            androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(settings.customSeedColor))
                        } catch (_: Exception) {
                            androidx.compose.ui.graphics.Color(0xFF5C7A94)
                        }
                        r = (c.red * 255).toInt().coerceIn(0, 255).toString()
                        g = (c.green * 255).toInt().coerceIn(0, 255).toString()
                        b = (c.blue * 255).toInt().coerceIn(0, 255).toString()
                    }

                    fun applyCustomColor() {
                        val rr = r.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        val gg = g.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        val bb = b.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        val hex = String.format("#FF%02X%02X%02X", rr, gg, bb)
                        repo.saveAsync(settings.copy(customSeedColor = hex))
                    }

                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            stringResource(R.string.settings_custom_theme_color),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = r,
                                onValueChange = { r = it.filter { ch -> ch.isDigit() }.take(3); applyCustomColor() },
                                label = { Text(stringResource(R.string.settings_custom_color_red)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = g,
                                onValueChange = { g = it.filter { ch -> ch.isDigit() }.take(3); applyCustomColor() },
                                label = { Text(stringResource(R.string.settings_custom_color_green)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = b,
                                onValueChange = { b = it.filter { ch -> ch.isDigit() }.take(3); applyCustomColor() },
                                label = { Text(stringResource(R.string.settings_custom_color_blue)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        val liveColor = try {
                            androidx.compose.ui.graphics.Color(
                                android.graphics.Color.parseColor(
                                    String.format("#FF%02X%02X%02X",
                                        r.toIntOrNull()?.coerceIn(0, 255) ?: 0,
                                        g.toIntOrNull()?.coerceIn(0, 255) ?: 0,
                                        b.toIntOrNull()?.coerceIn(0, 255) ?: 0
                                    )
                                )
                            )
                        } catch (_: Exception) {
                            androidx.compose.ui.graphics.Color(0xFF5C7A94)
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(liveColor, CircleShape)
                        )
                    }
                }
            }

            // ── 书库设置 ──
            SettingsGroup(title = stringResource(R.string.settings_group_library)) {
                val cacheDir = context.cacheDir
                var cacheSize by remember { mutableStateOf(context.formatSize(0L)) }
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        cacheSize = context.formatSize(calcCacheSize(cacheDir))
                    }
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_cache_management)) },
                    supportingContent = { Text(stringResource(R.string.settings_cache_size_desc, cacheSize)) },
                    leadingContent = { Icon(Icons.Default.Storage, contentDescription = stringResource(R.string.settings_cache_management)) },
                    modifier = Modifier.clickable { onOpenCacheManagement() }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_statistics)) },
                    supportingContent = { Text(stringResource(R.string.settings_statistics_desc)) },
                    leadingContent = { Icon(Icons.Default.BarChart, contentDescription = stringResource(R.string.settings_statistics)) },
                    modifier = Modifier.clickable { onOpenStatistics() }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_export_backup)) },
                    supportingContent = { Text(stringResource(R.string.settings_export_backup_desc)) },
                    leadingContent = { Icon(Icons.Default.Backup, contentDescription = stringResource(R.string.settings_export_backup)) },
                    modifier = Modifier.clickable {
                        createBackupLauncher.launch("manga_reader_backup_${System.currentTimeMillis()}.json")
                    }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_import_backup)) },
                    supportingContent = { Text(stringResource(R.string.settings_import_backup_desc)) },
                    leadingContent = { Icon(Icons.Default.Restore, contentDescription = stringResource(R.string.settings_import_backup)) },
                    modifier = Modifier.clickable {
                        openBackupLauncher.launch(arrayOf("application/json", "*/*"))
                    }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_clear_cache)) },
                    supportingContent = { Text(stringResource(R.string.settings_clear_cache_desc)) },
                    leadingContent = { Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.settings_clear_cache)) },
                    modifier = Modifier.clickable {
                        scope.launch(Dispatchers.IO) {
                            clearCache(cacheDir)
                            val newSize = context.formatSize(calcCacheSize(cacheDir))
                            withContext(Dispatchers.Main) {
                                cacheSize = newSize
                                Toast.makeText(context, context.getString(R.string.settings_cache_cleared_toast), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }

            // ── 关于 ──
            SettingsGroup(title = stringResource(R.string.settings_group_about)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_export_crash_logs)) },
                    supportingContent = { Text(stringResource(R.string.settings_export_crash_logs_desc)) },
                    leadingContent = { Icon(Icons.Default.BugReport, contentDescription = stringResource(R.string.settings_export_crash_logs)) },
                    modifier = Modifier.clickable {
                        val logs = CrashHandler.readCrashLogs()
                        if (logs.isEmpty()) {
                            Toast.makeText(context, context.getString(R.string.settings_crash_logs_empty_toast), Toast.LENGTH_SHORT).show()
                        } else {
                            try {
                                val logText = logs.joinToString("\n\n---\n\n")
                                val logDir = java.io.File(context.cacheDir, "crash_logs").apply { mkdirs() }
                                val file = java.io.File(logDir, "crash_logs_export_${System.currentTimeMillis()}.txt")
                                java.io.FileOutputStream(file).use { out ->
                                    out.write(logText.toByteArray(Charsets.UTF_8))
                                }
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    context.packageName + Constants.FILE_PROVIDER_AUTHORITY_SUFFIX,
                                    file
                                )
                                val intent = android.content.Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_crash_logs_export_subject))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, context.getString(R.string.settings_crash_logs_export_chooser)))
                            } catch (e: Exception) {
                                Toast.makeText(context, context.getString(R.string.settings_crash_logs_export_failed_toast, e.message ?: ""), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_version)) },
                    supportingContent = { Text(tryGetVersion(context)) }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_contact_author)) },
                    supportingContent = { Text(Constants.AUTHOR_CONTACT) },
                    modifier = Modifier.clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("作者联系方式", Constants.AUTHOR_CONTACT))
                        Toast.makeText(context, context.getString(R.string.settings_contact_copied_toast), Toast.LENGTH_SHORT).show()
                    }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_open_source_notice)) },
                    supportingContent = { Text(stringResource(R.string.settings_open_source_desc)) },
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
                                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_ok))
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

private fun ReaderSettings.resetImageFilters(): ReaderSettings = copy(
    autoCrop = false,
    cropMarginTop = 0,
    cropMarginBottom = 0,
    cropMarginLeft = 0,
    cropMarginRight = 0,
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

private fun ScrollMode.chineseName(context: Context): String = when (this) {
    ScrollMode.PAGE -> context.getString(R.string.scroll_mode_page)
    ScrollMode.VERTICAL -> context.getString(R.string.scroll_mode_vertical)
    ScrollMode.HORIZONTAL -> context.getString(R.string.scroll_mode_horizontal)
}

private fun ZoomMode.chineseName(context: Context): String = when (this) {
    ZoomMode.FREE -> context.getString(R.string.zoom_mode_free)
    ZoomMode.FIT_WIDTH -> context.getString(R.string.zoom_mode_fit_width)
    ZoomMode.FIT_HEIGHT -> context.getString(R.string.zoom_mode_fit_height)
    ZoomMode.FIT_SCREEN -> context.getString(R.string.zoom_mode_fit_screen)
}

private fun PageAnimation.chineseName(context: Context): String = when (this) {
    PageAnimation.NONE -> context.getString(R.string.page_animation_none)
    PageAnimation.SLIDE -> context.getString(R.string.page_animation_slide)
    PageAnimation.FADE -> context.getString(R.string.page_animation_fade)
    PageAnimation.PIXEL -> context.getString(R.string.page_animation_pixel)
    PageAnimation.BLINDS -> context.getString(R.string.page_animation_blinds)
    PageAnimation.SHATTER -> context.getString(R.string.page_animation_shatter)
    PageAnimation.DOTS -> context.getString(R.string.page_animation_dots)
}

private fun TapZoneSize.chineseName(context: Context): String = when (this) {
    TapZoneSize.SMALL -> context.getString(R.string.tap_zone_size_small)
    TapZoneSize.MEDIUM -> context.getString(R.string.tap_zone_size_medium)
    TapZoneSize.LARGE -> context.getString(R.string.tap_zone_size_large)
}

private fun ScaleFilter.chineseName(context: Context): String = when (this) {
    ScaleFilter.BILINEAR -> context.getString(R.string.scale_filter_bilinear)
    ScaleFilter.BICUBIC -> context.getString(R.string.scale_filter_bicubic)
    ScaleFilter.LANCZOS3 -> context.getString(R.string.scale_filter_lanczos3)
}

private fun ThemeAutoMode.chineseName(context: Context): String = when (this) {
    ThemeAutoMode.OFF -> context.getString(R.string.theme_auto_off)
    ThemeAutoMode.FOLLOW_SYSTEM -> context.getString(R.string.theme_auto_follow_system)
    ThemeAutoMode.SCHEDULE -> context.getString(R.string.theme_auto_schedule)
}

private fun calcCacheSize(dir: java.io.File): Long {
    return dir.walkBottomUp().filter { it.isFile }.map { it.length() }.sum()
}

private fun clearCache(dir: java.io.File) {
    dir.listFiles()?.forEach { child ->
        if (child.isDirectory) child.deleteRecursively() else child.delete()
    }
}

private fun Context.formatSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> getString(R.string.cache_size_format_gb, bytes / (1024 * 1024 * 1024f))
        bytes >= 1024 * 1024 -> getString(R.string.cache_size_format_mb, bytes / (1024 * 1024f))
        bytes >= 1024 -> getString(R.string.cache_size_format_kb, bytes / 1024f)
        else -> getString(R.string.cache_size_format_b, bytes.toInt())
    }
}

private fun tryGetVersion(context: Context): String {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: context.getString(R.string.settings_version_unknown)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: context.getString(R.string.settings_version_unknown)
        }
    }.getOrDefault(context.getString(R.string.settings_version_unknown))
}
