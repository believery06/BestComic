package com.mangareader.cache

import android.content.Context
import java.io.File

/**
 * 启动时清理冗余磁盘缓存。
 *
 * 清理范围：
 * - 页面与缩略图磁盘缓存目录 page_cache、thumb_cache（仅由 DiskLruCache 自身 LRU 管理，启动时不全清）
 * - 分享卡片临时 PNG
 * - 分享源文件副本 source_share
 * - 崩溃日志导出副本 crash_logs
 * - 解析器泄漏的临时目录与文件，如 cbt/cb7 解压目录、zip_prov、epub、pdf 等临时文件
 *
 * 不清理：
 * - DataStore 中的书签、收藏、最近阅读、阅读进度等配置数据
 * - 封面缩略图，避免书架大量重新加载
 */
object CacheCleaner {

    private const val MAX_CACHE_AGE_MS = 24L * 60 * 60 * 1000

    fun cleanOnStartup(context: Context) {
        val cacheDir = context.cacheDir ?: return
        // 不再清空 page_cache / thumb_cache：它们由 DiskLruCache 自身 LRU 策略管理，
        // 启动时全清会失去跨会话缓存复用的意义，拖慢二次打开速度。
        runCatching { cleanShareCards(cacheDir) }
        runCatching { cleanTransientDir(cacheDir, "source_share") }
        runCatching { cleanTransientDir(cacheDir, "crash_logs") }
        runCatching { cleanOldTempFiles(cacheDir) }
        // PdfParser 在 cacheDir 空间不足时会回退到 filesDir，同样清理该处旧临时文件。
        context.filesDir?.let { runCatching { cleanOldTempFiles(it) } }
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
     * 清理指定临时目录中超过 [MAX_CACHE_AGE_MS] 的旧文件。
     * 不删除目录本身，避免影响正在进行的分享/导出操作。
     */
    private fun cleanTransientDir(cacheDir: File, name: String) {
        val now = System.currentTimeMillis()
        File(cacheDir, name).listFiles()?.forEach { child ->
            if (now - child.lastModified() > MAX_CACHE_AGE_MS) {
                runCatching { child.deleteRecursively() }
            }
        }
    }

    /**
     * 清理解析器可能泄漏的临时文件/目录。
     * 仅删除超过 [MAX_CACHE_AGE_MS] 的旧文件，避免误删当前正在使用的资源。
     */
    private fun cleanOldTempFiles(cacheDir: File) {
        val now = System.currentTimeMillis()
        // 旧版 CBT/CB7 整理解压遗留目录；新版 CBT 使用 cbt_temp_*.cbt 临时归档文件。
        val tempDirPrefixes = listOf("cbt_", "cb7_", "cbz_", "cbr_")
        // pdf_ 由 PdfRenderer.createTempFile 生成；cbr_temp_/cbt_temp_ 为 content:// 源回退副本；
        // tmp_/temp_ 为通用临时文件；cbz_/cbr_/cbt_/cb7_ 为旧版解析器可能遗留的压缩包副本；
        // mobi_temp_/mobi_thumb_ 为 MOBI 解析与封面提取的 content:// 源回退副本。
        val tempFilePrefixes = listOf(
            "zip_prov_", "epub_", "pdf_", "temp_", "tmp_",
            "cbr_temp_", "cbt_temp_", "cbz_", "cbr_", "cbt_", "cb7_",
            "cbt_index_", "mobi_temp_", "mobi_thumb_"
        )

        cacheDir.listFiles()?.forEach { child ->
            if (child.isDirectory && tempDirPrefixes.any { child.name.startsWith(it) }) {
                if (now - child.lastModified() > MAX_CACHE_AGE_MS) {
                    runCatching { child.deleteRecursively() }
                }
            } else if (child.isFile && tempFilePrefixes.any { child.name.startsWith(it) }) {
                if (now - child.lastModified() > MAX_CACHE_AGE_MS) {
                    runCatching { child.delete() }
                }
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
