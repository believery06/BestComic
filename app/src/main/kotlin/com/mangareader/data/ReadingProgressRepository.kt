package com.mangareader.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class ReadingProgressRepository(private val context: Context) {

    companion object {
        @Volatile
        private var instance: ReadingProgressRepository? = null

        fun getInstance(context: Context): ReadingProgressRepository {
            return instance ?: synchronized(this) {
                instance ?: ReadingProgressRepository(context.applicationContext).also { instance = it }
            }
        }

        private val KEY_PROGRESS_PREFIX = "progress_"
        private val KEY_PROGRESS_TIME_PREFIX = "progress_time_"
        private val KEY_TOTAL_PREFIX = "total_"
        private val KEY_TRACKED_URIS = stringPreferencesKey("tracked_uris")
        private const val MAX_TRACKED_URIS = 500

        private val json = Json { ignoreUnknownKeys = true }
    }

    fun getProgress(uri: Uri): Flow<Int> = getProgress(uri, null)

    fun getProgress(uri: Uri, chapterTitle: String?): Flow<Int> = context.userDataStore.data.map { prefs ->
        prefs[intPreferencesKey(progressKey(uri, chapterTitle))]
            ?: prefs[intPreferencesKey(legacyProgressKey(uri, chapterTitle))]
            ?: 0
    }.flowOn(Dispatchers.IO)

    suspend fun saveProgress(uri: Uri, page: Int) = saveProgress(uri, null, page)

    suspend fun saveProgress(uri: Uri, chapterTitle: String?, page: Int) {
        context.userDataStore.edit { prefs ->
            val key = intPreferencesKey(progressKey(uri, chapterTitle))
            prefs[key] = page
            val legacyKey = intPreferencesKey(legacyProgressKey(uri, chapterTitle))
            if (prefs.contains(legacyKey)) {
                prefs.remove(legacyKey)
            }
            val timeKey = longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + uri.toString())
            prefs[timeKey] = System.currentTimeMillis()
            val currentUri = uri.toString()
            val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS])
            if (!tracked.contains(currentUri)) {
                val updated = (listOf(currentUri) + tracked).take(MAX_TRACKED_URIS)
                prefs[KEY_TRACKED_URIS] = json.encodeToString(ListSerializer(String.serializer()), updated)
            }
        }
    }

    private fun progressKey(uri: Uri, chapterTitle: String?): String {
        return KEY_PROGRESS_PREFIX + uri.toString() + if (!chapterTitle.isNullOrBlank()) "#$chapterTitle" else ""
    }

    private fun legacyProgressKey(uri: Uri, chapterTitle: String?): String {
        return KEY_PROGRESS_PREFIX + uri.toString() + if (!chapterTitle.isNullOrBlank()) "\u0000$chapterTitle" else ""
    }

    fun getTotalPages(uri: Uri): Flow<Int> = context.userDataStore.data.map { prefs ->
        prefs[intPreferencesKey(KEY_TOTAL_PREFIX + uri.toString())] ?: 0
    }.flowOn(Dispatchers.IO)

    suspend fun saveTotalPages(uri: Uri, total: Int) {
        context.userDataStore.edit { prefs ->
            prefs[intPreferencesKey(KEY_TOTAL_PREFIX + uri.toString())] = total
        }
    }

    suspend fun saveReadingProgress(
        uri: Uri,
        chapterTitle: String?,
        chapterPage: Int,
        overallPage: Int,
        totalPages: Int
    ) {
        context.userDataStore.edit { prefs ->
            val chapterKey = intPreferencesKey(progressKey(uri, chapterTitle))
            prefs[chapterKey] = chapterPage
            val legacyKey = intPreferencesKey(legacyProgressKey(uri, chapterTitle))
            if (prefs.contains(legacyKey)) {
                prefs.remove(legacyKey)
            }
            val timeKey = longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + uri.toString())
            prefs[timeKey] = System.currentTimeMillis()
            val currentUri = uri.toString()
            val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS])
            if (!tracked.contains(currentUri)) {
                val updated = (listOf(currentUri) + tracked).take(MAX_TRACKED_URIS)
                prefs[KEY_TRACKED_URIS] = json.encodeToString(ListSerializer(String.serializer()), updated)
            }
            prefs[intPreferencesKey(KEY_PROGRESS_PREFIX + currentUri)] = overallPage
            prefs[intPreferencesKey(KEY_TOTAL_PREFIX + currentUri)] = totalPages
        }
    }

    suspend fun saveProgressBatch(
        progressMap: Map<String, Int>,
        totalMap: Map<String, Int>
    ) {
        if (progressMap.isEmpty() && totalMap.isEmpty()) return
        context.userDataStore.edit { prefs ->
            val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS]).toMutableSet()
            var changed = false
            val now = System.currentTimeMillis()
            for ((uri, page) in progressMap) {
                prefs[intPreferencesKey(KEY_PROGRESS_PREFIX + uri)] = page
                prefs[longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + uri)] = now
                if (tracked.add(uri)) changed = true
            }
            for ((uri, total) in totalMap) {
                prefs[intPreferencesKey(KEY_TOTAL_PREFIX + uri)] = total
                if (tracked.add(uri)) changed = true
            }
            if (changed) {
                prefs[KEY_TRACKED_URIS] = json.encodeToString(
                    ListSerializer(String.serializer()),
                    (tracked.toList().take(MAX_TRACKED_URIS))
                )
            }
        }
    }

    suspend fun saveProgressTimesBatch(times: Map<String, Long>) {
        if (times.isEmpty()) return
        context.userDataStore.edit { prefs ->
            val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS]).toMutableSet()
            var changed = false
            for ((uri, time) in times) {
                prefs[longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + uri)] = time
                if (tracked.add(uri)) changed = true
            }
            if (changed) {
                prefs[KEY_TRACKED_URIS] = json.encodeToString(
                    ListSerializer(String.serializer()),
                    tracked.toList().take(MAX_TRACKED_URIS)
                )
            }
        }
    }

    fun getAllProgress(): Flow<Map<String, Pair<Int, Int>>> = context.userDataStore.data.map { prefs ->
        val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS])
        if (tracked.isEmpty()) return@map emptyMap()
        val all = prefs.asMap()
        val result = HashMap<String, Pair<Int, Int>>(tracked.size)
        for (uriStr in tracked) {
            val baseProgress = all[intPreferencesKey(KEY_PROGRESS_PREFIX + uriStr)] as? Int
            val total = (all[intPreferencesKey(KEY_TOTAL_PREFIX + uriStr)] as? Int) ?: 0
            val progress = baseProgress ?: run {
                val prefix = KEY_PROGRESS_PREFIX + uriStr
                all.entries.filter {
                    val name = it.key.name
                    name.startsWith(prefix) && name.length > prefix.length &&
                        name[prefix.length] in setOf('\u0000', '#')
                }.mapNotNull { it.value as? Int }.maxOrNull() ?: 0
            }
            result[uriStr] = Pair(progress, total)
        }
        result
    }.flowOn(Dispatchers.IO)

    private fun decodeTrackedUris(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }
            .getOrElse { raw.split("|").filter(String::isNotEmpty) }
    }
}
