package com.mangareader.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import com.mangareader.provider.ComicProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.util.Collections

/**
 * 页面缓存管理器。
 *
 * 新策略：
 * - L1 内存缓存：以当前页为中心，前后各 [PREFETCH_RANGE] 页（默认 15，总约 31 页）。
 *   用户翻到任意一页时，该页附近的页面大概率已在内存，翻页更流畅。
 * - L2 磁盘缓存：仅缓存原始图片字节，默认上限 30MB（页面）+ 10MB（缩略图），LRU 淘汰。
 * - 解码策略：按屏幕尺寸 1.5 倍采样，普通图片用 RGB_565；PDF 等系统要求用 ARGB_8888。
 * - 翻页时先同步加载当前页，再在后台按距离优先级预加载前后页。
 * - 切换漫画或退出阅读时清空 L1 内存缓存，避免不同漫画/会话之间互相占用。
 */
class PageCacheManager(context: Context) {

    /** 预加载方向：滚动模式下按滚动方向优先加载，避免视觉上的“不按顺序加载”。 */
    enum class PrefetchDirection { BOTH, FORWARD, BACKWARD }

    companion object {
        private const val TAG = "PageCacheManager"
        // 当前页前后各预加载 15 页，总缓存窗口约 31 页
        private const val PREFETCH_RANGE = 15
        // 后台预加载并发度：适当提高并发以提升快速滚动时的加载速度
        private const val PREFETCH_CONCURRENCY = 3
    }

    // 以页索引为 key 的内存缓存；synchronizedMap 保证读写线程安全
    private val memoryCache = Collections.synchronizedMap(LinkedHashMap<Int, Bitmap>())

    @Volatile
    private var currentCacheId: String? = null

    @Volatile
    private var currentIndexValue: Int = -1

    private var prefetchJob: Job? = null
    private val prefetchSemaphore = Semaphore(PREFETCH_CONCURRENCY)

    private val pageDiskCache = DiskLruCache(context.cacheDir, "page_cache", 30L * 1024 * 1024)
    private val thumbDiskCache = DiskLruCache(context.cacheDir, "thumb_cache", 10L * 1024 * 1024)

    private var screenSize: Size = Size(1080, 1920)

    fun setScreenSize(width: Int, height: Int) {
        screenSize = Size(width, height)
    }

    /**
     * 翻到 [index] 时调用。
     * - 若换了漫画，清空 L1 内存与 L2 磁盘缓存
     * - 同步确保当前页在内存
     * - 后台按 [direction] 优先级预加载前后 [PREFETCH_RANGE] 页
     */
    suspend fun setCurrentPage(
        provider: ComicProvider,
        index: Int,
        direction: PrefetchDirection = PrefetchDirection.BOTH
    ) {
        if (index !in 0 until provider.pageCount) return

        withContext(Dispatchers.IO) {
            // 切换漫画：清空所有缓存，避免上一本的位图/磁盘数据占用空间
            if (currentCacheId != provider.cacheId) {
                clearMemoryInternal()
                pageDiskCache.clear()
                thumbDiskCache.clear()
                currentCacheId = provider.cacheId
            }

            currentIndexValue = index

            // 1. 同步加载当前页（阻塞，保证 UI 立刻能显示）
            loadIntoMemory(provider, index)

            // 2. 取消旧预加载任务
            prefetchJob?.cancelAndJoin()

            // 3. 启动新预加载任务
            prefetchJob = launch {
                prefetchRange(provider, index, direction)
            }
        }
    }

    /**
     * 获取指定页位图。优先内存缓存，其次磁盘，最后从 provider 读取。
     * 此方法不主动写入内存缓存，仅作为数据读取入口。
     */
    suspend fun getPage(provider: ComicProvider, index: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (index !in 0 until provider.pageCount) return@withContext null

        // 1. 内存命中
        synchronized(memoryCache) {
            memoryCache[index]?.takeIf { !it.isRecycled }
        }?.let { return@withContext it }

        // 2. 磁盘缓存（原始字节）
        val bytesFromDisk = loadBytesFromDisk(provider, index)
        if (bytesFromDisk != null) {
            val decoded = decodeBytes(bytesFromDisk)
            if (decoded != null) return@withContext decoded
        }

        // 3. 从 provider 读取原始字节并缓存到磁盘
        val bytes = readPageBytes(provider, index)
        if (bytes != null) {
            saveBytesToDisk(provider, index, bytes)
            val decoded = decodeBytes(bytes)
            if (decoded != null) return@withContext decoded
        }

        // 4. fallback：provider 无法提供字节流（如 PDF），直接获取 Bitmap
        runCatching { provider.getPage(index) }.getOrNull()?.takeIf { !it.isRecycled }
    }

