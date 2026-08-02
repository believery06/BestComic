package com.mangareader.cache

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
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

    // 内存中维护每个缓存文件的最近访问时间，避免依赖文件系统 lastModified() 的低精度或不可靠实现。
    private val accessTimeIndex = ConcurrentHashMap<String, Long>()

    // 使用单线程后台执行 trim，避免 put 持有写锁时长时间删除文件阻塞读操作。
    private val trimExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "DiskLruCache-$name-trim").apply { isDaemon = true }
    }

    @Volatile
    private var trimPending = false
    @Volatile
    private var isShutdown = false

    fun getStream(key: String): InputStream? {
        // 元数据操作受读锁保护；打开文件流后立即解锁。
        // Android 基于 Linux，已打开的文件描述符在文件被 unlink 后仍可继续读取，
        // 因此无需在读取期间持有读锁，避免调用方未关闭流时造成读锁泄漏。
        val file: File
        lock.readLock().lock()
        try {
            file = fileForKey(key)
            if (!file.exists() || file.length() == 0L) return null
            val now = System.currentTimeMillis()
            file.setLastModified(now)
            accessTimeIndex[file.name] = now
        } finally {
            lock.readLock().unlock()
        }
        return try {
            FileInputStream(file)
        } catch (e: Exception) {
            null
        }
    }

    fun get(key: String): File? {
        lock.readLock().lock()
        try {
            val file = fileForKey(key)
            if (!file.exists() || file.length() == 0L) return null
            val now = System.currentTimeMillis()
            file.setLastModified(now)
            accessTimeIndex[file.name] = now
            return file
        } finally {
            lock.readLock().unlock()
        }
    }

    fun put(key: String, writer: (File) -> Unit): File? {
        if (isShutdown) return null
        // 将耗时写入放在锁外，避免大文件写入阻塞并发读取。
        val tmp = File(dir, "${keyHash(key)}.${System.currentTimeMillis()}.tmp")
        try {
            writer(tmp)
            if (!tmp.exists() || tmp.length() == 0L) {
                tmp.delete()
                return null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            return null
        }

        lock.writeLock().lock()
        try {
            val file = fileForKey(key)
            // renameTo 在某些文件系统/权限场景下会失败，兜底使用复制+删除。
            val moved = if (tmp.renameTo(file)) {
                true
            } else {
                runCatching {
                    tmp.inputStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                    tmp.delete()
                    true
                }.getOrDefault(false)
            }
            if (!moved) {
                tmp.delete()
                return null
            }
            val now = System.currentTimeMillis()
            file.setLastModified(now)
            accessTimeIndex[file.name] = now
            scheduleTrim()
            return file
        } catch (e: Exception) {
            tmp.delete()
            return null
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun clear() {
        lock.writeLock().lock()
        try {
            dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
            accessTimeIndex.clear()
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun remove(key: String): Boolean {
        lock.writeLock().lock()
        try {
            val file = fileForKey(key)
            val deleted = file.delete()
            if (deleted) accessTimeIndex.remove(file.name)
            return deleted
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

    private fun scheduleTrim() {
        if (isShutdown || trimPending) return
        trimPending = true
        trimExecutor.execute {
            trimPending = false
            trim()
        }
    }

    private fun trim() {
        // 快速路径：先检查总大小，未超限直接返回，避免获取写锁。
        val filesSnapshot = dir.listFiles() ?: return
        var total = filesSnapshot.sumOf { it.length() }
        if (total <= maxSizeBytes) return

        // 优先使用内存中维护的访问时间排序，避免依赖文件系统 lastModified() 精度。
        val sorted = filesSnapshot.filter { it.isFile }.sortedBy { accessTimeIndex[it.name] ?: it.lastModified() }
        for (file in sorted) {
            if (total <= maxSizeBytes) break
            lock.writeLock().lock()
            try {
                // 重新检查：异步执行期间文件可能已被删除或大小变化
                if (!file.exists()) continue
                val len = file.length()
                if (file.delete()) {
                    total -= len
                    accessTimeIndex.remove(file.name)
                }
            } finally {
                lock.writeLock().unlock()
            }
        }
    }

    /**
     * 释放后台 trim 线程并清理内存索引。应在缓存不再需要时调用（如应用退出）。
     */
    fun shutdown() {
        if (isShutdown) return
        synchronized(this) {
            if (isShutdown) return
            isShutdown = true
            accessTimeIndex.clear()
            trimExecutor.shutdown()
            runCatching { trimExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) }
        }
    }
}
