package com.mangareader.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.util.Size
import com.mangareader.provider.ComicProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.util.Collections

class PageCacheManager(context: Context) {

    enum class PrefetchDirection { BOTH, FORWARD, BACKWARD }

    enum class Aggressiveness { LOW, BALANCED, HIGH }

    companion object {
        private const val TAG = "PageCacheManager"
        private const val PREFETCH_CONCURRENCY = 3
        private const val MEMORY_LIMIT_BYTES = 120L * 1024 * 1024
        private const val MAX_IMAGE_DIMENSION = 8000
    }

    private val memoryCache = Collections.synchronizedMap(LinkedHashMap<Int, Bitmap>(16, 0.75f, true))
    private val memoryUsed = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var currentCacheId: String? = null

    @Volatile
    private var currentIndexValue: Int = -1

    private var prefetchJob: Job? = null
    private val prefetchSemaphore = Semaphore(PREFETCH_CONCURRENCY)

    private val pageDiskCache = DiskLruCache(context.cacheDir, "page_cache", 30L * 1024 * 1024)
    private val thumbDiskCache = DiskLruCache(context.cacheDir, "thumb_cache", 10L * 1024 * 1024)

    private val diskKeyIndex = Collections.synchronizedSet(java.util.HashSet<String>())

    private var screenSize: Size = Size(1080, 1920)

    private var prefetchRangeSize = 15
    private var scrollSpeedTracker = ScrollSpeedTracker()

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
        if (index !in 0 until provider.pageCount) return

