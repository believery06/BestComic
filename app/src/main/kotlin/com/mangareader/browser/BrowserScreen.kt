package com.mangareader.browser

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicType
import com.mangareader.utils.PermissionUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onOpenComic: (ComicEntry) -> Unit,
    onBack: () -> Unit = {},
    viewModel: BrowserViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var permissionDenied by remember { mutableStateOf(false) }
    val settingsRepo = remember { com.mangareader.data.SettingsRepository(context) }
    val savedRootStr by settingsRepo.getBookshelfRoot().collectAsStateWithLifecycle(initialValue = null)
    val savedBookshelfUri = savedRootStr?.let { runCatching { Uri.parse(it) }.getOrNull() }

    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDenied = false
            viewModel.listRootStorage()
        } else {
            permissionDenied = true
        }
    }

    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.openFolder(it)
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                viewModel.listRootStorage()
            } else {
                permissionDenied = true
            }
        } else {
            if (PermissionUtils.hasStoragePermission(context)) {
                viewModel.listRootStorage()
            } else {
                storageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.title) },
                navigationIcon = {
                    if (uiState.currentUri != null) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (uiState.currentUri != null) {
                        TextButton(onClick = {
                            uiState.currentUri?.let { uri ->
                                // 根据目录内容自动判断类型：含章节文件用 COMIC_BOOK，纯图片用 FOLDER
                                val type = viewModel.detectDirectoryType(uri)
                                onOpenComic(
                                    ComicEntry(
                                        uri = uri,
                                        title = uiState.title,
                                        type = type,
                                        isDirectory = true,
                                        path = uri.toString()
                                    )
                                )
                            }
                        }) {
                            Text("打开为漫画", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    TextButton(onClick = {
                        safLauncher.launch(savedBookshelfUri)
                    }) {
                        Text("系统文件浏览", color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
                    if (permissionDenied) {
                PermissionRationale(
                    onGrant = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                context.startActivity(intent)
                            }
                        } else {
                            storageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    },
                    onCheck = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            if (Environment.isExternalStorageManager()) {
                                permissionDenied = false
                                viewModel.listRootStorage()
                            }
                        } else {
                            if (PermissionUtils.hasStoragePermission(context)) {
                                permissionDenied = false
                                viewModel.listRootStorage()
                            }
                        }
                    }
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(uiState.entries, key = { it.uri.toString() }) { entry ->
                        EntryItem(entry = entry, onClick = {
                            if (entry.isDirectory) {
                                viewModel.openFolder(entry.uri)
                            } else {
                                onOpenComic(entry)
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryItem(entry: ComicEntry, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(entry.title) },
        supportingContent = {
            Text(
                if (entry.isDirectory) "文件夹" else when (entry.type) {
                    ComicType.CBZ -> "CBZ 漫画"
                    ComicType.CBR -> "CBR 漫画"
                    ComicType.CB7 -> "CB7 漫画"
                    ComicType.CBT -> "CBT 漫画"
                    ComicType.EPUB -> "EPUB 电子书"
                    ComicType.PDF -> "PDF 文档"
                    ComicType.FOLDER -> "图片文件夹"
                    ComicType.COMIC_BOOK -> "漫画系列"
                    ComicType.UNKNOWN -> "未知"
                }
            )
        },
        leadingContent = {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = null
            )
        }
    )
}

@Composable
private fun PermissionRationale(onGrant: () -> Unit, onCheck: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("需要所有文件访问权限才能浏览本地漫画", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onGrant) { Text("去授权") }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onCheck) { Text("已授权，刷新") }
    }
}
