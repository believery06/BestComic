package com.mangareader.cache

import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * 简易 LRU 磁盘缓存。
 *
 * 按文件最后访问时间管理，超过 [maxSizeBytes] 时删除最久未访问的条目。
 * 键通过 SHA-256 哈希后作为文件名。
 */
class DiskLruCache(
    cacheDir: File,
    private val name: String,
    private val maxSizeBytes: Long = 100L * 1024 * 1024
) {
    private val dir = File(cacheDir, name).apply { mkdirs() }
    
    // 全局读写锁，防止 trim 删除正在被读取的文件
    private val lock = ReentrantReadWriteLock()

    fun getStream(key: String): InputStream? {
        lock.readLock().lock()
        var fis: FileInputStream? = null
        var stream: InputStream? = null
        try {
            val file = fileForKey(key)
            if (!file.exists() || file.length() == 0L) {
                lock.readLock().unlock()
                return null
            }
            file.setLastModified(System.currentTimeMillis())
            fis = FileInputStream(file)
            val capturedFis = fis
            stream = object : FilterInputStream(capturedFis) {
                var closed = false
                override fun close() {
                    if (!closed) {
                        closed = true
                        try { super.close() } finally { lock.readLock().unlock() }
                    }
                }
            }
            fis = null // 所有权转移给 stream
            return stream
        } catch (e: Exception) {
            runCatching { stream?.close() }
            runCatching { fis?.close() }
            lock.readLock().unlock()
            return null
        }
    }

    fun get(key: String): File? {
        val file = fileForKey(key)
        if (!file.exists() || file.length() == 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    fun put(key: String, writer: (File) -> Unit): File? {
        lock.writeLock().lock()
        try {
            val tmp = File(dir, "${keyHash(key)}.tmp")
            try {
                writer(tmp)
                if (!tmp.exists() || tmp.length() == 0L) {
                    tmp.delete()
                    return null
                }
                val file = fileForKey(key)
                tmp.renameTo(file)
                file.setLastModified(System.currentTimeMillis())
                trim()
                return file
            } catch (e: Exception) {
                tmp.delete()
                return null
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun clear() {
        lock.writeLock().lock()
        try {
            dir.listFiles()?.forEach { it.delete() }
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun size(): Long {
        lock.readLock().lock()
        try {
            return dir.listFiles()?.sumOf { it.length() } ?: 0L
        } finally {
            lock.readLock().unlock()
        }
    }

    private fun fileForKey(key: String): File = File(dir, "${keyHash(key)}.cache")

    private fun keyHash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(key.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun trim() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxSizeBytes) return

        // 按最后访问时间升序，删除最旧的
        val sorted = files.filter { it.isFile }.sortedBy { it.lastModified() }
        for (file in sorted) {
            if (total <= maxSizeBytes) break
            val len = file.length()
            if (file.delete()) total -= len
        }
    }
}
