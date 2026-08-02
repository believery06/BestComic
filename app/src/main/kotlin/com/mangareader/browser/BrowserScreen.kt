package com.mangareader.browser

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mangareader.R
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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { com.mangareader.data.SettingsRepository.getInstance(context) }
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
        uri?.let { selectedUri ->
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val permissionTaken = runCatching {
                context.contentResolver.takePersistableUriPermission(selectedUri, flags)
                // 验证持久权限是否真正被授予（某些 ROM 可能不支持持久化）
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == selectedUri && it.isReadPermission && it.isWritePermission
                }
            }.getOrDefault(false)

            if (permissionTaken) {
                viewModel.openFolder(selectedUri)
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.browser_saf_permission_failed)
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 不再使用 MANAGE_EXTERNAL_STORAGE，改用 SAF 系统文件浏览器
            permissionDenied = false
        } else {
            if (PermissionUtils.hasStoragePermission(context)) {
                viewModel.listRootStorage()
            } else {
                storageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(uiState.title) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.currentUri != null) {
                            viewModel.navigateUp()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                            Text(stringResource(R.string.browser_open_as_comic), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    TextButton(onClick = {
                        safLauncher.launch(savedBookshelfUri)
                    }) {
                        Text(stringResource(R.string.browser_system_file_browser), color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (permissionDenied) {
                // Android 11+ 不再申请广泛存储权限，直接引导使用 SAF
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    SafRationale(onOpenSaf = { safLauncher.launch(savedBookshelfUri) })
                } else {
                    PermissionRationale(
                        onGrant = { storageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE) },
                        onCheck = {
                            if (PermissionUtils.hasStoragePermission(context)) {
                                permissionDenied = false
                                viewModel.listRootStorage()
                            }
                        }
                    )
                }
            } else if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.error != null) {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.browser_unknown_error),
                    onRetry = { viewModel.refreshCurrentDir() },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (uiState.entries.isEmpty()) {
                EmptyState(modifier = Modifier.align(Alignment.Center))
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
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.browser_empty_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.browser_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.browser_error_retry)) }
    }
}

@Composable
private fun EntryItem(entry: ComicEntry, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(entry.title) },
        supportingContent = {
            Text(
                if (entry.isDirectory) stringResource(R.string.browser_type_folder) else when (entry.type) {
                    ComicType.CBZ -> stringResource(R.string.browser_type_cbz)
                    ComicType.CBR -> stringResource(R.string.browser_type_cbr)
                    ComicType.CB7 -> stringResource(R.string.browser_type_cb7)
                    ComicType.CBT -> stringResource(R.string.browser_type_cbt)
                    ComicType.EPUB -> stringResource(R.string.browser_type_epub)
                    ComicType.PDF -> stringResource(R.string.browser_type_pdf)
                    ComicType.MOBI -> stringResource(R.string.browser_type_mobi)
                    ComicType.FOLDER -> stringResource(R.string.browser_type_image_folder)
                    ComicType.COMIC_BOOK -> stringResource(R.string.browser_type_comic_book)
                    ComicType.UNKNOWN -> stringResource(R.string.browser_type_unknown)
                }
            )
        },
        leadingContent = {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = if (entry.isDirectory) stringResource(R.string.browser_entry_icon_folder) else stringResource(R.string.browser_entry_icon_file)
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
        Text(stringResource(R.string.browser_permission_message), style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onGrant) { Text(stringResource(R.string.browser_permission_grant)) }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onCheck) { Text(stringResource(R.string.browser_permission_refresh)) }
    }
}

@Composable
private fun SafRationale(onOpenSaf: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.browser_saf_message), style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onOpenSaf) { Text(stringResource(R.string.browser_saf_open)) }
    }
}
