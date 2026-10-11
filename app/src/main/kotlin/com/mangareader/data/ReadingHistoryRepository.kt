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
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class ReadingHistoryRepository(private val context: Context) {

    companion object {
        @Volatile
        private var instance: ReadingHistoryRepository? = null

        fun getInstance(context: Context): ReadingHistoryRepository {
            return instance ?: synchronized(this) {
                instance ?: ReadingHistoryRepository(context.applicationContext).also { instance = it }
            }
        }

        private val KEY_READING_HISTORY = stringPreferencesKey("reading_history")
        private const val MAX_HISTORY_SIZE = 200

        private val json = Json { ignoreUnknownKeys = true }
    }

    fun getReadingHistory(): Flow<List<ReadingHistoryEntry>> = context.userDataStore.data.map { prefs ->
        parseReadingHistory(prefs[KEY_READING_HISTORY])
    }.flowOn(Dispatchers.IO)

    suspend fun addReadingHistory(entry: ReadingHistoryEntry) {
        context.userDataStore.edit { prefs ->
            val list = parseReadingHistory(prefs[KEY_READING_HISTORY]).toMutableList()
            list.removeAll { it.uriString == entry.uriString }
            list.add(0, entry)
            while (list.size > MAX_HISTORY_SIZE) list.removeAt(list.size - 1)
            prefs[KEY_READING_HISTORY] = json.encodeToString(ListSerializer(ReadingHistoryEntry.serializer()), list)
        }
    }

    suspend fun clearReadingHistory(uriString: String? = null) {
        context.userDataStore.edit { prefs ->
            if (uriString == null) {
                prefs.remove(KEY_READING_HISTORY)
            } else {
                val list = parseReadingHistory(prefs[KEY_READING_HISTORY]).toMutableList()
                list.removeAll { it.uriString == uriString }
                prefs[KEY_READING_HISTORY] = json.encodeToString(ListSerializer(ReadingHistoryEntry.serializer()), list)
            }
        }
    }

    fun getRecentReads(): Flow<Map<String, Long>> = context.userDataStore.data.map { prefs ->
        val tracked = decodeTrackedUris(prefs[stringPreferencesKey("tracked_uris")])
        if (tracked.isEmpty()) return@map emptyMap()
        val all = prefs.asMap()
        val result = HashMap<String, Long>(tracked.size)
        for (uriStr in tracked) {
            val time = all[androidx.datastore.preferences.core.longPreferencesKey("progress_time_$uriStr")] as? Long ?: 0L
            if (time > 0L) {
                result[uriStr] = time
            }
        }
        result
    }.flowOn(Dispatchers.IO)

    suspend fun markAsRead(uri: Uri, totalPages: Int) {
        val progressRepo = ReadingProgressRepository.getInstance(context)
        // 必须同时写 total：此前只写进度键，对从未打开过的漫画（total 缺失），
        // 书架仍判定为未读，批量标记已读完全无效。
        progressRepo.saveTotalPages(uri, totalPages)
        // 写到末页或越过末页一位：保证单页漫画（末页下标 0）也能满足已读判定，
        // 阅读器恢复进度时会自行 coerce 回合法范围。
        progressRepo.saveProgress(uri, totalPages.coerceAtLeast(1))
    }

    suspend fun markAsUnread(uri: Uri) {
        ReadingProgressRepository.getInstance(context).saveProgress(uri, 0)
    }

    private fun decodeTrackedUris(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrElse { raw.split("|").filter(String::isNotEmpty) }
    }

    private fun parseReadingHistory(raw: String?): List<ReadingHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(ReadingHistoryEntry.serializer()), raw) }.getOrDefault(emptyList())
    }
}