    /**
     * 获取缩略图（独立缓存体系，最大边不超过 [maxDimension]）。
     */
    suspend fun getThumbnail(provider: ComicProvider, index: Int, maxDimension: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            if (index !in 0 until provider.pageCount) return@withContext null
            val dim = maxDimension.coerceIn(64, 360)

            // 1. 优先从内存缓存获取已有位图并缩放
            val cachedBmp = synchronized(memoryCache) {
                memoryCache[index]?.takeIf { !it.isRecycled }
            }
            if (cachedBmp != null) {
                return@withContext scaleBitmapForThumb(cachedBmp, dim)
            }

            // 2. 磁盘缩略图缓存
            val key = thumbKey(provider.cacheId, index, dim)
            thumbDiskCache.getStream(key)?.use { stream ->
                BitmapFactory.decodeStream(stream)?.takeIf { !it.isRecycled }
            }?.let { return@withContext it }

            // 3. 从原始字节解码缩略图
            val bytes = readPageBytes(provider, index)
            if (bytes != null && bytes.isNotEmpty()) {
                val decoded = decodeBytes(bytes, dim, dim)
                if (decoded != null) {
                    thumbDiskCache.put(key) { file ->
                        FileOutputStream(file).use { out ->
                            val fmt = if (decoded.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                            decoded.compress(fmt, 85, out)
                        }
                    }
                    return@withContext decoded
                }
            }

            // 4. fallback：用 provider 直接获取位图再缩放
            val fullBmp = runCatching { provider.getPage(index) }.getOrNull()
                ?.takeIf { !it.isRecycled } ?: return@withContext null
            val thumb = scaleBitmapForThumb(fullBmp, dim)
            if (thumb !== fullBmp && !fullBmp.isRecycled) runCatching { fullBmp.recycle() }
            thumb
        }

    /** 主动预加载一页到内存缓存。 */
    suspend fun preload(provider: ComicProvider, index: Int) {
        if (index !in 0 until provider.pageCount) return
        withContext(Dispatchers.IO) {
            loadIntoMemory(provider, index)
        }
    }

    /** 清空 L1 内存缓存（退出阅读/切换漫画时调用），保留 L2 磁盘缓存。 */
    fun clearMemory() {
        clearMemoryInternal()
    }

    /** 清空 L1 内存 + L2 磁盘缓存。 */
    fun clear() {
        clearMemoryInternal()
        pageDiskCache.clear()
        thumbDiskCache.clear()
    }

    /** 仅清空磁盘缓存。 */
    fun clearDisk() {
        pageDiskCache.clear()
        thumbDiskCache.clear()
    }

    /** 低内存时只保留当前页。 */
    fun onLowMemory() {
        val keep = currentIndexValue
        synchronized(memoryCache) {
            val iterator = memoryCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key == keep) continue
                entry.value.takeIf { !it.isRecycled }?.let { runCatching { it.recycle() } }
                iterator.remove()
            }
        }
    }

    /** 后台预加载当前页前后 [PREFETCH_RANGE] 页，按 [direction] 决定优先级。 */
    private suspend fun prefetchRange(
        provider: ComicProvider,
        center: Int,
        direction: PrefetchDirection
    ) {
        val start = (center - PREFETCH_RANGE).coerceAtLeast(0)
        val end = (center + PREFETCH_RANGE).coerceAtMost(provider.pageCount - 1)

        // 构建按方向优先的加载序列：
        // - FORWARD：先顺序加载后面页面，再加载前面页面
        // - BACKWARD：先顺序加载前面页面，再加载后面页面
        // - BOTH：按距离当前页由近及远排序
        val indices = when (direction) {
            PrefetchDirection.FORWARD -> {
                val forward = (center + 1..end).toList()
                val backward = (center - 1 downTo start).toList()
                forward + backward
            }
            PrefetchDirection.BACKWARD -> {
                val backward = (center - 1 downTo start).toList()
                val forward = (center + 1..end).toList()
                backward + forward
            }
            PrefetchDirection.BOTH -> {
                (start..end)
                    .filter { it != center }
                    .sortedBy { kotlin.math.abs(it - center) }
            }
        }

        for (idx in indices) {
            if (!currentCoroutineContext().isActive) return
            // 已在内存则跳过
            val cached = synchronized(memoryCache) {
                memoryCache[idx]?.takeIf { !it.isRecycled }
            }
            if (cached != null) continue

            prefetchSemaphore.withPermit {
                loadIntoMemory(provider, idx)
            }
        }
    }

    /** 加载一页并放入内存缓存。 */
    private suspend fun loadIntoMemory(provider: ComicProvider, index: Int) {
        if (index !in 0 until provider.pageCount) return

        // 再次检查，避免重复加载
        synchronized(memoryCache) {
            memoryCache[index]?.takeIf { !it.isRecycled }
        }?.let { return }

        val bmp = getPage(provider, index) ?: return
        val old = synchronized(memoryCache) {
            memoryCache.put(index, bmp)
        }
        // 如果 map 中已有同索引位图（理论上不应发生），回收旧的
        if (old != null && old !== bmp && !old.isRecycled) {
            runCatching { old.recycle() }
        }
    }

    private fun clearMemoryInternal() {
        prefetchJob?.cancel()
        prefetchJob = null
        synchronized(memoryCache) {
            for (bmp in memoryCache.values) {
                if (!bmp.isRecycled) runCatching { bmp.recycle() }
            }
            memoryCache.clear()
        }
        currentIndexValue = -1
    }

    private suspend fun readPageBytes(provider: ComicProvider, index: Int): ByteArray? {
        return try {
            provider.getPageStream(index)?.use { stream -> stream.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeBytes(bytes: ByteArray, reqWidth: Int? = null, reqHeight: Int? = null): Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val targetW = reqWidth ?: (screenSize.width * 1.5f).toInt().coerceAtLeast(1)
            val targetH = reqHeight ?: (screenSize.height * 1.5f).toInt().coerceAtLeast(1)

            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

            val sample = calculateInSampleSize(
                boundsOptions.outWidth, boundsOptions.outHeight, targetW, targetH
            )

            val hasAlpha = boundsOptions.outMimeType?.equals("image/png", ignoreCase = true) == true
                    || boundsOptions.outMimeType?.equals("image/webp", ignoreCase = true) == true
            val config = if (hasAlpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = config
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "decodeBytes failed: ${e.message}")
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        if (width <= 0 || height <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1
        var inSampleSize = 1
        var halfWidth = width
        var halfHeight = height
        while (halfWidth / 2 >= reqWidth && halfHeight / 2 >= reqHeight) {
            halfWidth /= 2
            halfHeight /= 2
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun loadBytesFromDisk(provider: ComicProvider, index: Int): ByteArray? {
        val key = pageKey(provider.cacheId, index)
        return try {
            pageDiskCache.getStream(key)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveBytesToDisk(provider: ComicProvider, index: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val key = pageKey(provider.cacheId, index)
        pageDiskCache.put(key) { file ->
            FileOutputStream(file).use { out ->
                out.write(bytes)
                out.flush()
            }
        }
    }

    private fun pageKey(id: String, index: Int) = "${sha256(id)}#p#$index"
    private fun thumbKey(id: String, index: Int, dim: Int) = "${sha256(id)}#t#$index#$dim"

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun scaleBitmapForThumb(src: Bitmap, maxDim: Int): Bitmap {
        if (src.isRecycled) return src
        val scale = (maxDim.toFloat() / maxOf(src.width, src.height)).coerceAtMost(1f)
        if (scale >= 1f) return src
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(src, w, h, true)
        } catch (e: OutOfMemoryError) {
            src
        }
    }
}
