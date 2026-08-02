package com.mangareader.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.util.LruCache
import android.util.Size
import com.mangareader.data.ComicProvider
import com.mangareader.utils.BitmapPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.util.Collections

class PageCacheManager(context: Context) {

    enum class PrefetchDirection { BOTH, FORWARD, BACKWARD }

    enum class Aggressiveness { LOW, BALANCED, HIGH }

    companion object {
        private const val TAG = "PageCacheManager"
        private const val PREFETCH_CONCURRENCY = 3
        private const val DECODE_CONCURRENCY = 2
        private const val MAX_IMAGE_DIMENSION = 8000

        @Volatile
        private var instance: PageCacheManager? = null

        /**
         * 获取全局单例，确保磁盘缓存只被一个实例管理，避免多实例操作同一目录导致不一致。
         */
        fun getInstance(context: android.content.Context): PageCacheManager {
            return instance ?: synchronized(this) {
                instance ?: PageCacheManager(context.applicationContext).also { instance = it }
            }
        }

        /** 根据设备堆内存动态计算 L1 内存缓存上限（1/5 堆内存，限制在 60-200MB），
         *  在保证不 OOM 的前提下为 PDF 大图保留更多相邻页。
         */
        private fun memoryLimitBytes(): Long {
            val maxMemory = Runtime.getRuntime().maxMemory()
            return (maxMemory / 5).coerceIn(60L * 1024 * 1024, 200L * 1024 * 1024)
        }
    }

    private val memoryLimitBytes = memoryLimitBytes()

    /**
     * 真正的 LRU 内存缓存：按位图占用字节数计数，淘汰时自动回收旧位图。
     * UI 层从缓存取出后会复制独立副本，因此此处回收不会导致绘制崩溃。
     */
    private fun safeBitmapSize(bmp: Bitmap?): Int {
        if (bmp == null || bmp.isRecycled) return 1
        return bmp.allocationByteCount.coerceAtLeast(1)
    }

