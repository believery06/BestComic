package com.mangareader.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mangareader.R
import com.mangareader.data.SettingsRepository
import com.mangareader.utils.OnboardingUtils
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 引导所需状态的聚合快照。
 * [loaded] 用于区分「DataStore 尚未返回首帧」与「已加载但值为空/默认」：
 * DataStore Flow 的初始值为 false/null，若据此判断会在每次冷启动瞬间误判为
 * 「尚未选择书架目录」，导致重复弹出目录选择框。
 */
private data class OnboardingState(
    val loaded: Boolean = false,
    val permissionAck: Boolean = false,
    val helpDontShow: Boolean = false,
    val bookshelfRoot: String? = null
)

/**
 * Two-step first-run experience, hosted by the top-level NavHost in
 * MangaReaderApp:
 *
 *   1. Permission dialog.
 *      - Android 11+：使用 Storage Access Framework (SAF) 选择书架目录，
 *        不再请求 MANAGE_EXTERNAL_STORAGE / 「管理所有文件」权限。
 *      - Android 10 及以下：请求 READ_EXTERNAL_STORAGE 运行时权限。
 *   2. Help dialog. Shown only after the permission dialog has been seen.
 *      Three outcomes:
 *        - "不再显示" → stored in DataStore; never shown again.
 *        - "关闭"     → just dismiss for this session; shown again next time.
 *        - "查看完整说明" → re-opens the same dialog from the top app bar.
 *
 * The dialogs are gated by `permissionAcknowledged` and `helpDontShow` so
 * they appear exactly once on the first run.
 */
@Composable
fun OnboardingHost(
    onOpenHelp: () -> Unit = {},
    settingsRepository: SettingsRepository,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 将三个 DataStore Flow 聚合为单一状态，并记录是否已完成首帧加载。
    // 未加载完成（loaded=false）时不评估弹窗条件，避免用默认值误判。
    val onboardingState by produceState(initialValue = OnboardingState()) {
        combine(
            settingsRepository.getPermissionAcknowledged(),
            settingsRepository.getHelpDontShow(),
            settingsRepository.getBookshelfRoot()
        ) { permissionAck, helpDontShow, bookshelfRoot ->
            OnboardingState(
                loaded = true,
                permissionAck = permissionAck,
                helpDontShow = helpDontShow,
                bookshelfRoot = bookshelfRoot
            )
        }.collect { value = it }
    }
    val permissionAck = onboardingState.permissionAck
    val helpDontShow = onboardingState.helpDontShow
    val bookshelfRoot = onboardingState.bookshelfRoot

    var hasStorage by remember {
        mutableStateOf(OnboardingUtils.hasStoragePermission(context))
    }
    // 仅在首次使用且尚未完成授权/选择书架时显示权限对话框
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    val isAndroid11Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasStorage = granted
        if (granted) {
            showPermissionDialog = false
            scope.launch { settingsRepository.setPermissionAcknowledged(true) }
        }
    }

    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            showPermissionDialog = false
            scope.launch {
                settingsRepository.saveBookshelfRoot(it.toString())
                settingsRepository.setPermissionAcknowledged(true)
            }
        }
    }

    // 当引导状态从 DataStore 加载完毕后，决定是否显示对话框。
    // 注意：必须等首次加载完成（loaded）才判断，且当已选择书架/已确认权限时
    // 要显式收起对话框，避免冷启动默认值误判后弹窗一直停留。
    LaunchedEffect(onboardingState) {
        if (!onboardingState.loaded) return@LaunchedEffect
        val lowVersionNeedsPermission = !isAndroid11Plus && !OnboardingUtils.hasStoragePermission(context)
        val highVersionNeedsBookshelf = isAndroid11Plus && bookshelfRoot == null
        if (!permissionAck && (lowVersionNeedsPermission || highVersionNeedsBookshelf)) {
            showPermissionDialog = true
        } else {
            showPermissionDialog = false
        }
        if (permissionAck && !helpDontShow && !showHelpDialog) {
            showHelpDialog = true
        }
    }

    // 从系统设置返回时刷新低版本权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStorage = OnboardingUtils.hasStoragePermission(context)
                if (!isAndroid11Plus && hasStorage && showPermissionDialog) {
                    showPermissionDialog = false
                    scope.launch { settingsRepository.setPermissionAcknowledged(true) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    content()

    if (showPermissionDialog) {
        PermissionDialog(
            isAndroid11Plus = isAndroid11Plus,
            onGrant = {
                if (isAndroid11Plus) {
                    safLauncher.launch(null)
                } else {
                    storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            },
            onOpenSettings = {
                val intent = OnboardingUtils.buildAppDetailsIntent(context)
                runCatching { context.startActivity(intent) }
            },
            onClose = {
                showPermissionDialog = false
                scope.launch { settingsRepository.setPermissionAcknowledged(true) }
            }
        )
    }

    if (showHelpDialog) {
        HelpDialog(
            onClose = { showHelpDialog = false },
            onDontShowAgain = {
                scope.launch {
                    settingsRepository.setHelpDontShow(true)
                }
                showHelpDialog = false
            },
            onOpenFullHelp = onOpenHelp
        )
    }
}

@Composable
private fun PermissionDialog(
    isAndroid11Plus: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        // 允许点击外部或系统返回键关闭（等价于「稍后」）。
        // 原实现为 onDismissRequest = { }，一旦弹窗因状态误判残留，
        // 返回键与点击外部均无效，用户会被卡死在弹窗上只能强制关闭应用。
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.onboarding_permission_title)) },
        text = {
            Column {
                if (isAndroid11Plus) {
                    Text(
                        stringResource(R.string.onboarding_permission_saf_message)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.onboarding_permission_saf_hint),
                        fontSize = 13.sp
                    )
                } else {
                    Text(stringResource(R.string.onboarding_permission_storage_message))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.onboarding_permission_storage_hint),
                        fontSize = 13.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onGrant) {
                Text(
                    if (isAndroid11Plus) {
                        stringResource(R.string.onboarding_permission_select_bookshelf)
                    } else {
                        stringResource(R.string.onboarding_permission_request)
                    }
                )
            }
        },
        dismissButton = {
            Row {
                if (!isAndroid11Plus) {
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.onboarding_permission_go_settings)) }
                }
                TextButton(onClick = onClose) { Text(stringResource(R.string.onboarding_permission_later)) }
            }
        }
    )
}

