package com.mangareader.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class ComicExtrasRepository(private val context: Context) {

    companion object {
        @Volatile
        private var instance: ComicExtrasRepository? = null

        fun getInstance(context: Context): ComicExtrasRepository {
            return instance ?: synchronized(this) {
                instance ?: ComicExtrasRepository(context.applicationContext).also { instance = it }
            }
        }

        private val KEY_COMIC_EXTRAS_PREFIX = "extras_"
        private val KEY_READING_LISTS = stringPreferencesKey("reading_lists")

        private val json = Json { ignoreUnknownKeys = true }
    }

    fun getComicExtras(uri: Uri): Flow<ComicExtras> = context.userDataStore.data.map { prefs ->
        parseComicExtras(prefs[stringPreferencesKey(KEY_COMIC_EXTRAS_PREFIX + uri.toString())])
    }.flowOn(Dispatchers.IO)

    fun getAllComicExtras(): Flow<Map<String, ComicExtras>> = context.userDataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            val keyName = key.name
            if (keyName.startsWith(KEY_COMIC_EXTRAS_PREFIX) && value is String) {
                val uriString = keyName.removePrefix(KEY_COMIC_EXTRAS_PREFIX)
                uriString to parseComicExtras(value)
            } else null
        }.toMap()
    }.flowOn(Dispatchers.IO)

    suspend fun saveComicExtras(uri: Uri, extras: ComicExtras) {
        context.userDataStore.edit { prefs ->
            prefs[stringPreferencesKey(KEY_COMIC_EXTRAS_PREFIX + uri.toString())] =
                json.encodeToString(ComicExtras.serializer(), extras)
        }
    }

    private fun parseComicExtras(raw: String?): ComicExtras {
        if (raw.isNullOrBlank()) return ComicExtras()
        return runCatching { json.decodeFromString(ComicExtras.serializer(), raw) }.getOrDefault(ComicExtras())
    }

    fun getReadingLists(): Flow<List<ReadingList>> = context.userDataStore.data.map { prefs ->
        parseReadingLists(prefs[KEY_READING_LISTS])
    }.flowOn(Dispatchers.IO)

    suspend fun saveReadingLists(lists: List<ReadingList>) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_READING_LISTS] = json.encodeToString(ListSerializer(ReadingList.serializer()), lists)
        }
    }

    suspend fun addComicToList(listId: String, comic: ComicEntry) {
        val lists = getReadingLists().first().toMutableList()
        val index = lists.indexOfFirst { it.id == listId }
        if (index < 0) return
        val list = lists[index]
        val item = ReadingListItem(uriString = comic.uri.toString(), title = comic.title)
        if (list.items.any { it.uriString == item.uriString }) return
        lists[index] = list.copy(items = list.items + item)
        saveReadingLists(lists)
    }

    private fun parseReadingLists(raw: String?): List<ReadingList> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(ReadingList.serializer()), raw) }.getOrDefault(emptyList())
    }
}
