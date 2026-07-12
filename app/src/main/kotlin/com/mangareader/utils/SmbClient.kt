package com.mangareader.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * SMB/NAS流式阅读客户端
 * 支持直接读取局域网NAS或SMB共享中的漫画文件
 * 使用JCIFS/SMB协议实现，支持随机读取压缩包内的文件
 *
 * 由于Android端SMB库体积较大，这里提供基于HTTP的文件服务器
 * 适配方案：用户可在NAS上运行简单的HTTP文件服务器（如Python http.server）
 * 或使用支持SMB的第三方库（如jcifs-ng）
 *
 * 简化实现：基于HTTP Range请求的流式读取
 * 适用于支持HTTP文件服务的NAS（如群晖File Station、Samba HTTP前端等）
 */
object SmbClient {

    /**
     * 测试NAS连接是否可用
     * @param url NAS HTTP文件服务地址
     */
    suspend fun testConnection(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val conn = createConnection(url, "HEAD")
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) {
                val contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: 0
                val acceptRanges = conn.getHeaderField("Accept-Ranges")
                Result.success(
                    "连接成功 | 大小: ${formatSize(contentLength)} | 支持Range: ${acceptRanges ?: "否"}"
                )
            } else {
                Result.failure(Exception("服务器返回错误：HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取远程文件大小
     */
    suspend fun getFileSize(url: String): Long = withContext(Dispatchers.IO) {
        try {
            val conn = createConnection(url, "HEAD")
            val size = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
            conn.disconnect()
            size
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * 使用HTTP Range请求读取文件的指定字节范围
     * 支持随机读取，可用于ZIP/RAR等压缩格式的流式访问
     */
    suspend fun readRange(url: String, start: Long, end: Long): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val conn = createConnection(url, "GET")
                conn.setRequestProperty("Range", "bytes=$start-$end")
                val code = conn.responseCode
                if (code != 206 && code !in 200..299) {
                    conn.disconnect()
                    return@withContext null
                }
                val contentLength = conn.getHeaderField("Content-Length")?.toIntOrNull()
                    ?: ((end - start + 1).toInt())
                val buffer = ByteArray(contentLength)
                conn.inputStream.use { input ->
                    var offset = 0
                    var bytesRead: Int
                    while (offset < contentLength) {
                        bytesRead = input.read(buffer, offset, contentLength - offset)
                        if (bytesRead < 0) break
                        offset += bytesRead
                    }
                }
                conn.disconnect()
                buffer
            } catch (_: Exception) {
                null
            }
        }

    /**
     * 流式读取整个文件（用于下载小文件/元数据）
     */
    suspend fun readFully(url: String, maxSize: Long = 100 * 1024 * 1024): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val conn = createConnection(url, "GET")
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    return@withContext null
                }
                val contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull()
                    ?: maxSize
                if (contentLength > maxSize) {
                    conn.disconnect()
                    return@withContext null
                }
                val baos = ByteArrayOutputStream(contentLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                conn.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } >= 0) {
                        baos.write(buffer, 0, bytesRead)
                    }
                }
                conn.disconnect()
                baos.toByteArray()
            } catch (_: Exception) {
                null
            }
        }

    /**
     * 从远程ZIP文件中流式读取单个条目
     * 适用于CBZ文件，无需下载整个文件即可读取单张图片
     */
    suspend fun readZipEntry(
        url: String,
        entryName: String,
        centralDirectory: List<ZipEntryInfo> = emptyList()
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // 1. 先读取EOCD记录获取中央目录
            val entries = if (centralDirectory.isEmpty()) {
                readCentralDirectory(url)
            } else {
                centralDirectory
            }

            val entry = entries.find { it.name == entryName } ?: return@withContext null

            // 2. 读取本地文件头
            val localHeader = readRange(url, entry.localHeaderOffset, entry.localHeaderOffset + 29)
                ?: return@withContext null
            val fileNameLength = ((localHeader[26].toInt() and 0xFF) or
                    ((localHeader[27].toInt() and 0xFF) shl 8))
            val extraLength = ((localHeader[28].toInt() and 0xFF) or
                    ((localHeader[29].toInt() and 0xFF) shl 8))

            // 3. 计算数据起始偏移
            val dataOffset = entry.localHeaderOffset + 30 + fileNameLength + extraLength

            // 4. 读取压缩数据
            readRange(url, dataOffset, dataOffset + entry.compressedSize - 1)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 读取ZIP中央目录
     */
    private suspend fun readCentralDirectory(url: String): List<ZipEntryInfo> =
        withContext(Dispatchers.IO) {
            try {
                val fileSize = getFileSize(url)
                if (fileSize <= 0) return@withContext emptyList()

                // 从文件末尾向前搜索EOCD签名 (0x06054b50)
                val searchSize = 65536L.coerceAtMost(fileSize)
                val tail = readRange(url, fileSize - searchSize, fileSize - 1)
                    ?: return@withContext emptyList()

                var eocdOffset = -1
                for (i in tail.size - 22 downTo 0) {
                    if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() &&
                        tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
                    ) {
                        eocdOffset = i
                        break
                    }
                }
                if (eocdOffset < 0) return@withContext emptyList()

                val eocd = tail.copyOfRange(eocdOffset, eocdOffset + 22)
                val centralDirOffset = (
                        (eocd[16].toInt() and 0xFF) or
                                ((eocd[17].toInt() and 0xFF) shl 8) or
                                ((eocd[18].toInt() and 0xFF) shl 16) or
                                ((eocd[19].toInt() and 0xFF) shl 24)
                        ).toLong()
                val centralDirSize = (
                        (eocd[12].toInt() and 0xFF) or
                                ((eocd[13].toInt() and 0xFF) shl 8) or
                                ((eocd[14].toInt() and 0xFF) shl 16) or
                                ((eocd[15].toInt() and 0xFF) shl 24)
                        ).toLong()

                val centralDir = readRange(url, centralDirOffset, centralDirOffset + centralDirSize - 1)
                    ?: return@withContext emptyList()

                // 解析中央目录条目
                val entries = mutableListOf<ZipEntryInfo>()
                var pos = 0
                while (pos < centralDir.size - 46) {
                    val signature = ((centralDir[pos].toInt() and 0xFF) or
                            ((centralDir[pos + 1].toInt() and 0xFF) shl 8) or
                            ((centralDir[pos + 2].toInt() and 0xFF) shl 16) or
                            ((centralDir[pos + 3].toInt() and 0xFF) shl 24))
                    if (signature != 0x02014b50) break

                    val compressedSize = (
                            (centralDir[pos + 20].toInt() and 0xFF) or
                                    ((centralDir[pos + 21].toInt() and 0xFF) shl 8) or
                                    ((centralDir[pos + 22].toInt() and 0xFF) shl 16) or
                                    ((centralDir[pos + 23].toInt() and 0xFF) shl 24)
                            ).toLong()
                    val uncompressedSize = (
                            (centralDir[pos + 24].toInt() and 0xFF) or
                                    ((centralDir[pos + 25].toInt() and 0xFF) shl 8) or
                                    ((centralDir[pos + 26].toInt() and 0xFF) shl 16) or
                                    ((centralDir[pos + 27].toInt() and 0xFF) shl 24)
                            ).toLong()
                    val fileNameLen = ((centralDir[pos + 28].toInt() and 0xFF) or
                            ((centralDir[pos + 29].toInt() and 0xFF) shl 8))
                    val extraLen = ((centralDir[pos + 30].toInt() and 0xFF) or
                            ((centralDir[pos + 31].toInt() and 0xFF) shl 8))
                    val commentLen = ((centralDir[pos + 32].toInt() and 0xFF) or
                            ((centralDir[pos + 33].toInt() and 0xFF) shl 8))
                    val localHeaderOffset = (
                            (centralDir[pos + 42].toInt() and 0xFF) or
                                    ((centralDir[pos + 43].toInt() and 0xFF) shl 8) or
                                    ((centralDir[pos + 44].toInt() and 0xFF) shl 16) or
                                    ((centralDir[pos + 45].toInt() and 0xFF) shl 24)
                            ).toLong()

                    val nameBytes = centralDir.copyOfRange(pos + 46, pos + 46 + fileNameLen)
                    val name = String(nameBytes, Charsets.UTF_8)

                    if (compressedSize > 0 && !name.endsWith("/")) {
                        entries.add(
                            ZipEntryInfo(
                                name = name,
                                compressedSize = compressedSize,
                                uncompressedSize = uncompressedSize,
                                localHeaderOffset = localHeaderOffset
                            )
                        )
                    }

                    pos += 46 + fileNameLen + extraLen + commentLen
                }
                entries
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * 列出远程目录中的文件（适用于Apache/nginx目录列表）
     */
    suspend fun listDirectory(url: String): List<RemoteFileInfo> = withContext(Dispatchers.IO) {
        try {
            val conn = createConnection(url, "GET")
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext emptyList()
            }
            val html = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            // 简单解析HTML目录列表
            parseDirectoryListing(html, url)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseDirectoryListing(html: String, baseUrl: String): List<RemoteFileInfo> {
        val files = mutableListOf<RemoteFileInfo>()
        // 匹配 <a href="..."> 链接
        val hrefRegex = Regex("""<a\s+href="([^"]+)"[^>]*>([^<]+)</a>""")
        for (match in hrefRegex.findAll(html)) {
            val href = match.groupValues[1]
            val name = match.groupValues[2].trim()
            if (href == "../" || name == "Parent Directory") continue
            val isDir = href.endsWith("/")
            val fullUrl = if (href.startsWith("http")) href
            else "${baseUrl.trimEnd('/')}/${href.trimStart('/')}"

            // 检查是否为漫画格式
            val isComic = name.let { n ->
                n.endsWith(".cbz") || n.endsWith(".zip") || n.endsWith(".cbr") ||
                        n.endsWith(".rar") || n.endsWith(".cb7") || n.endsWith(".7z") ||
                        n.endsWith(".cbt") || n.endsWith(".epub") || n.endsWith(".pdf")
            }

            files.add(
                RemoteFileInfo(
                    name = name,
                    url = fullUrl,
                    isDirectory = isDir,
                    isComic = isComic
                )
            )
        }
        return files
    }

    data class ZipEntryInfo(
        val name: String,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long
    )

    data class RemoteFileInfo(
        val name: String,
        val url: String,
        val isDirectory: Boolean,
        val isComic: Boolean
    )

    private fun createConnection(urlString: String, method: String): HttpURLConnection {
        val url = URL(urlString)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 30000
            setRequestProperty("User-Agent", "BestComic/1.0")
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}