@Composable
private fun HelpDialog(
    onClose: () -> Unit,
    onDontShowAgain: () -> Unit,
    onOpenFullHelp: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.onboarding_help_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                HelpSection(stringResource(R.string.onboarding_help_section_bookshelf))
                Text(stringResource(R.string.onboarding_help_bookshelf_1))
                Text(stringResource(R.string.onboarding_help_bookshelf_2))
                Text(stringResource(R.string.onboarding_help_bookshelf_3))
                Text(stringResource(R.string.onboarding_help_bookshelf_4))
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection(stringResource(R.string.onboarding_help_section_reading))
                Text(stringResource(R.string.onboarding_help_reading_1))
                Text(stringResource(R.string.onboarding_help_reading_2))
                Text(stringResource(R.string.onboarding_help_reading_3))
                Text(stringResource(R.string.onboarding_help_reading_4))
                Text(stringResource(R.string.onboarding_help_reading_5))
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection(stringResource(R.string.onboarding_help_section_mode))
                Text(stringResource(R.string.onboarding_help_mode_1))
                Text(stringResource(R.string.onboarding_help_mode_2))
                Text(stringResource(R.string.onboarding_help_mode_3))
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection(stringResource(R.string.onboarding_help_section_image))
                Text(stringResource(R.string.onboarding_help_image_1))
                Text(stringResource(R.string.onboarding_help_image_2))
                Text(stringResource(R.string.onboarding_help_image_3))
                Text(stringResource(R.string.onboarding_help_image_4))
                Text(stringResource(R.string.onboarding_help_image_5))
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection(stringResource(R.string.onboarding_help_section_progress))
                Text(stringResource(R.string.onboarding_help_progress_1))
                Text(stringResource(R.string.onboarding_help_progress_2))
                Text(stringResource(R.string.onboarding_help_progress_3))
                Text(stringResource(R.string.onboarding_help_progress_4))
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection(stringResource(R.string.onboarding_help_section_formats))
                Text(stringResource(R.string.onboarding_help_formats_1))
                Text(stringResource(R.string.onboarding_help_formats_2))
                Text(stringResource(R.string.onboarding_help_formats_3))
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection(stringResource(R.string.onboarding_help_section_privacy))
                Text(stringResource(R.string.onboarding_help_privacy_1))
                Text(stringResource(R.string.onboarding_help_privacy_2))
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection(stringResource(R.string.onboarding_help_section_experimental))
                Text(stringResource(R.string.onboarding_help_experimental_1))
                Text(stringResource(R.string.onboarding_help_experimental_2))
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.onboarding_help_close)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    onClose()
                    onOpenFullHelp()
                }) { Text(stringResource(R.string.onboarding_help_full_help)) }
                TextButton(onClick = onDontShowAgain) { Text(stringResource(R.string.onboarding_help_dont_show)) }
            }
        }
    )
}

