package com.mangareader.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mangareader.R
import com.mangareader.cache.CacheCleaner
import com.mangareader.cache.PageCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 缓存管理页面：展示各类缓存占用，支持按类型清理。
 * 不会清理书签、收藏、最近阅读、阅读进度等用户数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheManagementScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pageCacheSize by remember { mutableStateOf(0L) }
    var thumbCacheSize by remember { mutableStateOf(0L) }
    var shareCardSize by remember { mutableStateOf(0L) }
    var parserTempSize by remember { mutableStateOf(0L) }
    var isClearing by remember { mutableStateOf(false) }

    suspend fun refreshSizes() {
        val (page, thumb, share, parser) = withContext(Dispatchers.IO) {
            val cacheDir = context.cacheDir
            val page = getDirSize(File(cacheDir, "page_cache"))
            val thumb = getDirSize(File(cacheDir, "thumb_cache"))
            val share = getDirSize(File(cacheDir, "share_cards"))
            val parser = cacheDir.listFiles { f ->
                f.name.startsWith("cbt_") || f.name.startsWith("cb7_") || f.name.startsWith("zip_prov_") ||
                f.name.startsWith("epub_") || f.name.startsWith("pdf_") || f.name.startsWith("tmp_") ||
                f.name.startsWith("temp_") || f.name.startsWith("cbr_temp_") || f.name.startsWith("cbt_temp_") ||
                f.name.startsWith("cbz_") || f.name.startsWith("cbr_") || f.name.startsWith("cbt_index_") ||
                f.name == "source_share" || f.name == "crash_logs"
            }?.sumOf {
                if (it.isDirectory) CacheCleaner.calcSize(it) else it.length()
            } ?: 0L
            listOf(page, thumb, share, parser)
        }
        pageCacheSize = page
        thumbCacheSize = thumb
        shareCardSize = share
        parserTempSize = parser
    }

    fun clearCache(block: suspend () -> Unit) {
        isClearing = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } finally {
                refreshSizes()
                isClearing = false
            }
        }
    }

    LaunchedEffect(Unit) { refreshSizes() }

    fun formatSize(bytes: Long): String = context.formatSize(bytes)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cache_management_title)) },
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
            Text(
                stringResource(R.string.cache_usage_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            CacheItem(
                label = stringResource(R.string.cache_page_cache),
                description = stringResource(R.string.cache_page_cache_desc),
                size = formatSize(pageCacheSize),
                icon = Icons.Default.Image,
                contentDescription = stringResource(R.string.cache_page_cache),
                enabled = !isClearing,
                onClear = {
                    clearCache { PageCacheManager.getInstance(context).clearPageDisk() }
                }
            )
            CacheItem(
                label = stringResource(R.string.cache_thumb_cache),
                description = stringResource(R.string.cache_thumb_cache_desc),
                size = formatSize(thumbCacheSize),
                icon = Icons.Default.PhotoLibrary,
                contentDescription = stringResource(R.string.cache_thumb_cache),
                enabled = !isClearing,
                onClear = {
                    clearCache { PageCacheManager.getInstance(context).clearThumbDisk() }
                }
            )
            CacheItem(
                label = stringResource(R.string.cache_share_card),
                description = stringResource(R.string.cache_share_card_desc),
                size = formatSize(shareCardSize),
                icon = Icons.Default.Share,
                contentDescription = stringResource(R.string.cache_share_card),
                enabled = !isClearing,
                onClear = {
                    clearCache { File(context.cacheDir, "share_cards").listFiles()?.forEach { it.deleteRecursively() } }
                }
            )
            CacheItem(
                label = stringResource(R.string.cache_parser_temp),
                description = stringResource(R.string.cache_parser_temp_desc),
                size = formatSize(parserTempSize),
                icon = Icons.Default.FolderOpen,
                contentDescription = stringResource(R.string.cache_parser_temp),
                enabled = !isClearing,
                onClear = {
                    clearCache {
                        val cacheDir = context.cacheDir
                        cacheDir.listFiles { f ->
                            f.name.startsWith("cbt_") || f.name.startsWith("cb7_") || f.name.startsWith("zip_prov_") ||
                            f.name.startsWith("epub_") || f.name.startsWith("pdf_") || f.name.startsWith("tmp_") ||
                            f.name.startsWith("cbz_") || f.name.startsWith("cbr_") ||
                            f.name == "source_share" || f.name == "crash_logs"
                        }?.forEach { it.deleteRecursively() }
                    }
                }
            )

            val total = pageCacheSize + thumbCacheSize + shareCardSize + parserTempSize
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    clearCache {
                        // 页面与缩略图磁盘缓存必须通过 DiskLruCache 管理，禁止直接 deleteRecursively
                        PageCacheManager.getInstance(context).clear()
                        context.cacheDir.listFiles { f ->
                            f.name == "share_cards" || f.name == "source_share" || f.name == "crash_logs" ||
                            f.name.startsWith("cbt_") || f.name.startsWith("cb7_") ||
                            f.name.startsWith("zip_prov_") || f.name.startsWith("epub_") || f.name.startsWith("pdf_") ||
                            f.name.startsWith("tmp_") || f.name.startsWith("cbz_") || f.name.startsWith("cbr_")
                        }?.forEach { it.deleteRecursively() }
                    }
                },
                enabled = !isClearing && total > 0,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.cache_clear_all, formatSize(total)))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cache_clear_all, formatSize(total)))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CacheItem(
    label: String,
    description: String,
    size: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClear: () -> Unit
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Icon(icon, contentDescription = contentDescription) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(size, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onClear, enabled = enabled) {
                    Text(stringResource(R.string.cache_clear))
                }
            }
        }
    )
}

private fun getDirSize(dir: File): Long {
    if (!dir.exists()) return 0L
    return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

private fun Context.formatSize(bytes: Long): String {
    if (bytes <= 0L) return getString(R.string.cache_size_format_b, 0)
    val gb = 1024 * 1024 * 1024
    val mb = 1024 * 1024
    val kb = 1024
    return when {
        bytes >= gb -> getString(R.string.cache_size_format_gb, bytes / gb.toFloat())
        bytes >= mb -> getString(R.string.cache_size_format_mb, bytes / mb.toFloat())
        bytes >= kb -> getString(R.string.cache_size_format_kb, bytes / kb.toFloat())
        else -> getString(R.string.cache_size_format_b, bytes.toInt())
    }
}
