package com.mangareader

import android.app.Application
import com.mangareader.utils.CrashHandler
import java.io.File

class MangaReaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
        cleanOldTempFiles()
    }

    /**
     * 清理超过 24 小时的旧临时文件，防止 CBT/CB7 解析器因进程被杀导致的临时文件泄漏。
     */
    private fun cleanOldTempFiles() {
        val cacheDir = cacheDir ?: return
        val maxAge = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        val dirs = cacheDir.listFiles()?.filter { dir ->
            dir.isDirectory && (dir.name.startsWith("cbt_") || dir.name.startsWith("cb7_"))
        } ?: return
        for (dir in dirs) {
            if (now - dir.lastModified() > maxAge) {
                runCatching { dir.deleteRecursively() }
            }
        }
    }
}
