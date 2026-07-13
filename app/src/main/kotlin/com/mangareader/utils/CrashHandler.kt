package com.mangareader.utils

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
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
    private lateinit var logDir: File
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** 初始化全局异常捕获。在 Application.onCreate() 中调用。 */
    fun init(context: Context) {
        logDir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
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
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(logDir, "crash_$timestamp.log")
            val sw = StringWriter()
            PrintWriter(sw).use { pw ->
                pw.println("Time: ${Date()}")
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
            file.writeText(sw.toString())
        } catch (_: Exception) {
            // 日志写入失败时静默处理，避免二次崩溃
        }
    }

    /** 当日志文件数超过 [MAX_LOG_FILES] 时，删除最旧的。 */
    private fun rotateLogs() {
        val files = logDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size >= MAX_LOG_FILES) {
            files.take(files.size - MAX_LOG_FILES + 1).forEach { runCatching { it.delete() } }
        }
    }

    /**
     * 读取所有未展示的崩溃日志内容，并删除对应文件。
     * 应在应用启动、UI 准备好后调用，用于在弹窗中展示。
     */
    fun consumeCrashLogs(): List<String> {
        if (!::logDir.isInitialized) return emptyList()
        val files = logDir.listFiles()?.sortedBy { it.lastModified() } ?: return emptyList()
        val logs = files.mapNotNull { file ->
            runCatching { file.readText() }.getOrNull()
        }
        files.forEach { runCatching { it.delete() } }
        return logs
    }

    /** 检查是否存在未展示的崩溃日志。 */
    fun hasCrashLogs(): Boolean {
        if (!::logDir.isInitialized) return false
        return (logDir.listFiles()?.size ?: 0) > 0
    }
}
