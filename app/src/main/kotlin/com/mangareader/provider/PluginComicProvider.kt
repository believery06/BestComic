package com.mangareader.provider

import android.graphics.Bitmap

/**
 * 插件化 [ComicProvider] 接口预留。
 *
 * 核心包只定义此接口，PDF/网络协议/云盘/特殊格式等后续可作为插件实现，
 * 通过 [PluginRegistry] 动态注册到 [ComicProviderFactory]。
 */
interface PluginComicProvider : ComicProvider {
    /** 插件唯一标识 */
    val pluginId: String

    /** 插件支持的协议或扩展名，例如 "pdf", "cbz", "smb" */
    val supportedSchemes: List<String>

    /** 插件初始化入口 */
    fun initialize(params: Map<String, String>): Boolean
}

/**
 * 插件注册表。未来可通过 ServiceLoader 或反射加载插件实现。
 */
object PluginRegistry {
    private val plugins = mutableMapOf<String, PluginComicProvider>()

    fun register(provider: PluginComicProvider) {
        plugins[provider.pluginId] = provider
    }

    fun unregister(pluginId: String) {
        plugins.remove(pluginId)
    }

    fun findByScheme(scheme: String): PluginComicProvider? {
        return plugins.values.firstOrNull { scheme in it.supportedSchemes }
    }
}

/**
 * 占位插件实现，防止接口空悬。
 */
class StubPluginProvider(
    override val pluginId: String = "stub",
    override val supportedSchemes: List<String> = emptyList()
) : PluginComicProvider {
    override val title: String = "Stub"
    override val pageCount: Int = 0
    override suspend fun getPage(index: Int): Bitmap? = null
    override suspend fun getThumbnail(index: Int, maxDimension: Int): Bitmap? = null
    override fun initialize(params: Map<String, String>): Boolean = false
}