    private val memoryCache = object : LruCache<Int, Bitmap>(memoryLimitBytes.toInt()) {
        override fun sizeOf(key: Int, value: Bitmap): Int {
            return safeBitmapSize(value)
        }

        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap?, newValue: Bitmap?) {
            if (oldValue != null && !oldValue.isRecycled) {
                if (evicted) {
                    BitmapPool.put(oldValue)
                } else {
                    runCatching { oldValue.recycle() }
                }
            }
        }
    }

    /**
     * 缩略图专用内存缓存，与主页面缓存分离：
     * - 尺寸小（通常 64-360px）、数量多，需要独立的 8MB 上限避免挤掉全尺寸页面。
     * - 向 UI 返回安全副本，避免 UI 回收后污染缓存中的原图。
     */
    private data class ThumbnailKey(val cacheId: String, val index: Int, val dim: Int)

    private val thumbMemoryCache = object : LruCache<ThumbnailKey, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: ThumbnailKey, value: Bitmap): Int {
            return safeBitmapSize(value)
        }

        override fun entryRemoved(evicted: Boolean, key: ThumbnailKey, oldValue: Bitmap?, newValue: Bitmap?) {
            oldValue?.takeIf { !it.isRecycled }?.let { runCatching { it.recycle() } }
        }
    }

    // 用于保证同一时刻只有一个协程加载指定页，避免重复解码导致的内存抖动。
    // 使用 Mutex 而非 synchronized，可在挂起函数 getPage 调用期间阻塞其他协程，
    // 确保同一个 index 只被加载一次。
    private val loadLocks = java.util.concurrent.ConcurrentHashMap<Int, Mutex>()

    // 缩略图并发加载锁，按 (cacheId, index, dim) 维度去重，避免同一张缩略图同时解码多次。
    private val thumbLoadLocks = java.util.concurrent.ConcurrentHashMap<ThumbnailKey, Mutex>()

    @Volatile
    private var currentCacheId: String? = null

    @Volatile
    private var currentIndexValue: Int = -1

    @Volatile
    private var prefetchJob: Job? = null
    @Volatile
    private var isShutdown = false
    private val prefetchSemaphore = Semaphore(PREFETCH_CONCURRENCY)
    private val decodeSemaphore = Semaphore(DECODE_CONCURRENCY)

    private val pageDiskCache = DiskLruCache(context.cacheDir, "page_cache", 30L * 1024 * 1024)
    private val thumbDiskCache = DiskLruCache(context.cacheDir, "thumb_cache", 10L * 1024 * 1024)

    private val diskKeyIndex = Collections.synchronizedSet(java.util.HashSet<String>())

    @Volatile
    private var screenSize: Size = Size(1080, 1920)

    @Volatile
    private var prefetchRangeSize = 15

    @Volatile
    private var scrollSpeedTracker = ScrollSpeedTracker()

    /** 独立的缓存协程作用域，预读任务在此启动，避免阻塞 setCurrentPage 的调用方。 */
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun setScreenSize(width: Int, height: Int) {
        screenSize = Size(width, height)
    }

    fun setAggressiveness(level: Aggressiveness) {
        prefetchRangeSize = when (level) {
            Aggressiveness.LOW -> 5
            Aggressiveness.BALANCED -> 15
            Aggressiveness.HIGH -> 25
        }
    }

    fun getCurrentPrefetchRange(): Int = prefetchRangeSize

    private fun updateScrollSpeed(index: Int) {
        scrollSpeedTracker.onPageChanged(index)
        val speed = scrollSpeedTracker.getCurrentSpeed()
        prefetchRangeSize = when {
            speed > 5 -> 25
            speed > 2 -> 15
            else -> 8
        }
    }

    suspend fun setCurrentPage(
        provider: ComicProvider,
        index: Int,
        direction: PrefetchDirection = PrefetchDirection.BOTH
    ) {
        if (isShutdown || index !in 0 until provider.pageCount) return

        withContext(Dispatchers.IO) {
            if (currentCacheId != provider.cacheId) {
                val oldCacheId = currentCacheId
                clearMemory()
                // 只清理旧漫画的磁盘缓存，保留其他漫画的跨会话缓存，避免切换时全部清空。
                oldCacheId?.let { clearByCacheId(it) }
                currentCacheId = provider.cacheId
            }

            currentIndexValue = index
            updateScrollSpeed(index)

            // 滑动窗口清理：只保留当前页附近的页面，释放远端页面内存
            evictOutsideWindow(index)

            // 先取消上一轮预加载，避免其占用 PDF 渲染锁导致当前页加载阻塞
            prefetchJob?.cancelAndJoin()
            prefetchJob = null

            // 优先加载当前页，确保用户立即看到内容
            loadIntoMemory(provider, index)

            prefetchJob = launch {
                try {
                    prefetchRange(provider, index, direction)
                } catch (e: CancellationException) {
                    // 预读任务被取消时静默结束，不记录为错误。
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Prefetch failed", e)
                }
            }
        }
    }

    /** 仅保留当前页附近的滑动窗口页面，超出窗口立即回收。 */
    private fun evictOutsideWindow(center: Int) {
        val window = getWindowSize()
        val minIndex = (center - window).coerceAtLeast(0)
        val maxIndex = (center + window).coerceAtMost(Int.MAX_VALUE)
        // 使用 snapshot 避免在迭代 LruCache 时修改
        for (key in memoryCache.snapshot().keys) {
            if (key !in minIndex..maxIndex) {
                memoryCache.remove(key)
            }
        }
    }

    /** 内存窗口大小与当前预加载范围保持一致。
     *  避免预读任务加载的远端页面被立即释放，同时根据滚动速度动态收缩窗口。
     */
    private fun getWindowSize(): Int = prefetchRangeSize.coerceAtLeast(1)

    /**
     * 内部方法：获取或解码页面原始Bitmap，不经过副本复制。
     * 返回值可能是：memoryCache中的引用、新解码的Bitmap、或provider直接渲染的Bitmap。
     * 调用方必须负责将返回的Bitmap放入memoryCache（如果还没有的话），绝不能recycle从memoryCache命中的Bitmap。
     */
    private suspend fun getOrDecodePage(provider: ComicProvider, index: Int): Bitmap? {
        if (isShutdown || index !in 0 until provider.pageCount) return null

        memoryCache.get(index)?.takeIf { !it.isRecycled }?.let { return it }

        val bytesFromDisk = loadBytesFromDisk(provider, index)
        if (bytesFromDisk != null) {
            val decoded = decodeBytes(bytesFromDisk)
            if (decoded != null) return decoded
        }

        val bytes = readPageBytes(provider, index)
        if (bytes != null) {
            saveBytesToDisk(provider, index, bytes)
            val decoded = decodeBytes(bytes)
            if (decoded != null) return decoded
        }

        val bmp = try {
            provider.getPage(index)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Provider 获取页面失败: index=$index", e)
            null
        }?.takeIf { !it.isRecycled }
        if (bmp != null) {
            saveBitmapToDisk(provider, index, bmp)
            return bmp
        }

        return null
    }

    /**
     * 公共API：获取页面Bitmap，始终返回独立副本，调用方可以安全使用和回收。
     * 如果页面尚未在内存缓存中，会先解码并加入缓存后再返回副本。
     */
    suspend fun getPage(provider: ComicProvider, index: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (isShutdown || index !in 0 until provider.pageCount) return@withContext null

        val cached = memoryCache.get(index)?.takeIf { !it.isRecycled }
        if (cached != null) {
            return@withContext createBitmapCopy(cached)
        }

        val raw = getOrDecodePage(provider, index) ?: return@withContext null
        if (raw.isRecycled) return@withContext null

        val toCopy = if (memoryCache.get(index)?.takeIf { !it.isRecycled } == null) {
            memoryCache.put(index, raw)
            raw
        } else {
            if (!raw.isRecycled && raw !== memoryCache.get(index)) {
                runCatching { raw.recycle() }
            }
            memoryCache.get(index)
        }

        if (toCopy != null && !toCopy.isRecycled) {
            createBitmapCopy(toCopy)
        } else {
            null
        }
    }

    suspend fun getThumbnail(provider: ComicProvider, index: Int, maxDimension: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            if (isShutdown || index !in 0 until provider.pageCount) return@withContext null
            val dim = maxDimension.coerceIn(64, 360)
            val key = thumbKey(provider.cacheId, index, dim)
            val tKey = ThumbnailKey(provider.cacheId, index, dim)

            // 快速路径：缩略图专用内存缓存命中后返回安全副本。
            thumbMemoryCache.get(tKey)?.takeIf { !it.isRecycled }?.let {
                return@withContext createBitmapCopy(it)
            }

            val mutex = thumbLoadLocks.computeIfAbsent(tKey) { Mutex() }
            try {
                mutex.withLock {
                    if (!currentCoroutineContext().isActive) return@withContext null
                    thumbMemoryCache.get(tKey)?.takeIf { !it.isRecycled }?.let {
                        return@withContext createBitmapCopy(it)
                    }

                    thumbDiskCache.getStream(key)?.use { stream ->
                        BitmapFactory.decodeStream(stream)?.takeIf { !it.isRecycled }
                    }?.let { decoded ->
                        thumbMemoryCache.put(tKey, decoded)
                        addToDiskKeyIndex(key)
                        return@withContext createBitmapCopy(decoded)
                    }

                    val bytes = readPageBytes(provider, index)
                    if (bytes != null && bytes.isNotEmpty()) {
                        val decoded = decodeBytes(bytes, dim, dim)
                        if (decoded != null) {
                            saveThumbToDisk(key, decoded)
                            thumbMemoryCache.put(tKey, decoded)
                            addToDiskKeyIndex(key)
                            return@withContext createBitmapCopy(decoded)
                        }
                    }

                    val fullBmp = runCatching { provider.getPage(index) }.getOrNull()?.takeIf { !it.isRecycled }
                        ?: return@withContext null
                    val thumb = runCatching { scaleBitmapForThumb(fullBmp, dim) }.getOrNull()
                    if (thumb == null || thumb !== fullBmp) {
                        if (!fullBmp.isRecycled) runCatching { fullBmp.recycle() }
                    }
                    if (thumb == null) return@withContext null

                    saveThumbToDisk(key, thumb)
                    thumbMemoryCache.put(tKey, thumb)
                    addToDiskKeyIndex(key)
                    return@withContext createBitmapCopy(thumb)
                }
            } finally {
                thumbLoadLocks.remove(tKey)
            }
        }

    suspend fun preload(provider: ComicProvider, index: Int) {
        if (isShutdown || index !in 0 until provider.pageCount) return
        withContext(Dispatchers.IO) {
            loadIntoMemory(provider, index)
        }
    }

    fun clearMemory() {
        prefetchJob?.cancel()
        prefetchJob = null
        // 直接 evictAll，由 entryRemoved 统一把可复用位图回收到 BitmapPool。
        // 注意：不要在此处先手动遍历 snapshot 预放入 BitmapPool，否则 BitmapPool
        // 可能在本该参与 LruCache sizeOf 计算的位图被回收后，导致 LruCache 内部
        // 累计大小与 sizeOf 返回值不一致，抛出 IllegalStateException。
        memoryCache.evictAll()
        thumbMemoryCache.evictAll()
        thumbLoadLocks.clear()
        BitmapPool.clear()
        currentIndexValue = -1
    }

    fun clear() {
        clearMemory()
        clearDiskInternal()
    }

    fun clearDisk() {
        clearDiskInternal()
    }

    /**
     * 释放缓存管理器持有的后台协程作用域与磁盘缓存 trim 线程。
     * 应在应用退出或缓存不再需要时调用，避免线程泄漏。
     */
    fun shutdown() {
        if (isShutdown) return
        synchronized(this) {
            if (isShutdown) return
            isShutdown = true
            prefetchJob?.cancel()
            prefetchJob = null
            cacheScope.cancel()
            // 等待后台预读/写入任务结束，避免磁盘缓存关闭时仍有文件写入导致异常。
            runCatching {
                runBlocking {
                    withTimeoutOrNull(2_000L) {
                        cacheScope.coroutineContext[Job]?.cancelAndJoin()
                    }
                }
            }
            // 先清空内存缓存，避免作用域关闭后仍有 Bitmap 未被回收。
            memoryCache.evictAll()
            thumbMemoryCache.evictAll()
            thumbLoadLocks.clear()
            BitmapPool.clear()
            pageDiskCache.shutdown()
            thumbDiskCache.shutdown()
        }
    }

    /** 仅清除页面磁盘缓存。 */
    fun clearPageDisk() {
        pageDiskCache.clear()
    }

    /** 仅清除缩略图磁盘缓存。 */
    fun clearThumbDisk() {
        thumbDiskCache.clear()
    }

    fun onLowMemory() {
        trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE)
    }

    /**
     * 根据系统内存压力级别整理缓存。
     *
     * - RUNNING_LOW/MODERATE：保留当前页，移除其它内存缓存。
     * - BACKGROUND/MODERATE/COMPLETE：清空全部内存缓存与 Bitmap 复用池。
     */
    fun trimMemory(level: Int) {
        val isCritical = level == android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (isCritical) {
            clearMemoryCache()
        } else {
            val keep = currentIndexValue
            // 直接 remove，由 entryRemoved 统一回收；避免先 put 到 BitmapPool 导致位图
            // 在被 LruCache 统计前就被回收，触发 sizeOf 不一致异常。
            for (key in memoryCache.snapshot().keys) {
                if (key != keep) {
                    memoryCache.remove(key)
                }
            }
            BitmapPool.clear()
        }
    }

    /** 清空全部内存缓存与 Bitmap 复用池，仅在系统内存极度紧张或用户主动清理时调用。 */
    fun clearMemoryCache() {
        memoryCache.evictAll()
        thumbMemoryCache.evictAll()
        thumbLoadLocks.clear()
        BitmapPool.clear()
    }

    private suspend fun prefetchRange(
        provider: ComicProvider,
        center: Int,
        direction: PrefetchDirection
    ) {
        // 快速滑动时只预加载相邻页面，避免大量后台渲染拖垮 CPU
        val speed = scrollSpeedTracker.getCurrentSpeed()
        val effectiveRange = when {
            speed > 8 -> 1
            speed > 4 -> prefetchRangeSize / 2
            else -> prefetchRangeSize
        }.coerceAtLeast(1)

        val range = effectiveRange
        val start = (center - range).coerceAtLeast(0)
        val end = (center + range).coerceAtMost(provider.pageCount - 1)

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
            if (memoryCache.get(idx)?.takeIf { !it.isRecycled } != null) continue

            prefetchSemaphore.withPermit {
                if (!currentCoroutineContext().isActive) return@withPermit
                loadIntoMemory(provider, idx)
            }
        }
    }

    private suspend fun loadIntoMemory(provider: ComicProvider, index: Int) {
        if (!currentCoroutineContext().isActive) return
        if (index !in 0 until provider.pageCount) return

        memoryCache.get(index)?.takeIf { !it.isRecycled }?.let { return }

        val mutex = loadLocks.computeIfAbsent(index) { Mutex() }
        try {
            mutex.withLock {
                if (!currentCoroutineContext().isActive) return@withLock
                memoryCache.get(index)?.takeIf { !it.isRecycled }?.let { return@loadIntoMemory }
                val bmp = getOrDecodePage(provider, index) ?: return@loadIntoMemory
                if (bmp.isRecycled) return@loadIntoMemory
                memoryCache.get(index)?.takeIf { !it.isRecycled }?.let {
                    if (!bmp.isRecycled && bmp !== it) runCatching { bmp.recycle() }
                    return@loadIntoMemory
                }
                memoryCache.put(index, bmp)
            }
        } finally {
            loadLocks.remove(index)
        }
    }



    private fun clearDiskInternal() {
        pageDiskCache.clear()
        thumbDiskCache.clear()
        synchronized(diskKeyIndex) {
            diskKeyIndex.clear()
        }
    }

    /**
     * 清除指定漫画（cacheId）相关的页面与缩略图磁盘缓存。
     */
    fun clearByCacheId(cacheId: String) {
        val prefix = sha256(cacheId)
        val toRemove = synchronized(diskKeyIndex) {
            diskKeyIndex.filter { it.startsWith(prefix) }.toList()
        }
        for (key in toRemove) {
            pageDiskCache.remove(key)
            thumbDiskCache.remove(key)
            synchronized(diskKeyIndex) { diskKeyIndex.remove(key) }
        }
        // 同时清理缩略图内存缓存中该漫画的条目。
        for (tKey in thumbMemoryCache.snapshot().keys.filter { it.cacheId == cacheId }) {
            thumbMemoryCache.remove(tKey)
        }
    }

    /**
     * 估算指定漫画（cacheId）在磁盘缓存中占用的字节数。
     */
    fun getCacheSizeFor(cacheId: String): Long {
        val prefix = sha256(cacheId)
        val keys = synchronized(diskKeyIndex) {
            diskKeyIndex.filter { it.startsWith(prefix) }
        }
        var size = 0L
        for (key in keys) {
            pageDiskCache.get(key)?.length()?.let { size += it }
            thumbDiskCache.get(key)?.length()?.let { size += it }
        }
        return size
    }

    private fun isKeyInDiskCache(key: String): Boolean {
        synchronized(diskKeyIndex) {
            return diskKeyIndex.contains(key)
        }
    }

    private fun addToDiskKeyIndex(key: String) {
        synchronized(diskKeyIndex) {
            diskKeyIndex.add(key)
        }
    }

    private suspend fun readPageBytes(provider: ComicProvider, index: Int): ByteArray? {
        return try {
            provider.getPageStream(index)?.use { stream -> stream.readBytes() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "读取页面字节流失败: index=$index", e)
            null
        }
    }

    private suspend fun decodeBytes(bytes: ByteArray, reqWidth: Int? = null, reqHeight: Int? = null): Bitmap? {
        if (bytes.isEmpty()) return null
        return decodeSemaphore.withPermit {
            var reusableBitmap: Bitmap? = null
            try {
                val targetW = reqWidth ?: (screenSize.width * 1.5f).toInt().coerceAtLeast(1)
                val targetH = reqHeight ?: (screenSize.height * 1.5f).toInt().coerceAtLeast(1)

                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
                if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return@withPermit null

                if (boundsOptions.outWidth > MAX_IMAGE_DIMENSION || boundsOptions.outHeight > MAX_IMAGE_DIMENSION) {
                    android.util.Log.w(TAG, "Image too large: ${boundsOptions.outWidth}x${boundsOptions.outHeight}, using region decoder")
                    return@withPermit decodeWithRegionDecoder(bytes, targetW, targetH)
                }

                val sample = calculateInSampleSize(
                    boundsOptions.outWidth, boundsOptions.outHeight, targetW, targetH
                )

                val hasAlpha = boundsOptions.outMimeType?.equals("image/png", ignoreCase = true) == true
                        || boundsOptions.outMimeType?.equals("image/webp", ignoreCase = true) == true
                val config = if (hasAlpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = config
                    // 尝试从复用池获取 inBitmap，减少 GC 与内存抖动
                    val expectedW = boundsOptions.outWidth / sample
                    val expectedH = boundsOptions.outHeight / sample
                    reusableBitmap = BitmapPool.get(expectedW, expectedH, config)
                    reusableBitmap?.let { inBitmap = it }
                }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                // 若 decode 未使用 inBitmap（如平台因尺寸不匹配拒绝复用），归还复用池避免泄漏。
                val pooled = reusableBitmap
                if (decoded !== decodeOptions.inBitmap && pooled != null && !pooled.isRecycled) {
                    BitmapPool.put(pooled)
                }
                decoded
            } catch (e: CancellationException) {
                reusableBitmap?.takeIf { !it.isRecycled }?.let { BitmapPool.put(it) }
                throw e
            } catch (e: Throwable) {
                // 捕获 OOM 等 Error，避免单张大图导致崩溃；同时归还 inBitmap 避免池泄漏。
                reusableBitmap?.takeIf { !it.isRecycled }?.let { BitmapPool.put(it) }
                android.util.Log.w(TAG, "decodeBytes failed: ${e.message}")
                null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun decodeWithRegionDecoder(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        var decoder: BitmapRegionDecoder? = null
        var reusableBitmap: Bitmap? = null
        return try {
            decoder = BitmapRegionDecoder.newInstance(ByteArrayInputStream(bytes), false)
                ?: return null
            val width = decoder.width
            val height = decoder.height
            val scale = (reqWidth.toFloat() / width).coerceAtLeast(reqHeight.toFloat() / height).coerceAtMost(1f)
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)

            val sample = calculateInSampleSize(width, height, targetWidth, targetHeight)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
                val expectedW = width / sample
                val expectedH = height / sample
                reusableBitmap = BitmapPool.get(expectedW, expectedH, Bitmap.Config.RGB_565)
                reusableBitmap?.let { inBitmap = it }
            }

            val bitmap = decoder.decodeRegion(android.graphics.Rect(0, 0, width, height), options)
            val pooled = reusableBitmap
            if (bitmap !== options.inBitmap && pooled != null && !pooled.isRecycled) {
                BitmapPool.put(pooled)
            }
            decoder.recycle()
            decoder = null
            bitmap
        } catch (e: CancellationException) {
            reusableBitmap?.takeIf { !it.isRecycled }?.let { BitmapPool.put(it) }
            throw e
        } catch (e: Throwable) {
            reusableBitmap?.takeIf { !it.isRecycled }?.let { BitmapPool.put(it) }
            android.util.Log.w(TAG, "Region decode failed: ${e.message}")
            null
        } finally {
            decoder?.recycle()
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
        if (!isKeyInDiskCache(key)) return null
        return try {
            pageDiskCache.getStream(key)?.use { it.readBytes() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "从磁盘加载页面失败: index=$index", e)
            null
        }
    }

    private fun saveBytesToDisk(provider: ComicProvider, index: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val key = pageKey(provider.cacheId, index)
        try {
            pageDiskCache.put(key) { file ->
                FileOutputStream(file).use { out ->
                    out.write(bytes)
                    out.flush()
                }
            }
            addToDiskKeyIndex(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "保存页面到磁盘失败: index=$index", e)
        }
    }

    private fun saveThumbToDisk(key: String, bmp: Bitmap) {
        try {
            thumbDiskCache.put(key) { file ->
                FileOutputStream(file).use { out ->
                    val fmt = if (bmp.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    bmp.compress(fmt, 85, out)
                    out.flush()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "保存缩略图到磁盘失败: key=$key", e)
        }
    }

    private fun saveBitmapToDisk(provider: ComicProvider, index: Int, bmp: Bitmap) {
        val key = pageKey(provider.cacheId, index)
        // 不再使用 diskKeyIndex 做前置去重：多线程下该检查存在竞态，且 DiskLruCache.put
        // 本身会以写锁覆盖同名文件，直接把索引维护放在 put 成功后即可。
        try {
            val saved = pageDiskCache.put(key) { file ->
                FileOutputStream(file).use { out ->
                    val fmt = if (bmp.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    bmp.compress(fmt, 90, out)
                    out.flush()
                }
            }
            if (saved != null) {
                addToDiskKeyIndex(key)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "保存位图到磁盘失败: index=$index", e)
        }
    }

    private fun pageKey(id: String, index: Int) = "${sha256(id)}#p#$index"
    private fun thumbKey(id: String, index: Int, dim: Int) = "${sha256(id)}#t#$index#$dim"

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun scaleBitmapForThumb(src: Bitmap, maxDim: Int): Bitmap? {
        if (src.isRecycled) return null
        val scale = (maxDim.toFloat() / maxOf(src.width, src.height)).coerceAtMost(1f)
        if (scale >= 1f) return createBitmapCopy(src)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(src, w, h, true)
        } catch (e: OutOfMemoryError) {
            // OOM 时不应返回原始大图，否则缩略图会占用大量内存；降级返回 null 让上层处理。
            null
        }
    }

    /**
     * 创建位图安全副本。上层 UI 会在组件销毁时回收缩略图，
     * 若直接返回内存缓存中的位图会导致缓存被污染。
     */
    private fun createBitmapCopy(src: Bitmap): Bitmap? {
        if (src.isRecycled) return null
        return try {
            Bitmap.createBitmap(src)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private class ScrollSpeedTracker {
        private var lastPage = -1
        private var lastTime = 0L
        private var pagesPerSecond = 0f

        fun onPageChanged(page: Int) {
            val now = System.currentTimeMillis()
            if (lastPage >= 0 && lastTime > 0) {
                val elapsed = (now - lastTime).coerceAtLeast(1L)
                val speed = 1000f / elapsed
                pagesPerSecond = pagesPerSecond * 0.7f + speed * 0.3f
            }
            lastPage = page
            lastTime = now
        }

        fun getCurrentSpeed(): Float = pagesPerSecond
    }
}
