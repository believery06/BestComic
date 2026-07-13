package com.mangareader.bookshelf

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicType
import com.mangareader.data.SettingsRepository
import com.mangareader.parser.FolderParser
import com.mangareader.parser.ParserFactory
import com.mangareader.utils.CoverExtractor
import com.mangareader.utils.FileListHelper
import com.mangareader.utils.ParallelCoverExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class BookshelfViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)

    data class UiState(
        val rootUri: Uri? = null,
        val currentUri: Uri? = null,
        val pathStack: List<Uri> = emptyList(),
        val comics: List<ComicEntry> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val progressMap: Map<String, Pair<Int, Int>> = emptyMap(),
        val recentReads: Map<String, Long> = emptyMap()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null
    private var coverJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val storedRoot = settingsRepository.getBookshelfRoot().first()
            if (storedRoot != null) {
                val uri = Uri.parse(storedRoot)
                loadBookshelf(uri, resetStack = true)
            }
        }
        // 收集所有漫画的阅读进度，用于计算阅读状态
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.getAllProgress().collect { progressMap ->
                _uiState.value = _uiState.value.copy(progressMap = progressMap)
            }
        }
        // 收集最近阅读时间戳，用于书架「最近阅读」排序
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.getRecentReads().collect { recentReads ->
                _uiState.value = _uiState.value.copy(recentReads = recentReads)
            }
        }
    }

    fun getReadingStatus(uri: Uri): com.mangareader.data.ReadingStatus {
        val (progress, total) = _uiState.value.progressMap[uri.toString()] ?: return com.mangareader.data.ReadingStatus.UNREAD
        return when {
            total <= 0 || progress <= 0 -> com.mangareader.data.ReadingStatus.UNREAD
            progress >= total - 1 -> com.mangareader.data.ReadingStatus.READ
            else -> com.mangareader.data.ReadingStatus.READING
        }
    }

    fun markAsRead(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (uri in uris) {
                val total = _uiState.value.progressMap[uri.toString()]?.second ?: 0
                settingsRepository.markAsRead(uri, total.coerceAtLeast(1))
            }
        }
    }

    fun markAsUnread(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (uri in uris) {
                settingsRepository.markAsUnread(uri)
            }
        }
    }

    fun removeComics(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            for (uri in uris) {
                try {
                    when (uri.scheme) {
                        "file" -> {
                            val file = File(uri.path ?: continue)
                            if (file.isDirectory) file.deleteRecursively() else file.delete()
                        }
                        "content" -> {
                            val doc = DocumentFile.fromTreeUri(ctx, uri)
                                ?: DocumentFile.fromSingleUri(ctx, uri)
                            doc?.delete()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            FileListHelper.clearCache()
            _uiState.value.currentUri?.let { loadBookshelf(it) }
        }
    }

    fun setRoot(rootUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.saveBookshelfRoot(rootUri.toString())
            loadBookshelf(rootUri, resetStack = true)
        }
    }

    fun refresh() {
        _uiState.value.currentUri?.let { loadBookshelf(it) }
    }

    /**
     * 进入指定目录（书架文件系统层级浏览）。
     * 防止因符号链接/循环目录结构导致的无限嵌套。
     */
    fun openDirectory(dirUri: Uri) {
        val current = _uiState.value.currentUri
        val dirKey = dirUri.toString()
        // 当前目录或已访问过的目录禁止再次进入
        if (current != null && current.toString() == dirKey) return
        if (_uiState.value.pathStack.any { it.toString() == dirKey }) return
        val newStack = current?.let { _uiState.value.pathStack + it } ?: _uiState.value.pathStack
        loadBookshelf(dirUri, newStack = newStack)
    }

    /**
     * 返回上一级目录。
     */
    fun navigateUp(): Boolean {
        val stack = _uiState.value.pathStack
        if (stack.isEmpty()) return false
        val parent = stack.last()
        loadBookshelf(parent, newStack = stack.dropLast(1))
        return true
    }

    /**
     * 回到书架根目录。
     */
    fun navigateToRoot() {
        _uiState.value.rootUri?.let { loadBookshelf(it, resetStack = true) }
    }

    fun loadBookshelf(targetUri: Uri, resetStack: Boolean = false, newStack: List<Uri>? = null) {
        loadJob?.cancel()
        coverJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val root = if (resetStack) targetUri else _uiState.value.rootUri
            val stack = when {
                resetStack -> emptyList()
                newStack != null -> newStack
                else -> _uiState.value.pathStack
            }
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                rootUri = root,
                currentUri = targetUri,
                pathStack = stack,
                error = null
            )
            try {
                runCatching { ensureNoMediaFile(targetUri) }

                val comics = when (targetUri.scheme) {
                    "file", "content" -> loadDirectory(targetUri)
                    else -> emptyList()
                }
                _uiState.value = _uiState.value.copy(
                    comics = comics.sortedWith { a, b -> FolderParser.compareNames(a.title, b.title) },
                    isLoading = false,
                    error = null
                )

                // Extract covers asynchronously for items that don't have one yet
                extractCovers(comics)
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "未知错误"
                )
            }
        }
    }

    private suspend fun loadDirectory(rootUri: Uri): List<ComicEntry> {
        val items = mutableListOf<ComicEntry>()
        val children = FileListHelper.listChildren(getApplication(), rootUri)

        for (child in children) {
            if (child.name.startsWith(".")) continue

            if (child.isDirectory) {
                val (hasChapter, hasImage) = inspectDirectory(child.uri)
                if (hasChapter || hasImage) {
                    val type = if (hasChapter) ComicType.COMIC_BOOK else ComicType.FOLDER
                    items.add(
                        ComicEntry(
                            uri = child.uri,
                            title = child.name,
                            type = type,
                            coverUri = null,
                            isDirectory = true,
                            path = child.uri.toString()
                        )
                    )
                }
            } else {
                val type = ParserFactory.detectType(child.name)
                if (type != ComicType.UNKNOWN) {
                    items.add(
                        ComicEntry(
                            uri = child.uri,
                            title = child.name,
                            type = type,
                            coverUri = null,
                            isDirectory = false,
                            path = child.uri.toString()
                        )
                    )
                }
            }
        }

        // 如果当前目录本身包含图片文件（但没有子文件夹或子文件夹无漫画），
        // 将当前目录本身作为一个可直接打开的漫画条目（不再允许点击进入自身）
        if (items.isEmpty()) {
            val hasImages = children.any { !it.isDirectory && ParserFactory.isImageFile(it.name) }
            if (hasImages) {
                items.add(
                    ComicEntry(
                        uri = rootUri,
                        title = rootUri.lastPathSegment?.substringAfterLast('/') ?: "当前目录",
                        type = ComicType.FOLDER,
                        coverUri = null,
                        isDirectory = false,
                        path = rootUri.toString()
                    )
                )
            }
        }

        return items
    }

    /**
     * Extract covers for all comics in the background and update the UI
     * as each cover becomes available.
     */
    private fun extractCovers(comics: List<ComicEntry>) {
        coverJob?.cancel()
        val app = getApplication<android.app.Application>()
        val needExtraction = comics.filter { it.coverUri == null }
        if (needExtraction.isEmpty()) return

        coverJob = viewModelScope.launch(Dispatchers.IO) {
            val updated = comics.toMutableList()

            ParallelCoverExtractor.extractCoversParallel(app, needExtraction) { entry, coverUri ->
                if (coverUri != null) {
                    synchronized(updated) {
                        val idx = updated.indexOfFirst { it.uri == entry.uri }
                        if (idx >= 0) {
                            updated[idx] = updated[idx].copy(coverUri = coverUri)
                            _uiState.value = _uiState.value.copy(comics = updated.toList())
                        }
                    }
                }
            }
        }
    }

    private fun inspectDirectory(dirUri: Uri): Pair<Boolean, Boolean> {
        val children = FileListHelper.listChildren(getApplication(), dirUri)
        var hasChapter = false
        var hasImage = false
        for (child in children) {
            if (child.isDirectory) continue
            val type = ParserFactory.detectType(child.name)
            if (type != ComicType.UNKNOWN) hasChapter = true
            if (ParserFactory.isImageFile(child.name)) hasImage = true
            if (hasChapter) break
        }
        return Pair(hasChapter, hasImage)
    }

    private suspend fun findCover(dirUri: Uri, type: ComicType): Uri? {
        val children = FileListHelper.listChildren(getApplication(), dirUri)
            .filter { !it.isDirectory }
            .sortedWith { a, b -> FolderParser.compareNames(a.name, b.name) }

        if (type == ComicType.FOLDER) {
            return children.firstOrNull { ParserFactory.isImageFile(it.name) }?.uri
        }

        if (type == ComicType.COMIC_BOOK) {
            val firstChapter = children.firstOrNull {
                ParserFactory.detectType(it.name) != ComicType.UNKNOWN
            } ?: return null
            val chapterType = ParserFactory.detectType(firstChapter.name)
            return CoverExtractor.extractCover(getApplication(), firstChapter.uri, chapterType)
        }

        return null
    }

    private fun ensureNoMediaFile(rootUri: Uri) {
        val ctx = getApplication<android.app.Application>()
        when (rootUri.scheme) {
            "file" -> {
                val file = File(rootUri.path ?: return, ".nomedia")
                if (!file.exists()) {
                    runCatching { file.createNewFile() }
                }
            }
            "content" -> {
                val doc = DocumentFile.fromTreeUri(ctx, rootUri) ?: return
                if (!doc.listFiles().any { it.name == ".nomedia" }) {
                    runCatching {
                        DocumentsContract.createDocument(
                            ctx.contentResolver,
                            doc.uri,
                            "application/octet-stream",
                            ".nomedia"
                        )
                    }
                }
            }
        }
    }
}