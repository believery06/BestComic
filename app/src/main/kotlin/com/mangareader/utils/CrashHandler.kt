package com.mangareader.utils

import android.annotation.SuppressLint
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 全局异常捕获与错误日志机制。
 *
 * - 在 Application 中初始化，捕获所有未处理异常并写入日志文件
 * - 下次启动时检测是否有上次异常退出的记录，若有则在 UI 中弹出
 * - 保留最近 5 条日志，避免占用过多存储
 */
object CrashHandler {

    private const val LOG_DIR = "crash_logs"
    private const val MAX_LOG_FILES = 5
    private const val MAX_LOG_SIZE_BYTES = 256 * 1024
    /** 崩溃日志目录总大小上限 2MB，避免长期累积占用存储。 */
    private const val MAX_TOTAL_LOG_SIZE_BYTES = 2L * 1024 * 1024
    private const val MAX_LOG_READ_TOTAL_BYTES = 512 * 1024L // 读取崩溃日志总量上限 512KB
    private lateinit var logDir: File
    private val sensitivePaths = mutableListOf<String>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** 初始化全局异常捕获。在 Application.onCreate() 中调用。 */
    fun init(context: Context) {
        logDir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
        sensitivePaths.clear()
        context.applicationInfo?.dataDir?.let { sensitivePaths += it }
        context.filesDir?.absolutePath?.let { sensitivePaths += it }
        context.cacheDir?.absolutePath?.let { sensitivePaths += it }
        context.getExternalFilesDir(null)?.absolutePath?.let { sensitivePaths += it }
        context.externalCacheDir?.absolutePath?.let { sensitivePaths += it }
        context.applicationInfo?.sourceDir?.let { sensitivePaths += it }
        sensitivePaths += logDir.absolutePath
        // 先替换长路径，避免短路径先替换后无法匹配更具体的子路径
        sensitivePaths.sortByDescending { it.length }
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logCrash(thread, throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** 记录一条异常日志到文件。 */
    private fun logCrash(thread: Thread, throwable: Throwable) {
        try {
            rotateLogs()
            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US).format(LocalDateTime.now())
            val file = File(logDir, "crash_${timestamp}_${System.currentTimeMillis()}.log")
            val sw = StringWriter()
            PrintWriter(sw).use { pw ->
                pw.println("Time: ${LocalDateTime.now()}")
                pw.println("Thread: ${thread.name} (id=${thread.id})")
                pw.println("Exception: ${throwable.javaClass.name}: ${throwable.message}")
                throwable.printStackTrace(pw)
                var cause = throwable.cause
                while (cause != null) {
                    pw.println("Caused by: ${cause.javaClass.name}: ${cause.message}")
                    cause.printStackTrace(pw)
                    cause = cause.cause
                }
            }
            // 先写入临时文件再原子重命名，避免崩溃过程中产生残缺日志。
            var sanitized = sanitize(sw.toString())
            val hint = "\n\n[日志过长，已截断至 ${MAX_LOG_SIZE_BYTES / 1024}KB]"
            val hintBytes = hint.toByteArray(Charsets.UTF_8)
            val bytes = sanitized.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_LOG_SIZE_BYTES) {
                // 按 UTF-8 字符边界截断，避免产生无效字节序列。
                val maxBodyBytes = (MAX_LOG_SIZE_BYTES - hintBytes.size).coerceAtLeast(0)
                var end = maxBodyBytes.coerceAtMost(bytes.size)
                while (end > 0 && (bytes[end].toInt() and 0x80) != 0 && (bytes[end].toInt() and 0xC0) != 0xC0) {
                    end--
                }
                sanitized = bytes.decodeToString(0, end) + hint
            }
            val tmp = File(logDir, "${file.name}.tmp")
            tmp.writeText(sanitized)
            if (!tmp.renameTo(file)) {
                tmp.delete()
            }
        } catch (_: Exception) {
            // 日志写入失败时静默处理，避免二次崩溃
        }
    }

