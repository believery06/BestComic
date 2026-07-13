package com.mangareader.utils

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 崩溃日志展示对话框。
 * 在应用启动时检测上次是否有异常退出，若有则展示日志供用户查看和反馈。
 */
@Composable
fun CrashLogDialog(
    logs: List<String>,
    onDismiss: () -> Unit
) {
    if (logs.isEmpty()) return

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
        title = { Text("上次使用中出现异常退出") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "检测到以下异常日志，可能有助于排查问题：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                ) {
                    Text(
                        text = logs.joinToString("\n\n---\n\n"),
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "建议重启应用。如问题持续出现，请联系作者反馈。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("确定") }
        }
    )
}
