package com.mangareader.settings

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangareader.R
import com.mangareader.data.GestureConfig
import com.mangareader.data.ReaderSettings
import com.mangareader.data.SettingsRepository
import com.mangareader.data.displayPresetName
import com.mangareader.data.presetKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureSettingsScreen(
    onBack: () -> Unit,
    repository: SettingsRepository? = null
) {
    val context = LocalContext.current
    val repo = repository ?: remember { SettingsRepository.getInstance(context) }
    val scope = rememberCoroutineScope()
    val settings by repo.readerSettings.collectAsStateWithLifecycle(initialValue = ReaderSettings())
    var config by remember(settings.gestureConfig) { mutableStateOf(settings.gestureConfig) }
    var localRtl by remember(settings.rtl) { mutableStateOf(settings.rtl) }
    var saving by remember { mutableStateOf(false) }

    // 保存过程中拦截返回键，避免用户误操作导致配置未写入即退出。
    BackHandler(enabled = saving) { /* 等待保存完成 */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gesture_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    val newSettings = settings.copy(gestureConfig = config, rtl = localRtl)
                                    repo.saveAsync(newSettings)
                                    repo.flushSave(newSettings)
                                    onBack()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    saving = false
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.gesture_save_failed),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        enabled = !saving
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.gesture_save))
                        }
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
            // 预设
            Text(
                text = stringResource(R.string.gesture_presets),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    PresetItem(
                        name = GestureConfig.PRESET_MANGA.displayPresetName(context),
                        selected = config.presetKey() == "manga"
                    ) {
                        config = GestureConfig.PRESET_MANGA
                        localRtl = true
                    }
                    PresetItem(
                        name = GestureConfig.PRESET_COMIC.displayPresetName(context),
                        selected = config.presetKey() == "comic"
                    ) {
                        config = GestureConfig.PRESET_COMIC
                        localRtl = false
                    }
                    PresetItem(
                        name = GestureConfig.PRESET_SIMPLE.displayPresetName(context),
                        selected = config.presetKey() == "simple"
                    ) {
                        config = GestureConfig.PRESET_SIMPLE
                        localRtl = false
                    }
                }
            }

            Text(
                text = stringResource(R.string.gesture_tap_areas),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    GestureSelector(stringResource(R.string.gesture_tap_left), config.tapLeft, context) { config = config.copy(tapLeft = it) }
                    GestureSelector(stringResource(R.string.gesture_tap_right), config.tapRight, context) { config = config.copy(tapRight = it) }
                    GestureSelector(stringResource(R.string.gesture_tap_center), config.tapCenter, context) { config = config.copy(tapCenter = it) }
                    GestureSelector(stringResource(R.string.gesture_tap_top), config.tapTop, context) { config = config.copy(tapTop = it) }
                    GestureSelector(stringResource(R.string.gesture_tap_bottom), config.tapBottom, context) { config = config.copy(tapBottom = it) }
                }
            }

            Text(
                text = stringResource(R.string.gesture_swipe_gestures),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    GestureSelector(stringResource(R.string.gesture_swipe_left), config.swipeLeft, context) { config = config.copy(swipeLeft = it) }
                    GestureSelector(stringResource(R.string.gesture_swipe_right), config.swipeRight, context) { config = config.copy(swipeRight = it) }
                    GestureSelector(stringResource(R.string.gesture_swipe_up), config.swipeUp, context) { config = config.copy(swipeUp = it) }
                    GestureSelector(stringResource(R.string.gesture_swipe_down), config.swipeDown, context) { config = config.copy(swipeDown = it) }
                }
            }

            Text(
                text = stringResource(R.string.gesture_other_gestures),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    GestureSelector(stringResource(R.string.gesture_double_tap), config.doubleTap, context) { config = config.copy(doubleTap = it) }
                    GestureSelector(stringResource(R.string.gesture_long_press), config.longPress, context) { config = config.copy(longPress = it) }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PresetItem(name: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(name) },
        trailingContent = {
            if (selected) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_ok))
        },
        modifier = Modifier.clickable { onClick() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GestureSelector(
    label: String,
    selected: GestureConfig.GestureAction,
    context: Context,
    onSelect: (GestureConfig.GestureAction) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            TextButton(onClick = { expanded = true }) {
                Text(selected.chineseName(context))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                GestureConfig.GestureAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.chineseName(context)) },
                        onClick = {
                            onSelect(action)
                            expanded = false
                        },
                        trailingIcon = {
                            if (action == selected) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_ok))
                        }
                    )
                }
            }
        },
        modifier = Modifier.clickable { expanded = true }
    )
}

private fun GestureConfig.GestureAction.chineseName(context: Context): String = when (this) {
    GestureConfig.GestureAction.NOTHING -> context.getString(R.string.gesture_action_nothing)
    GestureConfig.GestureAction.PREV_PAGE -> context.getString(R.string.gesture_action_prev_page)
    GestureConfig.GestureAction.NEXT_PAGE -> context.getString(R.string.gesture_action_next_page)
    GestureConfig.GestureAction.TOGGLE_MENU -> context.getString(R.string.gesture_action_toggle_menu)
    GestureConfig.GestureAction.ZOOM_TOGGLE -> context.getString(R.string.gesture_action_zoom_toggle)
    GestureConfig.GestureAction.MAGNIFIER -> context.getString(R.string.gesture_action_magnifier)
    GestureConfig.GestureAction.AUTO_CROP_TOGGLE -> context.getString(R.string.gesture_action_auto_crop_toggle)
    GestureConfig.GestureAction.BRIGHTNESS_UP -> context.getString(R.string.gesture_action_brightness_up)
    GestureConfig.GestureAction.BRIGHTNESS_DOWN -> context.getString(R.string.gesture_action_brightness_down)
    GestureConfig.GestureAction.NEXT_CHAPTER -> context.getString(R.string.gesture_action_next_chapter)
    GestureConfig.GestureAction.PREV_CHAPTER -> context.getString(R.string.gesture_action_prev_chapter)
    GestureConfig.GestureAction.ROTATE -> context.getString(R.string.gesture_action_rotate)
    GestureConfig.GestureAction.SLIDESHOW_TOGGLE -> context.getString(R.string.gesture_action_slideshow_toggle)
    GestureConfig.GestureAction.PANEL_VIEW -> context.getString(R.string.gesture_action_panel_view)
    GestureConfig.GestureAction.BOOKMARK -> context.getString(R.string.gesture_action_bookmark)
    GestureConfig.GestureAction.BACK -> context.getString(R.string.gesture_action_back)
}