    /**
     * 脱敏处理：将应用私有路径、外部存储路径以及 URI 替换为占位符，避免日志泄露用户信息。
     */
    @SuppressLint("SdCardPath")
    private fun sanitize(text: String): String {
        var result = text
        for (path in sensitivePaths) {
            if (!path.isNullOrEmpty()) {
                result = result.replace(path, "<private>")
            }
        }
        // 外部存储路径 /storage/emulated/... 或 /sdcard/...
        result = result.replace(Regex("/storage/[^\\s\"'<>]*"), "<external>")
        result = result.replace(Regex("/sdcard/[^\\s\"'<>]*"), "<external>")
        // content:// 与 file:// URI
        result = result.replace(Regex("content://[^\\s\"'<>]*"), "<content-uri>")
        result = result.replace(Regex("file://[^\\s\"'<>]*"), "<file-uri>")
        return result
    }

    /** 当日志文件数或总大小超过上限时，删除最旧的日志。 */
    private fun rotateLogs() {
        val files = logDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        // 先按数量轮转
        if (files.size >= MAX_LOG_FILES) {
            files.take(files.size - MAX_LOG_FILES + 1).forEach { runCatching { it.delete() } }
        }
        // 再按总大小轮转，防止单文件较小但总占用持续膨胀
        var remaining = logDir.listFiles()?.toList() ?: return
        var totalSize = remaining.sumOf { it.length() }
        while (totalSize > MAX_TOTAL_LOG_SIZE_BYTES && remaining.isNotEmpty()) {
            val oldest = remaining.minByOrNull { it.lastModified() } ?: break
            runCatching { oldest.delete() }
            remaining = logDir.listFiles()?.toList() ?: break
            totalSize = remaining.sumOf { it.length() }
        }
    }

    /**
     * 读取所有未展示的崩溃日志内容，不删除文件。
     * 用于应用启动时检测，方便用户导出后再清理。
     */
    fun readCrashLogs(): List<String> {
        if (!::logDir.isInitialized) return emptyList()
        val files = logDir.listFiles()?.sortedBy { it.lastModified() } ?: return emptyList()
        var total = 0L
        return files.mapNotNull { file ->
            if (total >= MAX_LOG_READ_TOTAL_BYTES) return@mapNotNull null
            val text = runCatching {
                file.bufferedReader(Charsets.UTF_8).use { reader ->
                    val sb = StringBuilder()
                    val buffer = CharArray(4096)
                    while (total < MAX_LOG_READ_TOTAL_BYTES) {
                        val maxChars = ((MAX_LOG_READ_TOTAL_BYTES - total) / 2).coerceAtLeast(1).toInt()
                        val read = reader.read(buffer, 0, minOf(buffer.size, maxChars))
                        if (read <= 0) break
                        sb.append(buffer, 0, read)
                        total += read * 2L // UTF-8 字符按 2 字节估算上限
                    }
                    sb.toString()
                }
            }.getOrNull()
            text
        }
    }

    /**
     * 读取并删除所有崩溃日志。
     * 应在用户确认已查看/导出后调用。
     */
    fun consumeCrashLogs(): List<String> {
        if (!::logDir.isInitialized) return emptyList()
        val files = logDir.listFiles()?.sortedBy { it.lastModified() } ?: return emptyList()
        var total = 0L
        val logs = files.mapNotNull { file ->
            if (total >= MAX_LOG_READ_TOTAL_BYTES) return@mapNotNull null
            val text = runCatching {
                file.bufferedReader(Charsets.UTF_8).use { reader ->
                    val sb = StringBuilder()
                    val buffer = CharArray(4096)
                    while (total < MAX_LOG_READ_TOTAL_BYTES) {
                        val maxChars = ((MAX_LOG_READ_TOTAL_BYTES - total) / 2).coerceAtLeast(1).toInt()
                        val read = reader.read(buffer, 0, minOf(buffer.size, maxChars))
                        if (read <= 0) break
                        sb.append(buffer, 0, read)
                        total += read * 2L
                    }
                    sb.toString()
                }
            }.getOrNull()
            text
        }
        files.forEach { runCatching { it.delete() } }
        return logs
    }

    /** 清空所有崩溃日志文件。 */
    fun clearCrashLogs() {
        if (!::logDir.isInitialized) return
        logDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /** 检查是否存在未展示的崩溃日志。 */
    fun hasCrashLogs(): Boolean {
        if (!::logDir.isInitialized) return false
        return (logDir.listFiles()?.size ?: 0) > 0
    }
}
