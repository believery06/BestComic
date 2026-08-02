package com.mangareader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class BookshelfRepository(private val context: Context) {

    companion object {
        @Volatile
        private var instance: BookshelfRepository? = null

        fun getInstance(context: Context): BookshelfRepository {
            return instance ?: synchronized(this) {
                instance ?: BookshelfRepository(context.applicationContext).also { instance = it }
            }
        }

        private val KEY_BOOKSHELF_ROOT = stringPreferencesKey("bookshelf_root")
        private val KEY_BOOKSHELF_URIS = stringPreferencesKey("bookshelf_uris")
        private val KEY_CURRENT_BOOKSHELF_INDEX = intPreferencesKey("current_bookshelf_index")
        private val KEY_BOOKSHELF_VIEW_MODE = stringPreferencesKey("bookshelf_view_mode")
        private val KEY_BOOKSHELF_FILTER = stringPreferencesKey("bookshelf_filter")
        private val KEY_BOOKSHELF_SORT = stringPreferencesKey("bookshelf_sort")
        private val KEY_BOOKSHELF_SELECTED_CATEGORY = stringPreferencesKey("bookshelf_selected_category")
        private val KEY_BOOKSHELF_SEARCH_QUERY = stringPreferencesKey("bookshelf_search_query")
        private val KEY_BOOKSHELF_SEARCHING = booleanPreferencesKey("bookshelf_searching")
        private val KEY_BOOKSHELF_SEARCH_HISTORY = stringPreferencesKey("bookshelf_search_history")
        private const val MAX_SEARCH_HISTORY_SIZE = 20
        private const val KEY_BOOKSHELF_SCAN_PREFIX = "bookshelf_scan_"

        private val json = Json { ignoreUnknownKeys = true }
    }

    fun getBookshelfRoot(): Flow<String?> = context.userDataStore.data.map { prefs ->
        prefs[KEY_BOOKSHELF_ROOT]
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfRoot(uriString: String) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_BOOKSHELF_ROOT] = uriString
        }
    }

    fun getBookshelfUris(): Flow<List<String>> = context.userDataStore.data.map { prefs ->
        parseBookshelfUris(prefs[KEY_BOOKSHELF_URIS])
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfUris(uris: List<String>) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_BOOKSHELF_URIS] = uris.joinToString("|")
        }
    }

    suspend fun addBookshelfUri(uriString: String) {
        context.userDataStore.edit { prefs ->
            val list = parseBookshelfUris(prefs[KEY_BOOKSHELF_URIS]).toMutableList()
            if (!list.contains(uriString)) list.add(uriString)
            prefs[KEY_BOOKSHELF_URIS] = list.joinToString("|")
        }
    }

    suspend fun removeBookshelfUri(uriString: String) {
        context.userDataStore.edit { prefs ->
            val list = parseBookshelfUris(prefs[KEY_BOOKSHELF_URIS]).filter { it != uriString }
            prefs[KEY_BOOKSHELF_URIS] = list.joinToString("|")
        }
    }

    fun getCurrentBookshelfIndex(): Flow<Int> = context.userDataStore.data.map { prefs ->
        prefs[KEY_CURRENT_BOOKSHELF_INDEX] ?: 0
    }.flowOn(Dispatchers.IO)

    suspend fun saveCurrentBookshelfIndex(index: Int) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_CURRENT_BOOKSHELF_INDEX] = index
        }
    }

    private fun parseBookshelfUris(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split("|").filter { it.isNotEmpty() }
    }

    fun getBookshelfViewMode(): Flow<String> = context.userDataStore.data.map { prefs ->
        prefs[KEY_BOOKSHELF_VIEW_MODE] ?: "GRID"
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfViewMode(mode: String) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_BOOKSHELF_VIEW_MODE] = mode
        }
    }

    fun getBookshelfFilter(): Flow<String> = context.userDataStore.data.map { prefs ->
        prefs[KEY_BOOKSHELF_FILTER] ?: "ALL"
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfFilter(filter: String) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_BOOKSHELF_FILTER] = filter
        }
    }

    fun getBookshelfSort(): Flow<String> = context.userDataStore.data.map { prefs ->
        prefs[KEY_BOOKSHELF_SORT] ?: "NAME"
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfSort(sort: String) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_BOOKSHELF_SORT] = sort
        }
    }

    fun getBookshelfSelectedCategory(): Flow<String?> = context.userDataStore.data.map { prefs ->
        prefs[KEY_BOOKSHELF_SELECTED_CATEGORY]
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfSelectedCategory(category: String?) {
        context.userDataStore.edit { prefs ->
            if (category != null) {
                prefs[KEY_BOOKSHELF_SELECTED_CATEGORY] = category
            } else {
                prefs.remove(KEY_BOOKSHELF_SELECTED_CATEGORY)
            }
        }
    }

    fun getBookshelfSearchQuery(): Flow<String> = context.userDataStore.data.map { prefs ->
        prefs[KEY_BOOKSHELF_SEARCH_QUERY] ?: ""
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfSearchQuery(query: String) {
        context.userDataStore.edit { prefs ->
            if (query.isBlank()) {
                prefs.remove(KEY_BOOKSHELF_SEARCH_QUERY)
            } else {
                prefs[KEY_BOOKSHELF_SEARCH_QUERY] = query
            }
        }
    }

    fun isBookshelfSearching(): Flow<Boolean> = context.userDataStore.data.map { prefs ->
        prefs[KEY_BOOKSHELF_SEARCHING] ?: false
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfSearching(searching: Boolean) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_BOOKSHELF_SEARCHING] = searching
        }
    }

    fun getBookshelfSearchHistory(): Flow<List<String>> = context.userDataStore.data.map { prefs ->
        parseStringList(prefs[KEY_BOOKSHELF_SEARCH_HISTORY] ?: "")
    }.flowOn(Dispatchers.IO)

    suspend fun addBookshelfSearchHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        context.userDataStore.edit { prefs ->
            val current = parseStringList(prefs[KEY_BOOKSHELF_SEARCH_HISTORY] ?: "")
            val updated = buildList {
                add(trimmed)
                current.filter { it != trimmed }.take(MAX_SEARCH_HISTORY_SIZE - 1).forEach { add(it) }
            }
            prefs[KEY_BOOKSHELF_SEARCH_HISTORY] = json.encodeToString(ListSerializer(String.serializer()), updated)
        }
    }

    suspend fun clearBookshelfSearchHistory() {
        context.userDataStore.edit { prefs ->
            prefs.remove(KEY_BOOKSHELF_SEARCH_HISTORY)
        }
    }

    fun getBookshelfScanState(rootUriString: String): Flow<BookshelfScanState> = context.userDataStore.data.map { prefs ->
        val key = stringPreferencesKey(KEY_BOOKSHELF_SCAN_PREFIX + rootUriString)
        parseBookshelfScanState(prefs[key])
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfScanState(rootUriString: String, state: BookshelfScanState) {
        context.userDataStore.edit { prefs ->
            val key = stringPreferencesKey(KEY_BOOKSHELF_SCAN_PREFIX + rootUriString)
            prefs[key] = json.encodeToString(BookshelfScanState.serializer(), state)
        }
    }

    suspend fun markBookshelfFilesSeen(rootUriString: String, currentFiles: Set<String>) {
        context.userDataStore.edit { prefs ->
            val key = stringPreferencesKey(KEY_BOOKSHELF_SCAN_PREFIX + rootUriString)
            prefs[key] = json.encodeToString(BookshelfScanState.serializer(),
                BookshelfScanState(
                    lastScanAt = System.currentTimeMillis(),
                    knownFiles = currentFiles
                )
            )
        }
    }

    private fun parseBookshelfScanState(raw: String?): BookshelfScanState {
        if (raw.isNullOrBlank()) return BookshelfScanState()
        return runCatching { json.decodeFromString<BookshelfScanState>(raw) }.getOrDefault(BookshelfScanState())
    }

    private fun parseStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val decoded = runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrNull()
        if (decoded != null) return decoded
        return raw.split("|").filter { it.isNotEmpty() }
    }
}
