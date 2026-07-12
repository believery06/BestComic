package com.mangareader.onboarding

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mangareader.data.SettingsRepository
import com.mangareader.utils.OnboardingUtils
import kotlinx.coroutines.launch

/**
 * Two-step first-run experience, hosted by the top-level NavHost in
 * MangaReaderApp:
 *
 *   1. Permission dialog. The user can either jump to the system Settings
 *      screen to grant MANAGE_EXTERNAL_STORAGE, or close the dialog and use
 *      the SAF fallback (top app bar's "Browser" button always works).
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
    val permissionAck by settingsRepository.getPermissionAcknowledged()
        .collectAsStateWithLifecycle(initialValue = false)
    val helpDontShow by settingsRepository.getHelpDontShow()
        .collectAsStateWithLifecycle(initialValue = false)

    var hasManageFiles by remember {
        mutableStateOf(OnboardingUtils.hasManageAllFilesPermission(context))
    }
    // 仅在首次使用且尚未授权时显示权限对话框
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    // 当 permissionAck 从 DataStore 加载完毕后，决定是否显示对话框
    LaunchedEffect(permissionAck) {
        val currentPermission = OnboardingUtils.hasManageAllFilesPermission(context)
        hasManageFiles = currentPermission
        if (!permissionAck && !currentPermission) {
            showPermissionDialog = true
        }
        if (permissionAck && !helpDontShow && !showHelpDialog) {
            showHelpDialog = true
        }
    }

    // After returning from system Settings the user may have granted the
    // permission. When we become resumed, refresh the state.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val nowHasPermission = OnboardingUtils.hasManageAllFilesPermission(context)
                hasManageFiles = nowHasPermission
                // 如果已获得权限，自动关闭权限对话框并标记已确认
                if (nowHasPermission && showPermissionDialog) {
                    showPermissionDialog = false
                    scope.launch {
                        settingsRepository.setPermissionAcknowledged(true)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    content()

    if (showPermissionDialog) {
        PermissionDialog(
            hasManageFiles = hasManageFiles,
            onOpenSettings = {
                val intent = OnboardingUtils.buildManageAllFilesIntent(context)
                runCatching { context.startActivity(intent) }
            },
            onClose = {
                showPermissionDialog = false
                // Coroutine to persist the acknowledgment
                scope.launch {
                    settingsRepository.setPermissionAcknowledged(true)
                }
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
    hasManageFiles: Boolean,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("授予文件访问权限") },
        text = {
            Column {
                if (hasManageFiles) {
                    Text("已授予所有文件的管理权限。可以正常浏览本地漫画。")
                } else {
                    Text(
                        "为了让本应用像传统文件管理器一样自由读取你设备上的漫画，" +
                                "需要授予「管理所有文件」权限。"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "点击下方按钮跳转到系统设置，在「允许访问本设备上的全部文件」中开启本应用。",
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "如果你不想授予此权限，也可以使用顶部「浏览器」按钮用 SAF 选择文件夹。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (hasManageFiles) {
                TextButton(onClick = onClose) { Text("知道了") }
            } else {
                TextButton(onClick = onOpenSettings) { Text("去授予权限") }
            }
        },
        dismissButton = {
            if (!hasManageFiles) {
                TextButton(onClick = onClose) { Text("稍后") }
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
        title = { Text("使用说明") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                HelpSection("1. 书架")
                Text("• 首次进入后，点击右上角 + 选择一个文件夹作为书架目录")
                Text("• 根目录下的每个子文件夹会被视作一本漫画")
                Text("• 子文件夹内的多个 PDF / EPUB / CBZ 等按名称自然排序作为章节")
                Text("• 长按漫画卡片可标记为收藏")
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection("2. 阅读")
                Text("• 点击屏幕中央唤出/关闭菜单")
                Text("• 点击屏幕左/右区域翻页（RTL 日漫模式下方向相反）")
                Text("• 双指捏合可自由缩放（仅在「自由缩放」模式下）")
                Text("• 平板/横屏下，菜单会自动变成左右两侧栏，方便预览中间内容")
                Text("• 屏幕左边缘向右滑动可返回上一级")
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection("3. 阅读模式")
                Text("• 页模式：默认翻页模式，可切换单页 / 双页 / 日漫 RTL")
                Text("• 垂直滚动：适合韩式条漫、国产条漫")
                Text("• 水平滚动：长条横向漫画")
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection("4. 画面优化")
                Text("• 裁白边：扫描类漫画自动切除多余白边")
                Text("• 亮度 / 对比 / 伽马：调整图像观感")
                Text("• 黑白 / 锐化：改善老旧扫描件")
                Text("• 旋转 / 镜像：调整方向")
                Text("• 缩放适配：自由缩放 / 适合宽 / 适合屏")
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection("5. 进度管理")
                Text("• 每本漫画的当前页码会自动保存，重启后恢复")
                Text("• 菜单中可点击 ☆ 添加书签")
                Text("• 「跳转」可输入页码直接定位")
                Text("• 「缩略图」可显示所有页预览，点击跳转")
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection("6. 格式支持")
                Text("• 压缩包：CBZ/ZIP、CBR/RAR、CB7/7Z、CBT/TAR")
                Text("• 文档：EPUB、PDF")
                Text("• 图片文件夹：JPG / PNG / WebP / GIF / HEIC / BMP")
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection("7. 隐私")
                Text("• 网络权限仅用于元数据刮削、WebDAV同步等可选功能")
                Text("• 默认不会上传任何阅读记录或文件")
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection("8. 实验性功能")
                Text("• SMB / NAS 流式阅读：入口已预留，目前仅支持浏览 HTTP 文件列表，还不能直接打开漫画")
                Text("• WebDAV 同步：可配置服务器并测试连接，但尚未实现完整的数据上传/下载/自动同步")
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("关闭") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    onClose()
                    onOpenFullHelp()
                }) { Text("查看完整说明") }
                TextButton(onClick = onDontShowAgain) { Text("不再显示") }
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
        title = { Text("使用说明") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                HelpSection("1. 书架")
                Text("• 首次进入后，点击右上角 + 选择一个文件夹作为书架目录")
                Text("• 根目录下的每个子文件夹会被视作一本漫画")
                Text("• 子文件夹内的多个 PDF / EPUB / CBZ 等按名称自然排序作为章节")
                Text("• 长按漫画卡片可标记为收藏")
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection("2. 阅读")
                Text("• 点击屏幕中央唤出/关闭菜单")
                Text("• 点击屏幕左/右区域翻页（RTL 日漫模式下方向相反）")
                Text("• 双指捏合可自由缩放（仅在「自由缩放」模式下）")
                Text("• 平板/横屏下，菜单会自动变成左右两侧栏，方便预览中间内容")
                Text("• 屏幕左边缘向右滑动可返回上一级")
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection("3. 阅读模式")
                Text("• 页模式：默认翻页模式，可切换单页 / 双页 / 日漫 RTL")
                Text("• 垂直滚动：适合韩式条漫、国产条漫")
                Text("• 水平滚动：长条横向漫画")
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection("4. 画面优化")
                Text("• 裁白边：扫描类漫画自动切除多余白边")
                Text("• 亮度 / 对比 / 伽马：调整图像观感")
                Text("• 黑白 / 锐化：改善老旧扫描件")
                Text("• 旋转 / 镜像：调整方向")
                Text("• 缩放适配：自由缩放 / 适合宽 / 适合屏")
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection("5. 进度管理")
                Text("• 每本漫画的当前页码会自动保存，重启后恢复")
                Text("• 菜单中可点击 ☆ 添加书签")
                Text("• 「跳转」可输入页码直接定位")
                Text("• 「缩略图」可显示所有页预览，点击跳转")
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection("6. 格式支持")
                Text("• 压缩包：CBZ/ZIP、CBR/RAR、CB7/7Z、CBT/TAR")
                Text("• 文档：EPUB、PDF")
                Text("• 图片文件夹：JPG / PNG / WebP / GIF / HEIC / BMP")
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection("7. 隐私")
                Text("• 网络权限仅用于元数据刮削、WebDAV同步等可选功能")
                Text("• 默认不会上传任何阅读记录或文件")
                Spacer(modifier = Modifier.height(8.dp))

                HelpSection("8. 实验性功能")
                Text("• SMB / NAS 流式阅读：入口已预留，目前仅支持浏览 HTTP 文件列表，还不能直接打开漫画")
                Text("• WebDAV 同步：可配置服务器并测试连接，但尚未实现完整的数据上传/下载/自动同步")
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("关闭") }
        },
        dismissButton = {
            TextButton(onClick = onResetHelpPreference) { Text("重置引导") }
        }
    )
}
