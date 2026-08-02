package com.mangareader.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.core.content.FileProvider
import com.mangareader.Constants
import com.mangareader.R
import java.io.File
import java.io.FileOutputStream
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun CrashLogDialog(
    logs: List<String>,
    onDismiss: () -> Unit
) {
    if (logs.isEmpty()) return
    val context = LocalContext.current
    val logText = logs.joinToString("\n\n---\n\n")

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.BugReport, contentDescription = stringResource(R.string.crash_log_title)) },
        title = { Text(stringResource(R.string.crash_log_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.crash_log_message),
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
                        text = logText,
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
                    stringResource(R.string.crash_log_suggestion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("错误日志", logText))
                    Toast.makeText(context, context.getString(R.string.crash_log_copied_toast), Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.crash_log_copy))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = {
                    try {
                        val logDir = File(context.cacheDir, "crash_logs").apply { mkdirs() }
                        val file = File(logDir, "crash_logs_export_${System.currentTimeMillis()}.txt")
                        FileOutputStream(file).use { out ->
                            out.write(logText.toByteArray(Charsets.UTF_8))
                        }
                        val uri = FileProvider.getUriForFile(
                            context,
                            context.packageName + Constants.FILE_PROVIDER_AUTHORITY_SUFFIX,
                            file
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_log_export_subject))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, context.getString(R.string.crash_log_export_chooser)))
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.crash_log_export_failed_toast, e.message ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(stringResource(R.string.crash_log_export))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
            }
        }
    )
}
