package com.mangareader.utils

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CancellationException
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
    val lastModified: Long = System.currentTimeMillis(),
    /** 每条阅读进度对应的最后更新时间戳，用于多端同步冲突时选择较新的进度。 */
    val progressTimes: Map<String, Long> = emptyMap() // uri -> timestamp
)

object WebDavSync {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private const val SYNC_FILE = "best_comic_sync.json"
    private const val TIMEOUT = 15000
    private const val MAX_SYNC_DATA_BYTES = 10 * 1024 * 1024L // 同步数据上限 10MB

    /**
     * 测试WebDAV连接是否可用。
     * 除了 OPTIONS 探测外，还会尝试 PUT + GET 一个临时测试文件，确保服务器支持实际读写操作。
     * 若服务器地址使用 HTTP 明文传输且包含密码，将拒绝连接并提示使用 HTTPS。
     */
    suspend fun testConnection(config: WebDavConfig): Result<String> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val baseUrl = config.serverUrl.trimEnd('/')
            val url = URL(baseUrl)
            if (url.protocol == "http" && config.password.isNotEmpty()) {
                return@withContext Result.failure(
                    Exception("检测到 HTTP 明文传输，密码存在泄露风险。请使用 HTTPS 地址。")
                )
            }
            conn = createConnection(url, config, "OPTIONS")
            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext if (code == 401 || code == 403) {
                    Result.failure(Exception("认证失败: HTTP $code"))
                } else {
                    Result.failure(Exception("服务器返回 HTTP $code"))
                }
            }
            conn.disconnect()
            conn = null

            // 实际读写测试：上传临时文件后读取并删除。
            val testFileName = ".mangareader_test_${System.currentTimeMillis()}.tmp"
            val testContent = "mangareader-webdav-test".toByteArray(Charsets.UTF_8)
            val testUrl = URL("$baseUrl/$testFileName")

            // PUT
            val putConn = createConnection(testUrl, config, "PUT").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("Content-Length", testContent.size.toString())
            }
            val putCode = try {
                putConn.outputStream.use { it.write(testContent) }
                putConn.responseCode
            } finally {
                putConn.disconnect()
            }
            if (putCode !in 200..299 && putCode !in 201..204) {
                return@withContext Result.failure(Exception("上传测试文件失败: HTTP $putCode"))
            }

            // GET
            val getConn = createConnection(testUrl, config, "GET")
            val (getCode, gotContent) = try {
                val responseCode = getConn.responseCode
                val bytes = if (responseCode in 200..299) {
                    // 测试文件固定很小，直接读取即可。
                    getConn.inputStream.use { it.readBytes() }
                } else null
                responseCode to bytes
            } finally {
                getConn.disconnect()
            }
            if (getCode !in 200..299) {
                return@withContext Result.failure(Exception("读取测试文件失败: HTTP $getCode"))
            }
            if (gotContent?.contentEquals(testContent) != true) {
                return@withContext Result.failure(Exception("WebDAV 读写测试内容不一致"))
            }

            // DELETE（清理，失败仅记录不阻断）
            runCatching {
                val deleteConn = createConnection(testUrl, config, "DELETE")
                try {
                    deleteConn.responseCode
                } finally {
                    deleteConn.disconnect()
                }
            }

            Result.success("连接成功 (OPTIONS HTTP $code，读写测试通过)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
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
        favorites: Set<String>,
        progressTimes: Map<String, Long> = emptyMap()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val data = SyncData(
                progress = progress,
                totalPages = totalPages,
                bookmarks = bookmarks,
                favorites = favorites,
                progressTimes = progressTimes
            )
            val content = json.encodeToString(data)
            if (content.length > MAX_SYNC_DATA_BYTES) {
                return@withContext Result.failure(
                    Exception("本地同步数据超过 ${MAX_SYNC_DATA_BYTES / 1024 / 1024}MB 上限，请清理历史记录后重试")
                )
            }
            val url = URL("${config.serverUrl.trimEnd('/')}/$SYNC_FILE")
            conn = createConnection(url, config, "PUT")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")

            conn.outputStream.use { os ->
                ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)).use { input ->
                    input.copyTo(os)
                }
            }

            val code = conn.responseCode
            if (code in 200..299) {
                Result.success(Unit)
            } else if (code == 401 || code == 403) {
                Result.failure(Exception("认证失败: HTTP $code"))
            } else {
                Result.failure(Exception("上传失败: HTTP $code"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 从WebDAV服务器下载同步数据
     */
    suspend fun downloadSyncData(config: WebDavConfig): Result<SyncData> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("${config.serverUrl.trimEnd('/')}/$SYNC_FILE")
            conn = createConnection(url, config, "GET")
            val code = conn.responseCode

            if (code == 404) {
                return@withContext Result.success(SyncData()) // 远程没有数据，返回空
            }

            if (code !in 200..299) {
                return@withContext Result.failure(
                    if (code == 401 || code == 403) {
                        Exception("认证失败: HTTP $code")
                    } else {
                        Exception("下载失败: HTTP $code")
                    }
                )
            }

            val content = ByteArrayOutputStream().use { baos ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_SYNC_DATA_BYTES) {
                            return@withContext Result.failure(
                                Exception("远程同步数据超过 ${MAX_SYNC_DATA_BYTES / 1024 / 1024}MB 上限，可能存在异常")
                            )
                        }
                        baos.write(buffer, 0, read)
                    }
                }
                String(baos.toByteArray(), Charsets.UTF_8)
            }

            val data = json.decodeFromString<SyncData>(content)
            Result.success(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 双向合并同步数据。
     * - 阅读进度按每条记录的更新时间戳选择较新的一方；无时间戳时（旧数据）回退到取较大页码。
     * - 总页数取较大值（总页数通常只增不减）。
     * - 书签与收藏取并集。
     */
    fun mergeSyncData(local: SyncData, remote: SyncData): SyncData {
        val mergedProgress = local.progress.toMutableMap()
        val mergedTimes = local.progressTimes.toMutableMap()
        for ((uri, page) in remote.progress) {
            val remoteTime = remote.progressTimes[uri] ?: 0L
            val localTime = local.progressTimes[uri] ?: 0L
            when {
                remoteTime > localTime -> {
                    mergedProgress[uri] = page
                    mergedTimes[uri] = remoteTime
                }
                remoteTime == 0L && localTime == 0L -> {
                    // 旧数据没有时间戳，保守取较大页码
                    mergedProgress[uri] = maxOf(mergedProgress[uri] ?: 0, page)
                }
                else -> {
                    // 本地较新或时间相同：保留本地进度与时间
                }
            }
        }
        val mergedTotal = local.totalPages.toMutableMap()
        for ((uri, total) in remote.totalPages) {
            mergedTotal[uri] = maxOf(mergedTotal[uri] ?: 0, total)
        }
        return SyncData(
            progress = mergedProgress,
            totalPages = mergedTotal,
            bookmarks = mergeBookmarks(local.bookmarks, remote.bookmarks),
            favorites = mergeFavorites(local.favorites, remote.favorites),
            lastModified = maxOf(local.lastModified, remote.lastModified),
            progressTimes = mergedTimes
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
        var conn: HttpURLConnection? = null
        try {
            val url = URL("${config.serverUrl.trimEnd('/')}/$SYNC_FILE")
            conn = createConnection(url, config, "HEAD")
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun createConnection(url: URL, config: WebDavConfig, method: String): HttpURLConnection {
        if (url.protocol == "http" && config.password.isNotEmpty()) {
            throw IllegalArgumentException("检测到 HTTP 明文传输，密码存在泄露风险。请使用 HTTPS 地址。")
        }
        val conn = url.openConnection() as? HttpURLConnection
            ?: throw IllegalArgumentException("仅支持 HTTP/HTTPS 协议")
        conn.apply {
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
        val password = try {
            decryptPassword(storedPassword)
        } catch (_: Exception) {
            // 部分 ROM 的 KeyStore 可能无法使用，回退为空密码
            ""
        }
        if (storedPassword.isNotEmpty() && !storedPassword.startsWith(ENCRYPTED_PREFIX)) {
            try {
                prefs.edit().putString("password", encryptPassword(password)).apply()
            } catch (_: Exception) {
                android.util.Log.w("WebDavConfig", "迁移明文密码到加密存储失败，保留原值")
            }
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
        val encryptedPassword = try {
            encryptPassword(config.password)
        } catch (_: Exception) {
            // 加密失败时不保存密码，避免写入无效数据；用户下次需重新输入
            android.util.Log.w("WebDavConfig", "加密 WebDAV 密码失败，将清空已保存密码")
            ""
        }
        context.getSharedPreferences("webdav_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("server_url", config.serverUrl)
            .putString("username", config.username)
            .putString("password", encryptedPassword)
            .putBoolean("sync_progress", config.syncProgress)
            .putBoolean("sync_bookmarks", config.syncBookmarks)
            .putBoolean("sync_favorites", config.syncFavorites)
            .putInt("sync_interval", config.syncInterval)
            .apply()
        // 保存配置后同步更新后台同步任务
        WebDavSyncScheduler.schedule(context)
    }

    private fun encryptPassword(password: String): String {
        if (password.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP)
            val encrypted = android.util.Base64.encodeToString(
                cipher.doFinal(password.toByteArray(Charsets.UTF_8)),
                android.util.Base64.NO_WRAP
            )
            "$ENCRYPTED_PREFIX$iv:$encrypted"
        }.getOrDefault("")
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
