package com.mangareader.browser

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mangareader.R
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicType
import com.mangareader.parser.FolderParser
import com.mangareader.parser.ParserFactory
import com.mangareader.utils.FileListHelper
import com.mangareader.utils.FileManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    @Immutable
    data class UiState(
        val entries: List<ComicEntry> = emptyList(),
        val currentPath: String = "",
        val currentUri: Uri? = null,
        val title: String = "",
        val isLoading: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // 浏览历史可能在主线程（navigateUp）与 IO 线程（openFolder）并发访问，使用同步列表。
    private val history = Collections.synchronizedList(mutableListOf<Uri>())

    companion object {
        private const val TAG = "BrowserViewModel"
    }

    fun openFolder(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                if (history.isEmpty() || history.last() != uri) {
                    history.add(uri)
                }
                val entries = withContext(Dispatchers.IO) {
                    when (uri.scheme) {
                        "file" -> listFileFolder(uri)
                        "content" -> listFileFolder(uri)
                        else -> emptyList()
                    }
                }
                val displayName = uri.lastPathSegment ?: uri.toString()
                _uiState.value = _uiState.value.copy(
                    entries = entries,
                    currentPath = uri.toString(),
                    currentUri = uri,
                    title = displayName,
                    isLoading = false
                )
            } catch (e: CancellationException) {
                // 快速导航时旧任务被取消，不应覆盖新任务的 UI 状态
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "打开文件夹失败", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: getApplication<Application>().getString(R.string.browser_unknown_error)
                )
            }
        }
    }

    fun navigateUp() {
        if (history.size <= 1) return
        history.removeLastOrNull()
        val parent = history.lastOrNull() ?: return
        openFolder(parent)
    }

    fun refreshCurrentDir() {
        history.lastOrNull()?.let { openFolder(it) }
    }

    fun listRootStorage() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                history.clear()

                val entries = withContext(Dispatchers.IO) {
                    listStorageRoots()
                }

                val rootTitle = getApplication<Application>().getString(R.string.browser_storage_root)
                _uiState.value = _uiState.value.copy(
                    entries = entries,
                    currentPath = rootTitle,
                    currentUri = null,
                    title = rootTitle,
                    isLoading = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "列出存储根目录失败", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: getApplication<Application>().getString(R.string.browser_unknown_error)
                )
            }
        }
    }

    private fun listFileFolder(uri: Uri): List<ComicEntry> {
        val items = mutableListOf<ComicEntry>()
        val children = FileListHelper.listChildren(getApplication(), uri)

        for (child in children) {
            if (child.isDirectory) {
                val subType = detectDirectoryType(child.uri)
                items.add(
                    ComicEntry(
                        uri = child.uri,
                        title = child.name,
                        type = subType,
                        isDirectory = true,
                        path = child.uri.toString()
                    )
                )
            } else {
                val type = ParserFactory.detectType(child.name)
                if (type != ComicType.UNKNOWN) {
                    items.add(
                        ComicEntry(
                            uri = child.uri,
                            title = child.name,
                            type = type,
                            isDirectory = false,
                            path = child.uri.toString()
                        )
                    )
                }
            }
        }
        return items.sortedWith { a, b ->
            if (a.isDirectory != b.isDirectory) {
                if (a.isDirectory) -1 else 1
            } else {
                FolderParser.compareNames(a.title, b.title)
            }
        }
    }

    fun detectDirectoryType(dirUri: Uri): ComicType {
        return detectDirectoryTypeRecursive(dirUri, 0)
    }

    private fun detectDirectoryTypeRecursive(dirUri: Uri, depth: Int): ComicType {
        if (depth > 3) return ComicType.FOLDER
        val children = FileListHelper.listChildren(getApplication(), dirUri)
        for (child in children) {
            if (child.isDirectory) {
                val subType = detectDirectoryTypeRecursive(child.uri, depth + 1)
                if (subType == ComicType.COMIC_BOOK) return ComicType.COMIC_BOOK
            } else {
                val type = ParserFactory.detectType(child.name)
                if (type != ComicType.UNKNOWN) return ComicType.COMIC_BOOK
                if (ParserFactory.isImageFile(child.name) && depth > 0) {
                    return ComicType.COMIC_BOOK
                }
            }
        }
        // 走到这里说明目录内没有漫画章节文件/子目录；按现有设计统一视为可浏览的 FOLDER。
        return ComicType.FOLDER
    }

    /**
     * 列出设备可用的存储卷根目录。
     * Android 11+ 优先使用 [StorageManager.storageVolumes]，低版本或获取失败时回退到
     * getExternalFilesDirs 并向上遍历文件树推导根目录，避免硬编码 `/Android/data/` 路径。
     */
    private fun listStorageRoots(): List<ComicEntry> {
        val app = getApplication<Application>()
        val list = mutableListOf<ComicEntry>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val storageManager = app.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                storageManager?.storageVolumes?.forEach { volume ->
                    val dir = volume.directory?.let { FileManager.toSafeFile(it.absolutePath) }
                    if (dir != null && dir.exists()) {
                        val title = if (volume.isPrimary) {
                            app.getString(R.string.browser_internal_storage)
                        } else {
                            app.getString(R.string.browser_sd_card)
                        }
                        list.add(
                            ComicEntry(
                                uri = Uri.fromFile(dir),
                                title = title,
                                type = ComicType.FOLDER,
                                isDirectory = true,
                                path = dir.absolutePath
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "StorageManager 获取存储卷失败", e)
            }
        }

        // 回退：遍历 getExternalFilesDirs 推导根目录
        if (list.isEmpty()) {
            val externalDirs = app.getExternalFilesDirs(null).filterNotNull()
            val seen = mutableSetOf<String>()
            for (dir in externalDirs) {
                val root = deriveStorageRoot(dir.absolutePath)
                if (root != null && root.exists() && seen.add(root.absolutePath)) {
                    val isPrimary = seen.size == 1
                    list.add(
                        ComicEntry(
                            uri = Uri.fromFile(root),
                            title = if (isPrimary) app.getString(R.string.browser_internal_storage) else app.getString(R.string.browser_sd_card),
                            type = ComicType.FOLDER,
                            isDirectory = true,
                            path = root.absolutePath
                        )
                    )
                }
            }
        }
        // 最终规范化：对所有根目录路径做 canonical 处理并去重，避免符号链接/`..`
        // 导致同一目录被当作不同卷。
        return list.mapNotNull { entry ->
            FileManager.toSafeFile(entry.path)?.let { it.absolutePath to entry }
        }.distinctBy { it.first }.map { it.second }
    }

    /**
     * 从应用私有目录路径向上遍历，找到包含 Android 目录的存储根目录。
     * 若无法推导则回退到旧的 `/Android/data/` 截断逻辑。
     */
    private fun deriveStorageRoot(path: String): File? {
        var current: File? = FileManager.toSafeFile(path)
        while (current != null && current.exists()) {
            val hasAndroid = current.listFiles()?.any { it.name == "Android" && it.isDirectory } == true
            if (hasAndroid) return current
            current = current.parentFile
        }
        // 回退逻辑：同样使用规范化路径，避免 .. 截断到错误目录。
        return path.substringBefore("/Android/data/").takeIf { it != path }
            ?.let { FileManager.toSafeFile(it) }
            ?.takeIf { it.exists() }
    }
}
