package com.mangareader.utils

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * WebDAV进度同步工具
 * 支持通过WebDAV协议同步阅读进度、书签、书库数据
 * 使用PUT/GET/PROPFIND方法实现多端数据同步
 */
@Serializable
data class WebDavConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val syncProgress: Boolean = true,
    val syncBookmarks: Boolean = true,
    val syncFavorites: Boolean = true,
    val syncInterval: Int = 0 // 0=手动, >0=自动同步间隔(分钟)
)

@Serializable
data class SyncData(
    val progress: Map<String, Int> = emptyMap(),      // uri -> page
    val totalPages: Map<String, Int> = emptyMap(),     // uri -> total
    val bookmarks: Map<String, List<Int>> = emptyMap(), // uri -> pages
    val favorites: Set<String> = emptySet(),            // uri strings
    val lastModified: Long = System.currentTimeMillis()
)

object WebDavSync {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private const val SYNC_FILE = "best_comic_sync.json"
    private const val TIMEOUT = 15000

    /**
     * 测试WebDAV连接是否可用
     */
    suspend fun testConnection(config: WebDavConfig): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL(config.serverUrl.trimEnd('/'))
            val conn = createConnection(url, config, "OPTIONS")
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) {
                Result.success("连接成功 (HTTP $code)")
            } else {
                Result.failure(Exception("服务器返回 HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 上传同步数据到WebDAV服务器
     */
    suspend fun uploadSyncData(
        config: WebDavConfig,
        progress: Map<String, Int>,
        totalPages: Map<String, Int>,
        bookmarks: Map<String, List<Int>>,
        favorites: Set<String>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val data = SyncData(
                progress = progress,
                totalPages = totalPages,
                bookmarks = bookmarks,
                favorites = favorites
            )
            val content = json.encodeToString(data)
            val url = URL("${config.serverUrl.trimEnd('/')}/$SYNC_FILE")
            val conn = createConnection(url, config, "PUT")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")

            conn.outputStream.use { os ->
                ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)).use { input ->
                    input.copyTo(os)
                }
            }

            val code = conn.responseCode
            conn.disconnect()

            if (code in 200..299) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("上传失败: HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从WebDAV服务器下载同步数据
     */
    suspend fun downloadSyncData(config: WebDavConfig): Result<SyncData> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${config.serverUrl.trimEnd('/')}/$SYNC_FILE")
            val conn = createConnection(url, config, "GET")
            val code = conn.responseCode

            if (code == 404) {
                conn.disconnect()
                return@withContext Result.success(SyncData()) // 远程没有数据，返回空
            }

            if (code !in 200..299) {
                conn.disconnect()
                return@withContext Result.failure(Exception("下载失败: HTTP $code"))
            }

            val content = ByteArrayOutputStream().use { baos ->
                conn.inputStream.use { input ->
                    input.copyTo(baos)
                }
                baos.toString(Charsets.UTF_8)
            }
            conn.disconnect()

            val data = json.decodeFromString<SyncData>(content)
            Result.success(data)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 智能合并同步数据（时间戳最新优先）
     */
    fun mergeSyncData(local: SyncData, remote: SyncData): SyncData {
        if (remote.lastModified <= local.lastModified) return local

        return SyncData(
            progress = remote.progress, // 远程进度覆盖本地
            totalPages = (local.totalPages + remote.totalPages), // 合并
            bookmarks = mergeBookmarks(local.bookmarks, remote.bookmarks),
            favorites = mergeFavorites(local.favorites, remote.favorites),
            lastModified = maxOf(local.lastModified, remote.lastModified)
        )
    }

    private fun mergeBookmarks(
        local: Map<String, List<Int>>,
        remote: Map<String, List<Int>>
    ): Map<String, List<Int>> {
        val merged = local.toMutableMap()
        for ((uri, pages) in remote) {
            val existing = merged[uri] ?: emptyList()
            merged[uri] = (existing + pages).distinct().sorted()
        }
        return merged
    }

    private fun mergeFavorites(local: Set<String>, remote: Set<String>): Set<String> {
        return local + remote
    }

    /**
     * 检查远程文件是否存在
     */
    suspend fun checkRemoteExists(config: WebDavConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("${config.serverUrl.trimEnd('/')}/$SYNC_FILE")
            val conn = createConnection(url, config, "HEAD")
            val exists = conn.responseCode in 200..299
            conn.disconnect()
            exists
        } catch (_: Exception) {
            false
        }
    }

    private fun createConnection(url: URL, config: WebDavConfig, method: String): HttpURLConnection {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            setRequestProperty("User-Agent", "BestComic/1.0")
            instanceFollowRedirects = true
        }

        if (config.username.isNotEmpty()) {
            val auth = "${config.username}:${config.password}"
            val encoded = android.util.Base64.encodeToString(
                auth.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            conn.setRequestProperty("Authorization", "Basic $encoded")
        }

        return conn
    }
}

/**
 * WebDAV配置管理器
 */
class WebDavConfigManager(private val context: Context) {
    fun getConfig(): WebDavConfig {
        val prefs = context.getSharedPreferences("webdav_prefs", Context.MODE_PRIVATE)
        val storedPassword = prefs.getString("password", "") ?: ""
        val password = decryptPassword(storedPassword)
        if (storedPassword.isNotEmpty() && !storedPassword.startsWith(ENCRYPTED_PREFIX)) {
            prefs.edit().putString("password", encryptPassword(password)).apply()
        }
        return WebDavConfig(
            serverUrl = prefs.getString("server_url", "") ?: "",
            username = prefs.getString("username", "") ?: "",
            password = password,
            syncProgress = prefs.getBoolean("sync_progress", true),
            syncBookmarks = prefs.getBoolean("sync_bookmarks", true),
            syncFavorites = prefs.getBoolean("sync_favorites", true),
            syncInterval = prefs.getInt("sync_interval", 0)
        )
    }

    fun saveConfig(config: WebDavConfig) {
        context.getSharedPreferences("webdav_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("server_url", config.serverUrl)
            .putString("username", config.username)
            .putString("password", encryptPassword(config.password))
            .putBoolean("sync_progress", config.syncProgress)
            .putBoolean("sync_bookmarks", config.syncBookmarks)
            .putBoolean("sync_favorites", config.syncFavorites)
            .putInt("sync_interval", config.syncInterval)
            .apply()
    }

    private fun encryptPassword(password: String): String {
        if (password.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP)
        val encrypted = android.util.Base64.encodeToString(
            cipher.doFinal(password.toByteArray(Charsets.UTF_8)),
            android.util.Base64.NO_WRAP
        )
        return "$ENCRYPTED_PREFIX$iv:$encrypted"
    }

    private fun decryptPassword(stored: String): String {
        if (stored.isEmpty() || !stored.startsWith(ENCRYPTED_PREFIX)) return stored
        return runCatching {
            val parts = stored.removePrefix(ENCRYPTED_PREFIX).split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val encrypted = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "best_comic_webdav_password"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENCRYPTED_PREFIX = "enc:v1:"
    }
}
