package com.mangareader.reader

import android.net.Uri
import com.mangareader.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 书签管理器。
 *
 * 负责内存中书签列表的维护、与 [SettingsRepository] 的持久化同步，
 * 以及退出阅读时的自动恢复书签创建。
 * 从 [ReaderViewModel] 拆分出来，让 ViewModel 专注于阅读状态流转。
 */
class BookmarkManager(
    private val externalScope: CoroutineScope,
    private val settingsRepository: SettingsRepository
) {

    private val _bookmarks = MutableStateFlow<List<Int>>(emptyList())
    val bookmarks: StateFlow<List<Int>> = _bookmarks.asStateFlow()

    private var observeJob: Job? = null
    private var writeJob: Job? = null
    private var currentUri: Uri? = null

    /**
     * 切换当前漫画 URI，并订阅该 URI 对应的书签变化。
     * 切换时会先取消旧订阅，避免旧漫画的书签更新误写入当前状态。
     */
    fun setComicUri(uri: Uri?) {
        observeJob?.cancel()
        currentUri = uri
        _bookmarks.value = emptyList()
        if (uri == null) return
        observeJob = externalScope.launch {
            try {
                settingsRepository.getBookmarks().collect { map ->
                    val list = map[uri.toString()] ?: emptyList()
                    _bookmarks.value = list
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 订阅失败时保持空列表，不影响阅读
            }
        }
    }

    /** 用指定列表覆盖内存中的书签，通常用于漫画加载完成后的初始设置。 */
    fun reset(bookmarks: List<Int>) {
        _bookmarks.value = bookmarks
    }

    /** 清空内存中的书签列表。 */
    fun clear() {
        _bookmarks.value = emptyList()
    }

    /** 切换指定页的书签状态。 */
    fun toggleBookmarkAt(page: Int) {
        val uri = currentUri ?: return
        val current = _bookmarks.value
        val currentlyBookmarked = current.contains(page)
        val newBookmarks = if (currentlyBookmarked) {
            current.filter { it != page }
        } else {
            (current + page).sorted()
        }
        _bookmarks.value = newBookmarks
        enqueueWrite {
            if (currentlyBookmarked) {
                settingsRepository.removeBookmark(uri, page)
            } else {
                settingsRepository.addBookmark(uri, page)
            }
        }
    }

    /** 删除指定页的书签。 */
    fun removeBookmark(page: Int) {
        val uri = currentUri ?: return
        val current = _bookmarks.value
        if (!current.contains(page)) return
        _bookmarks.value = current.filter { it != page }
        enqueueWrite { settingsRepository.removeBookmark(uri, page) }
    }

    /**
     * 退出阅读时自动保存书签：若当前页没有书签且已阅读超过 3 页，则自动创建恢复书签。
     * 本方法只更新内存中的书签列表，真正的磁盘写入统一由 [persist] 完成。
     */
    fun autoSaveBookmarkOnExit(currentPage: Int, totalPages: Int) {
        val current = _bookmarks.value
        if (current.contains(currentPage)) return
        if (totalPages > 3 && currentPage > 0 && currentPage < totalPages - 1) {
            _bookmarks.value = current + currentPage
        }
    }

    /**
     * 将内存中的最终书签列表完整同步到磁盘。
     * 应在 ViewModel 生命周期末尾调用，配合 applicationScope 避免取消导致写入丢失。
     */
    suspend fun persist() {
        val uri = currentUri ?: return
        val list = _bookmarks.value
        try {
            settingsRepository.saveBookmarks(uri, list)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 退出时书签保存失败不应崩溃
        }
    }

    private fun enqueueWrite(block: suspend () -> Unit) {
        writeJob?.cancel()
        writeJob = externalScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 单次写入失败不阻断后续操作；下次切换会重新订阅
            }
        }
    }
}
