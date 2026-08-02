package com.mangareader.data

import android.graphics.Bitmap
import java.io.InputStream

/**
 * 统一漫画数据源抽象。
 *
 * 本地文件、压缩包、PDF、EPUB、网络文件（SMB/FTP/云盘）以及未来插件都通过此接口
 * 向阅读器提供页面。上层阅读、渲染、缓存逻辑不关心数据实际来源。
 *
 * 注：该接口原位于 `provider` 包，现上提到 `data` 包以打破 `data ↔ provider` 循环依赖。
 * 具体实现仍保留在 `provider` 包中。
 */
interface ComicProvider : AutoCloseable {

    /** 漫画标题/文件名 */
    val title: String

    /** 总页数 */
    val pageCount: Int

    /** Stable identity used for disk cache namespacing. */
    val cacheId: String get() = title

    /** 获取指定页完整位图（用于阅读显示） */
    suspend fun getPage(index: Int): Bitmap?

    /**
     * 获取指定页原始输入流。
     * 本地/压缩包实现可直接提供；网络/插件实现可返回 null 并依赖 [getPage]。
     */
    suspend fun getPageStream(index: Int): InputStream? = null

    /** 获取指定页缩略图，[maxDimension] 为最大边像素 */
    suspend fun getThumbnail(index: Int, maxDimension: Int): Bitmap? = null

    /** 获取页面在压缩包/文件中的原始名称，用于调试或显示 */
    fun getPageName(index: Int): String = ""

    /** 获取页面所属章节标题，无章节时返回空字符串 */
    fun getChapterTitle(index: Int): String = ""

    /** 获取页面关联的文本内容，用于 TTS 朗读；不支持时返回 null。 */
    suspend fun getPageText(index: Int): String? = null

    /** 释放底层资源（压缩包句柄、PDF 渲染器、网络连接等） */
    override fun close() {}
}
