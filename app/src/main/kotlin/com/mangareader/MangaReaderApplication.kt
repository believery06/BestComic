package com.mangareader

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.mangareader.cache.CacheCleaner
import com.mangareader.cache.PageCacheManager
import com.mangareader.data.SettingsRepository
import com.mangareader.utils.BitmapPool
import com.mangareader.utils.CrashHandler
import com.mangareader.utils.NotificationUtils
import com.mangareader.utils.WebDavSyncScheduler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MangaReaderApplication : Application() {

    companion object {
        lateinit var applicationScope: CoroutineScope
            private set
    }

    override fun onCreate() {
        super.onCreate()
        // 应用级后台作用域默认在 IO 调度器上运行，避免未指定 dispatcher 的任务阻塞主线程。
        // 加上 CoroutineExceptionHandler，确保后台初始化异常不会导致应用崩溃。
        applicationScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
                android.util.Log.e("MangaReaderApp", "应用级协程异常", t)
            }
        )
        CrashHandler.init(this)
        NotificationUtils.createChannels(this)
        WebDavSyncScheduler.schedule(this)
        // 启动缓存清理在 IO 线程执行，避免大缓存目录删除阻塞主线程。
        applicationScope.launch {
            runCatching { CacheCleaner.cleanOnStartup(this@MangaReaderApplication) }
                .onFailure { android.util.Log.e("MangaReaderApp", "启动缓存清理失败", it) }
        }
        // 恢复上次未正常结束的阅读会话时长，避免系统杀进程后统计丢失。
        applicationScope.launch {
            runCatching {
                SettingsRepository.getInstance(this@MangaReaderApplication).recoverReadingSession()
            }.onFailure { android.util.Log.e("MangaReaderApp", "恢复阅读会话失败", it) }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        // 应用终止时释放缓存后台协程与 trim 线程，避免线程泄漏（主要在模拟器生效，
        // 真实设备进程被系统回收时也会随进程结束，但保留兜底逻辑更健壮）。
        runCatching { PageCacheManager.getInstance(this).shutdown() }
            .onFailure { Log.e("MangaReaderApp", "缓存管理器关闭失败", it) }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // 系统整体内存不足时，立即清空 Bitmap 复用池与内存缓存。
        BitmapPool.clear()
        runCatching { PageCacheManager.getInstance(this).onLowMemory() }
            .onFailure { Log.e("MangaReaderApp", "低内存处理失败", it) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            // 应用退到后台（界面不再可见，等同用户离开应用）：清空内存缓存并清理
            // 阅读页面/缩略图磁盘缓存，避免退出应用后缓存仍长期占用存储。
            // 磁盘删除为 IO 操作，放到应用级作用域执行，避免阻塞主线程。
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                BitmapPool.clear()
                runCatching { PageCacheManager.getInstance(this).clearMemoryCache() }
                    .onFailure { Log.e("MangaReaderApp", "清空内存缓存失败", it) }
                applicationScope.launch {
                    runCatching { PageCacheManager.getInstance(this@MangaReaderApplication).clearDisk() }
                        .onFailure { Log.e("MangaReaderApp", "清理阅读磁盘缓存失败", it) }
                }
            }
            // 应用正在前台但系统内存紧张，清空缓存池与内存缓存。
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                BitmapPool.clear()
                runCatching { PageCacheManager.getInstance(this).trimMemory(level) }
                    .onFailure { Log.e("MangaReaderApp", "内存整理失败", it) }
            }
            // 系统即将回收后台应用，做最激进清理。
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                BitmapPool.clear()
                runCatching { PageCacheManager.getInstance(this).clearMemoryCache() }
                    .onFailure { Log.e("MangaReaderApp", "清空内存缓存失败", it) }
            }
        }
    }
}
