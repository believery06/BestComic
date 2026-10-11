package com.mangareader.cache

import android.content.Context
import java.io.File

/**
 * 启动时清理冗余磁盘缓存。
 *
 * 清理范围：
 * - 页面与缩略图磁盘缓存目录 page_cache、thumb_cache（仅由 DiskLruCache 自身 LRU 管理，启动时不全清）
 * - 解析器遗留的临时归档副本与临时目录，如 content:// 源的 CBR/CBT/MOBI/PDF 整本副本、旧版解压目录
 * - 分享源副本 source_share
 * - 分享卡片临时 PNG
 * - 崩溃日志导出副本 crash_logs
 *
 * 不清理：
 * - DataStore 中的书签、收藏、最近阅读、阅读进度等配置数据
 * - 封面缩略图 thumb_*，避免书架大量重新加载（由 CoverExtractor 按数量/容量上限自行管理）
 */
object CacheCleaner {

    /** 分享卡片、崩溃日志副本等小文件的保留时长（保留一天便于用户取用）。 */
    private const val MAX_CACHE_AGE_MS = 24L * 60 * 60 * 1000

    /** 解析器可能遗留的临时目录前缀（旧版整理解压目录）。 */
    private val TEMP_DIR_PREFIXES = listOf("cbt_", "cb7_", "cbz_", "cbr_")

    /**
     * 解析器可能遗留的临时文件前缀。
     * 其中 cbr_temp_ / cbt_temp_ / mobi_temp_ 为 content:// 源的整本副本，单个可达数百 MB。
     */
    private val TEMP_FILE_PREFIXES = listOf(
        "zip_prov_", "epub_", "pdf_", "temp_", "tmp_",
        "cbr_temp_", "cbt_temp_", "mobi_temp_", "mobi_thumb_",
        "cbz_", "cbr_", "cbt_", "cb7_", "cbt_index_"
    )

    fun cleanOnStartup(context: Context) {
        val cacheDir = context.cacheDir ?: return
        // 记录进程启动时刻：早于该时刻的临时文件必然由上一个已结束的进程创建，
        // 当前进程不可能正在使用，因此可安全无条件删除，无需再等待 24 小时。
        // 这是修复「读完退出应用后，数百 MB 的归档临时副本仍长期占用存储」的关键。
        val startupTime = System.currentTimeMillis()
        runCatching { cleanShareCards(cacheDir) }
        runCatching { cleanStaleTempFiles(cacheDir, startupTime) }
        // 分享源目录存放整本漫画副本，同样清理上一进程遗留。
        runCatching { cleanStaleDirChildren(cacheDir, "source_share", startupTime) }
        runCatching { cleanOldTransientDir(cacheDir, "crash_logs") }
        // PdfParser 在 cacheDir 空间不足时会回退到 filesDir，同样清理该处遗留临时文件。
        context.filesDir?.let { runCatching { cleanStaleTempFiles(it, startupTime) } }
    }

    /** 清理超过 [MAX_CACHE_AGE_MS] 的分享卡片临时 PNG。 */
    private fun cleanShareCards(cacheDir: File) {
        val now = System.currentTimeMillis()
        val isShareCard = { f: File ->
            f.isFile && f.name.startsWith("share_card_") && f.name.endsWith(".png")
        }
        // 现行写入位置：cacheDir/share_cards/
        File(cacheDir, "share_cards").listFiles()?.filter(isShareCard)?.forEach {
            if (now - it.lastModified() > MAX_CACHE_AGE_MS) {
                runCatching { it.delete() }
            }
        }
        // 兼容历史版本：旧版曾把分享卡片直接写在 cacheDir 根目录，导致永不清理。
        cacheDir.listFiles()?.filter(isShareCard)?.forEach {
            if (now - it.lastModified() > MAX_CACHE_AGE_MS) {
                runCatching { it.delete() }
            }
        }
    }

    /**
     * 删除目录中早于 [cutoffTime] 的解析器临时文件/目录。
     * 早于当前进程启动时刻的文件必定属于上一个已结束的进程，可无条件删除。
     */
    private fun cleanStaleTempFiles(dir: File, cutoffTime: Long) {
        dir.listFiles()?.forEach { child ->
            if (child.lastModified() >= cutoffTime) return@forEach
            when {
                child.isDirectory && TEMP_DIR_PREFIXES.any { child.name.startsWith(it) } ->
                    runCatching { child.deleteRecursively() }
                child.isFile && TEMP_FILE_PREFIXES.any { child.name.startsWith(it) } ->
                    runCatching { child.delete() }
            }
        }
    }

    /**
     * 删除 [name] 目录中早于 [cutoffTime] 的子项，不删除目录本身。
     * 用于清理分享源等「上一进程遗留」的临时副本。
     */
    private fun cleanStaleDirChildren(cacheDir: File, name: String, cutoffTime: Long) {
        File(cacheDir, name).listFiles()?.forEach { child ->
            if (child.lastModified() < cutoffTime) {
                runCatching { child.deleteRecursively() }
            }
        }
    }

    /**
     * 清理指定临时目录中超过 [MAX_CACHE_AGE_MS] 的旧文件。
     * 不删除目录本身，避免影响正在进行的导出操作。
     */
    private fun cleanOldTransientDir(cacheDir: File, name: String) {
        val now = System.currentTimeMillis()
        File(cacheDir, name).listFiles()?.forEach { child ->
            if (now - child.lastModified() > MAX_CACHE_AGE_MS) {
                runCatching { child.deleteRecursively() }
            }
        }
    }

    /** 计算目录总大小（字节）。 */
    fun calcSize(dir: File): Long {
        return runCatching {
            dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }
}