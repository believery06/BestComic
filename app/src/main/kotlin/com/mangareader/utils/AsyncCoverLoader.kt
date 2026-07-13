package com.mangareader.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.mangareader.data.ComicEntry
import com.mangareader.data.ComicType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * 并行封面加载器：批量并行解码封面，避免顺序加载导致的 UI 卡顿。
 * 与 Coil 的 AsyncImage 配合使用：先由本工具快速提取并缓存封面文件 URI，
 * 再由 Coil 负责异步加载和显示。
 */
object ParallelCoverExtractor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 并行提取多个漫画的封面，并通过回调通知每张封面的提取结果。
     * 相比顺序提取，并行方式可同时利用多核 CPU，大幅缩短批量加载时间。
     */
    fun extractCoversParallel(
        context: Context,
        entries: List<ComicEntry>,
        onCoverExtracted: (entry: ComicEntry, coverUri: Uri?) -> Unit
    ) {
        scope.launch {
            val deferreds = entries.map { entry ->
                async {
                    val coverUri = if (entry.coverUri == null) {
                        CoverExtractor.extractCover(context, entry.uri, entry.type)
                    } else {
                        entry.coverUri
                    }
                    entry to coverUri
                }
            }
            deferreds.forEach { deferred ->
                val (entry, coverUri) = deferred.await()
                if (coverUri != null) {
                    onCoverExtracted(entry, coverUri)
                }
            }
        }
    }
}