        withContext(Dispatchers.IO) {
            if (currentCacheId != provider.cacheId) {
                clearMemoryInternal()
                clearDiskInternal()
                currentCacheId = provider.cacheId
            }

            currentIndexValue = index
            updateScrollSpeed(index)

            loadIntoMemory(provider, index)

            prefetchJob?.cancelAndJoin()

            prefetchJob = launch(SupervisorJob()) {
                try {
                    prefetchRange(provider, index, direction)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Prefetch failed", e)
                }
            }
        }
    }

    suspend fun getPage(provider: ComicProvider, index: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (index !in 0 until provider.pageCount) return@withContext null

        synchronized(memoryCache) {
            memoryCache[index]?.takeIf { !it.isRecycled }
        }?.let { return@withContext it }

        val bytesFromDisk = loadBytesFromDisk(provider, index)
        if (bytesFromDisk != null) {
            val decoded = decodeBytes(bytesFromDisk)
            if (decoded != null) return@withContext decoded
        }

        val bytes = readPageBytes(provider, index)
        if (bytes != null) {
            saveBytesToDisk(provider, index, bytes)
            val decoded = decodeBytes(bytes)
            if (decoded != null) return@withContext decoded
        }

        runCatching { provider.getPage(index) }.getOrNull()?.takeIf { !it.isRecycled }
    }

    suspend fun getThumbnail(provider: ComicProvider, index: Int, maxDimension: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            if (index !in 0 until provider.pageCount) return@withContext null
            val dim = maxDimension.coerceIn(64, 360)

            val cachedBmp = synchronized(memoryCache) {
                memoryCache[index]?.takeIf { !it.isRecycled }
            }
            if (cachedBmp != null) {
                return@withContext scaleBitmapForThumb(cachedBmp, dim)
            }

            val key = thumbKey(provider.cacheId, index, dim)
            thumbDiskCache.getStream(key)?.use { stream ->
                BitmapFactory.decodeStream(stream)?.takeIf { !it.isRecycled }
            }?.let { return@withContext it }

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

            val fullBmp = runCatching { provider.getPage(index) }.getOrNull()
                ?.takeIf { !it.isRecycled } ?: return@withContext null
            val thumb = scaleBitmapForThumb(fullBmp, dim)
            if (thumb !== fullBmp && !fullBmp.isRecycled) runCatching { fullBmp.recycle() }
            thumb
        }

    suspend fun preload(provider: ComicProvider, index: Int) {
        if (index !in 0 until provider.pageCount) return
        withContext(Dispatchers.IO) {
            loadIntoMemory(provider, index)
        }
    }

    fun clearMemory() {
        clearMemoryInternal()
    }

    fun clear() {
        clearMemoryInternal()
        clearDiskInternal()
    }

    fun clearDisk() {
        clearDiskInternal()
    }

    fun onLowMemory() {
        val keep = currentIndexValue
        synchronized(memoryCache) {
            val iterator = memoryCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key == keep) continue
                iterator.remove()
            }
        }
        memoryUsed.set(0L)
    }

    private suspend fun prefetchRange(
        provider: ComicProvider,
        center: Int,
        direction: PrefetchDirection
    ) {
        val range = prefetchRangeSize
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
            val cached = synchronized(memoryCache) {
                memoryCache[idx]?.takeIf { !it.isRecycled }
            }
            if (cached != null) continue

            prefetchSemaphore.withPermit {
                if (!currentCoroutineContext().isActive) return@withPermit
                loadIntoMemory(provider, idx)
            }
        }
    }

    private suspend fun loadIntoMemory(provider: ComicProvider, index: Int) {
        if (!currentCoroutineContext().isActive) return
        if (index !in 0 until provider.pageCount) return

        synchronized(memoryCache) {
            memoryCache[index]?.takeIf { !it.isRecycled }
        }?.let { return }

        val bmp = getPage(provider, index) ?: return
        if (bmp.isRecycled) return

        val bytes = bmp.allocationByteCount.toLong()
        val old = synchronized(memoryCache) {
            val previous = memoryCache.put(index, bmp)
            if (previous == null) memoryUsed.addAndGet(bytes) else memoryUsed.addAndGet(bytes - previous.allocationByteCount.toLong())
            previous
        }
        if (old != null && old !== bmp && !old.isRecycled) {
            runCatching { old.recycle() }
        }
        evictOldestIfNeeded()
    }

    private fun evictOldestIfNeeded() {
        while (memoryUsed.get() > MEMORY_LIMIT_BYTES) {
            val removed = synchronized(memoryCache) {
                val it = memoryCache.entries.iterator()
                while (it.hasNext()) {
                    val entry = it.next()
                    if (entry.key == currentIndexValue) continue
                    it.remove()
                    return@synchronized entry.value
                }
                return@synchronized null
            } ?: break
            memoryUsed.addAndGet(-removed.allocationByteCount.toLong())
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
        memoryUsed.set(0L)
        currentIndexValue = -1
    }

    private fun clearDiskInternal() {
        pageDiskCache.clear()
        thumbDiskCache.clear()
        synchronized(diskKeyIndex) {
            diskKeyIndex.clear()
        }
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

            if (boundsOptions.outWidth > MAX_IMAGE_DIMENSION || boundsOptions.outHeight > MAX_IMAGE_DIMENSION) {
                android.util.Log.w(TAG, "Image too large: ${boundsOptions.outWidth}x${boundsOptions.outHeight}, using region decoder")
                return decodeWithRegionDecoder(bytes, targetW, targetH)
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
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "decodeBytes failed: ${e.message}")
            null
        }
    }

    private fun decodeWithRegionDecoder(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: return null
            val width = decoder.width
            val height = decoder.height
            val scale = (reqWidth.toFloat() / width).coerceAtLeast(reqHeight.toFloat() / height).coerceAtMost(1f)
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(width, height, targetWidth, targetHeight)
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val bitmap = decoder.decodeRegion(android.graphics.Rect(0, 0, width, height), options)
            decoder.recycle()
            bitmap
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Region decode failed: ${e.message}")
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
        if (!isKeyInDiskCache(key)) return null
        return try {
            pageDiskCache.getStream(key)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveBytesToDisk(provider: ComicProvider, index: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val key = pageKey(provider.cacheId, index)
        addToDiskKeyIndex(key)
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
