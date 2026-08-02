package com.mangareader.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class BookmarkRepository(private val context: Context) {

    companion object {
        @Volatile
        private var instance: BookmarkRepository? = null

        fun getInstance(context: Context): BookmarkRepository {
            return instance ?: synchronized(this) {
                instance ?: BookmarkRepository(context.applicationContext).also { instance = it }
            }
        }

        private val KEY_BOOKMARKS = stringPreferencesKey("bookmarks_map")
        private val KEY_FAVORITES = stringPreferencesKey("favorites_set")
        private val KEY_CATEGORIES = stringPreferencesKey("categories_list")
        private val KEY_COMIC_CATEGORIES = stringPreferencesKey("comic_categories")

        internal val json = Json { ignoreUnknownKeys = true }
    }

    fun getBookmarks(): Flow<Map<String, List<Int>>> = context.userDataStore.data.map { prefs ->
        decodeBookmarks(prefs[KEY_BOOKMARKS])
    }.flowOn(Dispatchers.IO)

    suspend fun addBookmark(uri: Uri, page: Int) {
        context.userDataStore.edit { prefs ->
            val map = decodeBookmarks(prefs[KEY_BOOKMARKS]).toMutableMap()
            val key = uri.toString()
            val list = (map[key] ?: emptyList()).toMutableSet().apply { add(page) }.toSortedSet()
            map[key] = list.toList()
            prefs[KEY_BOOKMARKS] = encodeBookmarks(map)
        }
    }

    suspend fun removeBookmark(uri: Uri, page: Int) {
        context.userDataStore.edit { prefs ->
            val map = decodeBookmarks(prefs[KEY_BOOKMARKS]).toMutableMap()
            val key = uri.toString()
            val list = (map[key] ?: return@edit).toMutableList().apply { remove(page) }
            if (list.isEmpty()) {
                map.remove(key)
            } else {
                map[key] = list.sorted()
            }
            prefs[KEY_BOOKMARKS] = encodeBookmarks(map)
        }
    }

    suspend fun saveBookmarks(uri: Uri, pages: List<Int>) {
        context.userDataStore.edit { prefs ->
            val map = decodeBookmarks(prefs[KEY_BOOKMARKS]).toMutableMap()
            val key = uri.toString()
            if (pages.isEmpty()) {
                map.remove(key)
            } else {
                map[key] = pages.distinct().sorted()
            }
            prefs[KEY_BOOKMARKS] = encodeBookmarks(map)
        }
    }

    suspend fun mergeBookmarks(uriToPages: Map<String, List<Int>>) {
        if (uriToPages.isEmpty()) return
        context.userDataStore.edit { prefs ->
            val map = decodeBookmarks(prefs[KEY_BOOKMARKS]).toMutableMap()
            for ((uri, pages) in uriToPages) {
                val existing = map[uri] ?: emptyList()
                map[uri] = (existing + pages).distinct().sorted()
            }
            prefs[KEY_BOOKMARKS] = encodeBookmarks(map)
        }
    }

    private fun decodeBookmarks(raw: String?): Map<String, List<Int>> {
        if (raw.isNullOrBlank()) return emptyMap()
        val serializer = MapSerializer(String.serializer(), ListSerializer(Int.serializer()))
        return runCatching { json.decodeFromString(serializer, raw) }.getOrElse {
            raw.split(";").mapNotNull { entry ->
                val separator = entry.lastIndexOf(':')
                if (separator <= 0) return@mapNotNull null
                val pages = entry.substring(separator + 1).split(',').mapNotNull(String::toIntOrNull)
                entry.substring(0, separator).takeIf { pages.isNotEmpty() }?.let { it to pages }
            }.toMap()
        }
    }

    private fun encodeBookmarks(value: Map<String, List<Int>>): String = json.encodeToString(
        MapSerializer(String.serializer(), ListSerializer(Int.serializer())), value
    )

    fun getFavorites(): Flow<Set<String>> = context.userDataStore.data.map { prefs ->
        parseStringSet(prefs[KEY_FAVORITES])
    }.flowOn(Dispatchers.IO)

    suspend fun toggleFavorite(uri: Uri) {
        context.userDataStore.edit { prefs ->
            val set = parseStringSet(prefs[KEY_FAVORITES]).toMutableSet()
            val key = uri.toString()
            if (set.contains(key)) set.remove(key) else set.add(key)
            prefs[KEY_FAVORITES] = serializeStringSet(set)
        }
    }

    suspend fun addFavorites(uris: Set<String>) {
        if (uris.isEmpty()) return
        context.userDataStore.edit { prefs ->
            val set = parseStringSet(prefs[KEY_FAVORITES]).toMutableSet()
            var changed = false
            for (uri in uris) {
                if (set.add(uri)) changed = true
            }
            if (changed) {
                prefs[KEY_FAVORITES] = serializeStringSet(set)
            }
        }
    }

    fun getCategories(): Flow<List<String>> = context.userDataStore.data.map { prefs ->
        parseStringList(prefs[KEY_CATEGORIES])
    }.flowOn(Dispatchers.IO)

    suspend fun addCategory(name: String) {
        context.userDataStore.edit { prefs ->
            val list = parseStringList(prefs[KEY_CATEGORIES]).toMutableList()
            if (!list.contains(name)) list.add(name)
            prefs[KEY_CATEGORIES] = serializeStringList(list)
        }
    }

    suspend fun removeCategory(name: String) {
        context.userDataStore.edit { prefs ->
            val list = parseStringList(prefs[KEY_CATEGORIES]).filter { it != name }
            prefs[KEY_CATEGORIES] = serializeStringList(list)
            val catRaw = prefs[KEY_COMIC_CATEGORIES] ?: ""
            val map = parseComicCategoryMap(catRaw)
            map.values.forEach { it.remove(name) }
            prefs[KEY_COMIC_CATEGORIES] = serializeComicCategoryMap(map)
        }
    }

    fun getComicCategories(): Flow<Map<String, Set<String>>> = context.userDataStore.data.map { prefs ->
        val raw = prefs[KEY_COMIC_CATEGORIES] ?: return@map emptyMap()
        parseComicCategoryMap(raw)
    }.flowOn(Dispatchers.IO)

    suspend fun toggleComicCategory(uri: Uri, category: String) {
        context.userDataStore.edit { prefs ->
            val raw = prefs[KEY_COMIC_CATEGORIES] ?: ""
            val map = parseComicCategoryMap(raw)
            val key = uri.toString()
            val cats = map.getOrPut(key) { mutableSetOf() }
            if (cats.contains(category)) cats.remove(category) else cats.add(category)
            prefs[KEY_COMIC_CATEGORIES] = serializeComicCategoryMap(map)
        }
    }

    private fun parseComicCategoryMap(raw: String): MutableMap<String, MutableSet<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        raw.split(";").forEach { entry ->
            val parts = entry.split("::")
            if (parts.size == 2) {
                val uri = parts[0]
                val cats = parts[1].split(",").filter { it.isNotEmpty() }.toMutableSet()
                result[uri] = cats
            }
        }
        return result
    }

    private fun serializeComicCategoryMap(map: Map<String, Set<String>>): String {
        return map.entries
            .filter { it.value.isNotEmpty() }
            .joinToString(";") { (uri, cats) -> "$uri::${cats.joinToString(",")}" }
    }

    private fun parseStringSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val decoded = runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
        if (decoded != null) return decoded.toSet()
        return raw.split("|").filter { it.isNotEmpty() }.toSet()
    }

    private fun serializeStringSet(value: Set<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), value.toList())

    private fun parseStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val decoded = runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrNull()
        if (decoded != null) return decoded
        return raw.split("|").filter { it.isNotEmpty() }
    }

    private fun serializeStringList(value: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), value)
}
