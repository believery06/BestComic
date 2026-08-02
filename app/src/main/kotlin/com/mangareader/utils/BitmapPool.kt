package com.mangareader.utils

import android.graphics.Bitmap

/**
 * 全应用共享的 Bitmap 复用池，用于解析器、缓存管理器、缩略图提取等解码场景。
 *
 * 通过 [BitmapFactory.Options.inBitmap] 复用已分配内存，可显著减少 GC 与内存抖动。
 * 该对象线程安全，可在任意线程调用。
 *
 * 重要约定：从池中取出的 Bitmap 必须仅用于解码（inBitmap），或在使用完毕后立即归还/回收；
 * 严禁把池中 Bitmap 直接交给 UI 绘制。所有 UI 层应使用独立副本，否则池回收后可能导致
 * "Cannot draw recycled bitmap" 异常。
 */
object BitmapPool {

    private const val MAX_POOL_SIZE = 6
    private val pool = java.util.ArrayDeque<Bitmap>(MAX_POOL_SIZE)
    private val lock = Any()

    /**
     * 尝试获取一块可复用的 Bitmap。
     *
     * @param width 目标宽度（采样后）
     * @param height 目标高度（采样后）
     * @param config 目标像素格式
     * @return 符合条件的可变 Bitmap，若无则返回 null
     */
    fun get(width: Int, height: Int, config: Bitmap.Config): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val needed = width * height * bytesPerPixel(config)
        synchronized(lock) {
            val iterator = pool.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.isRecycled || !candidate.isMutable) {
                    iterator.remove()
                    continue
                }
                // Android 4.4+ 允许 inBitmap 字节数 >= 需求且 config 相同
                if (candidate.config == config && candidate.allocationByteCount >= needed) {
                    iterator.remove()
                    return candidate
                }
            }
        }
        return null
    }

    /**
     * 将未使用的可变 Bitmap 归还池中。
     */
    fun put(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled || !bitmap.isMutable) return
        synchronized(lock) {
            // 避免同一个 Bitmap 实例被重复放入池中
            if (pool.any { it === bitmap }) return
            if (pool.size >= MAX_POOL_SIZE) {
                pool.pollFirst()?.takeIf { !it.isRecycled }?.recycle()
            }
            pool.addLast(bitmap)
        }
    }

    /**
     * 清空池中所有 Bitmap 并释放内存。
     */
    fun clear() {
        synchronized(lock) {
            pool.forEach { if (!it.isRecycled) it.recycle() }
            pool.clear()
        }
    }

    private fun bytesPerPixel(config: Bitmap.Config): Int = when (config) {
        Bitmap.Config.ARGB_8888 -> 4
        Bitmap.Config.RGB_565 -> 2
        Bitmap.Config.ALPHA_8 -> 1
        // HARDWARE 位图不可变且不能作为 inBitmap，返回极大值避免被错误匹配。
        Bitmap.Config.HARDWARE -> Int.MAX_VALUE
        else -> 4
    }
}