@Composable
private fun HelpSection(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

/**
 * Variant of the help dialog that is opened manually from the bookshelf top
 * bar. It always shows the same content; the "不再显示" button here is
 * replaced by "重置引导" so a user who previously dismissed the first-run
 * dialog can re-enable it.
 */
@Composable
fun ManualHelpDialog(
    onClose: () -> Unit,
    onResetHelpPreference: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.onboarding_help_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                HelpSection(stringResource(R.string.onboarding_help_section_bookshelf))
                Text(stringResource(R.string.onboarding_help_bookshelf_1))
                Text(stringResource(R.string.onboarding_help_bookshelf_2))
                Text(stringResource(R.string.onboarding_help_bookshelf_3))
                Text(stringResource(R.string.onboarding_help_bookshelf_4))
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection(stringResource(R.string.onboarding_help_section_reading))
                Text(stringResource(R.string.onboarding_help_reading_1))
                Text(stringResource(R.string.onboarding_help_reading_2))
                Text(stringResource(R.string.onboarding_help_reading_3))
                Text(stringResource(R.string.onboarding_help_reading_4))
                Text(stringResource(R.string.onboarding_help_reading_5))
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection(stringResource(R.string.onboarding_help_section_mode))
                Text(stringResource(R.string.onboarding_help_mode_1))
                Text(stringResource(R.string.onboarding_help_mode_2))
                Text(stringResource(R.string.onboarding_help_mode_3))
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection(stringResource(R.string.onboarding_help_section_image))
                Text(stringResource(R.string.onboarding_help_image_1))
                Text(stringResource(R.string.onboarding_help_image_2))
                Text(stringResource(R.string.onboarding_help_image_3))
                Text(stringResource(R.string.onboarding_help_image_4))
                Text(stringResource(R.string.onboarding_help_image_5))
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection(stringResource(R.string.onboarding_help_section_progress))
                Text(stringResource(R.string.onboarding_help_progress_1))
                Text(stringResource(R.string.onboarding_help_progress_2))
                Text(stringResource(R.string.onboarding_help_progress_3))
                Text(stringResource(R.string.onboarding_help_progress_4))
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection(stringResource(R.string.onboarding_help_section_formats))
                Text(stringResource(R.string.onboarding_help_formats_1))
                Text(stringResource(R.string.onboarding_help_formats_2))
                Text(stringResource(R.string.onboarding_help_formats_3))
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection(stringResource(R.string.onboarding_help_section_privacy))
                Text(stringResource(R.string.onboarding_help_privacy_1))
                Text(stringResource(R.string.onboarding_help_privacy_2))
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection(stringResource(R.string.onboarding_help_section_experimental))
                Text(stringResource(R.string.onboarding_help_experimental_1))
                Text(stringResource(R.string.onboarding_help_experimental_2))
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.onboarding_help_close)) }
        },
        dismissButton = {
            TextButton(onClick = onResetHelpPreference) { Text(stringResource(R.string.onboarding_help_reset)) }
        }
    )
}
