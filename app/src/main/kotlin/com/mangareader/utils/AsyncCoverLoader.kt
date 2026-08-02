package com.mangareader.utils

import android.content.Context
import android.net.Uri
import com.mangareader.data.ComicEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 并行封面加载器：批量并行解码封面，避免顺序加载导致的 UI 卡顿。
 * 与 Coil 的 AsyncImage 配合使用：先由本工具快速提取并缓存封面文件 URI，
 * 再由 Coil 负责异步加载和显示。
 */
object ParallelCoverExtractor {

    /**
     * 并行提取多个漫画的封面，并通过回调通知每张封面的提取结果。
     * 相比顺序提取，并行方式可同时利用多核 CPU，大幅缩短批量加载时间。
     *
     * 此函数为 suspend 函数，受调用方作用域生命周期约束，内部使用信号量限制最大并发数，
     * 避免同时解码过多封面导致内存/线程压力。
     */
    suspend fun extractCoversParallel(
        context: Context,
        entries: List<ComicEntry>,
        onCoverExtracted: (entry: ComicEntry, coverUri: Uri?) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(6)
        val results = entries.map { entry ->
            async {
                semaphore.withPermit {
                    val coverUri = if (entry.coverUri == null) {
                        try {
                            CoverExtractor.extractCover(context, entry.uri, entry.type)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        entry.coverUri
                    }
                    entry to coverUri
                }
            }
        }.awaitAll()
        results.forEach { (entry, coverUri) ->
            if (coverUri != null) {
                onCoverExtracted(entry, coverUri)
            }
        }
    }
}
