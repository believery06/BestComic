package com.mangareader.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mangareader.R
import com.mangareader.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * WebDAV 后台同步 Worker。
 * 根据用户配置的同步间隔定期执行双向同步，失败时按指数退避重试。
 */
class WebDavSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "WebDavSyncWorker"
        const val WORK_NAME = "webdav_auto_sync"
        private const val SYNC_TIMEOUT_MS = 5 * 60 * 1000L
    }

    override suspend fun doWork(): Result {
        // 整个同步流程设置 5 分钟超时，避免网络挂起或 DataStore 阻塞导致 Worker 长期占用。
        return try {
            withTimeout(SYNC_TIMEOUT_MS) { doWorkInternal() }
        } catch (e: CancellationException) {
            // CoroutineWorker 被取消时应抛出 CancellationException，让 WorkManager 正确标记为取消，
            // 而不是返回 failure（否则会被视为失败并可能触发不必要重试）。
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "自动同步异常", e)
            Result.retry()
        }
    }

    private suspend fun doWorkInternal(): Result {
        val context = applicationContext
        val manager = WebDavConfigManager(context)
        val config = manager.getConfig()

        if (config.serverUrl.isBlank() || config.syncInterval <= 0) {
            Log.d(TAG, "自动同步未启用或配置为空，跳过")
            return Result.success()
        }

        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "网络不可用，推迟同步")
            return Result.retry()
        }

        val repo = SettingsRepository.getInstance(context)
        val progressMap = try {
            repo.getAllProgress().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "读取本地进度失败", e)
            return Result.retry()
        }
        val bookmarks = try {
            repo.getBookmarks().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "读取本地书签失败", e)
            return Result.retry()
        }
        val favorites = try {
            repo.getFavorites().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "读取本地收藏失败", e)
            return Result.retry()
        }

        val progressTimes = try {
            repo.getRecentReads().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "读取本地进度时间失败", e)
            emptyMap<String, Long>()
        }

        val local = SyncData(
            progress = progressMap.mapValues { it.value.first },
            totalPages = progressMap.mapValues { it.value.second },
            bookmarks = bookmarks,
            favorites = favorites,
            progressTimes = progressTimes
        )

        val remoteResult = WebDavSync.downloadSyncData(config)
        if (remoteResult.isFailure) {
            val error = remoteResult.exceptionOrNull()
            Log.e(TAG, "下载远程数据失败", error)
            if (isAuthFailure(error)) {
                NotificationUtils.showSyncErrorNotification(
                    context,
                    error?.message ?: context.getString(R.string.webdav_sync_auth_error)
                )
                return Result.failure()
            }
            return Result.retry()
        }

        val remote = remoteResult.getOrThrow()
        val merged = WebDavSync.mergeSyncData(local, remote)

        applyMergedData(context, merged)

        val uploadResult = WebDavSync.uploadSyncData(
            config,
            merged.progress,
            merged.totalPages,
            merged.bookmarks,
            merged.favorites,
            merged.progressTimes
        )

        return if (uploadResult.isSuccess) {
            Log.d(TAG, "自动同步成功")
            Result.success()
        } else {
            val error = uploadResult.exceptionOrNull()
            Log.e(TAG, "上传合并数据失败", error)
            if (isAuthFailure(error)) {
                NotificationUtils.showSyncErrorNotification(
                    context,
                    error?.message ?: context.getString(R.string.webdav_sync_auth_error)
                )
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    /**
     * 判断是否为认证失败。401/403 属于配置错误，不应无限重试。
     * 同时兼容中文前缀与 HTTP 状态码文本，提升对多语言服务器错误的容错性。
     */
    private fun isAuthFailure(error: Throwable?): Boolean {
        val msg = error?.message ?: return false
        return msg.startsWith("认证失败") || msg.contains("401") || msg.contains("403")
    }

    private suspend fun applyMergedData(context: Context, merged: SyncData) {
        val repo = SettingsRepository.getInstance(context)

        // 1. 批量写入进度与总页数，避免每个 URI 触发一次 DataStore 事务。
        try {
            repo.saveProgressBatch(merged.progress, merged.totalPages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "批量写入同步进度失败", e)
            // 批量失败时降级为单条写入，尽量保留可写入的数据
            for ((uri, page) in merged.progress) {
                try {
                    val u = android.net.Uri.parse(uri)
                    repo.saveProgress(u, page)
                    merged.totalPages[uri]?.let { repo.saveTotalPages(u, it) }
                } catch (_: Exception) {
                    // 单条进度写入失败不应中断同步流程
                }
            }
        }

        // 2. 批量合并书签，避免单条 addBookmark 多次序列化大 Map。
        try {
            repo.mergeBookmarks(merged.bookmarks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "批量合并同步书签失败", e)
        }

        // 3. 批量添加收藏，避免单条 toggleFavorite 多次触发 DataStore 事务。
        val currentFavorites = try {
            repo.getFavorites().first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        }
        val toAdd = merged.favorites subtract currentFavorites
        try {
            repo.addFavorites(toAdd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "批量添加同步收藏失败", e)
            // 批量失败时降级为单条写入
            for (uri in toAdd) {
                try {
                    repo.toggleFavorite(android.net.Uri.parse(uri))
                } catch (_: Exception) {
                    // 单条收藏写入失败不应中断同步流程
                }
            }
        }

        // 4. 批量写入合并后的进度时间戳，保证后续冲突解决依据一致。
        try {
            repo.saveProgressTimesBatch(merged.progressTimes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "批量写入同步进度时间失败", e)
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/**
 * WebDAV 自动同步调度器。
 * 根据 [WebDavConfig.syncInterval] 更新或取消周期性同步任务。
 */
object WebDavSyncScheduler {

    private const val TAG = "WebDavSyncScheduler"
    private const val WORK_NAME = WebDavSyncWorker.WORK_NAME

    fun schedule(context: Context) {
        try {
            val config = WebDavConfigManager(context).getConfig()
            val intervalMinutes = config.syncInterval.coerceAtLeast(15)

            if (config.serverUrl.isBlank() || config.syncInterval <= 0) {
                cancel(context)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<WebDavSyncWorker>(
                intervalMinutes.toLong(),
                TimeUnit.MINUTES
            )
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        } catch (e: Exception) {
            Log.e(TAG, "调度 WebDAV 同步任务失败", e)
        }
    }

    fun cancel(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        } catch (e: Exception) {
            Log.e(TAG, "取消 WebDAV 同步任务失败", e)
        }
    }

    fun observeWorkInfo(context: Context) =
        try {
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(WORK_NAME)
        } catch (e: Exception) {
            Log.e(TAG, "获取 WebDAV 同步任务状态失败", e)
            null
        }
}
