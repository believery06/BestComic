package com.mangareader.browser

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicType
import com.mangareader.parser.FolderParser
import com.mangareader.parser.ParserFactory
import com.mangareader.utils.FileListHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

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

    private val history = mutableListOf<Uri>()

    fun openFolder(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                if (history.isEmpty() || history.last() != uri) {
                    history.add(uri)
                }
                val entries = when (uri.scheme) {
                    "file" -> listFileFolder(uri)
                    "content" -> listFileFolder(uri)
                    else -> emptyList()
                }
                val displayName = uri.lastPathSegment ?: uri.toString()
                _uiState.value = _uiState.value.copy(
                    entries = entries,
                    currentPath = uri.toString(),
                    currentUri = uri,
                    title = displayName,
                    isLoading = false
                )
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "未知错误"
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
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val entries = mutableListOf<ComicEntry>()
                history.clear()

                // Internal storage
                val internal = android.os.Environment.getExternalStorageDirectory()
                entries.add(
                    ComicEntry(
                        uri = Uri.fromFile(internal),
                        title = "内部存储",
                        type = ComicType.FOLDER,
                        isDirectory = true,
                        path = internal.absolutePath
                    )
                )

                // External SD cards
                val externalDirs = getApplication<Application>()
                    .getExternalFilesDirs(null)
                for (dir in externalDirs) {
                    val path = dir.absolutePath
                    val sdPath = path.substringBefore("/Android/data/")
                    if (sdPath != internal.absolutePath) {
                        val sd = File(sdPath)
                        if (sd.exists()) {
                            entries.add(
                                ComicEntry(
                                    uri = Uri.fromFile(sd),
                            title = "SD 卡",
                                    type = ComicType.FOLDER,
                                    isDirectory = true,
                                    path = sdPath
                                )
                            )
                        }
                    }
                }

                _uiState.value = _uiState.value.copy(
                    entries = entries,
                    currentPath = "存储根目录",
                    currentUri = null,
                    title = "存储根目录",
                    isLoading = false
                )
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "未知错误"
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
        val hasImages = children.any { !it.isDirectory && ParserFactory.isImageFile(it.name) }
        return if (hasImages) ComicType.FOLDER else ComicType.FOLDER
    }
}
