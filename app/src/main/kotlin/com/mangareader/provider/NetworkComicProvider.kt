package com.mangareader.provider

import android.graphics.Bitmap

/**
 * 网络协议直读（SMB/FTP/WebDAV/云盘）的 [ComicProvider] 接口预留。
 *
 * 具体实现将在后续版本中按协议拆分（如 SmbProvider、FtpProvider 等）。
 * 统一继承此接口后，阅读器无需关心数据来自本地还是网络。
 */
interface NetworkComicProvider : ComicProvider {
    /** 远程资源的 URL 或 URI 字符串 */
    val remoteUrl: String

    /** 测试连接是否可用 */
    suspend fun testConnection(): Boolean

    /** 列出远程目录下的条目（用于书架层级浏览） */
    suspend fun listEntries(path: String): List<RemoteEntry>
}

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L
)

/**
 * SMB 直读占位实现，后续接入 jcifs/smbj 等库。
 */
class SmbProvider(
    override val remoteUrl: String
) : NetworkComicProvider {
    override val title: String = remoteUrl.substringAfterLast('/')
    override val pageCount: Int = 0
    override suspend fun getPage(index: Int): Bitmap? = null
    override suspend fun getThumbnail(index: Int, maxDimension: Int): Bitmap? = null
    override suspend fun testConnection(): Boolean = false
    override suspend fun listEntries(path: String): List<RemoteEntry> = emptyList()
}
