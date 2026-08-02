package com.mangareader.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.mangareader.R

/**
 * 本地通知工具，用于阅读目标达成等场景。
 */
object NotificationUtils {

    private const val CHANNEL_ID_GOALS = "reading_goals"
    private const val CHANNEL_ID_SYNC = "sync_status"
    private const val NOTIFICATION_ID_GOAL = 1001
    private const val NOTIFICATION_ID_SYNC_ERROR = 1002

    fun createChannels(context: Context) {
        // minSdk 为 26，NotificationChannel 始终可用。
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val goalChannel = NotificationChannel(
            CHANNEL_ID_GOALS,
            context.getString(R.string.notification_channel_reading_goals),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_reading_goals_desc)
            setShowBadge(true)
        }
        val syncChannel = NotificationChannel(
            CHANNEL_ID_SYNC,
            context.getString(R.string.notification_channel_sync),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_sync_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(goalChannel, syncChannel))
    }

    /**
     * 发送阅读目标达成通知。
     *
     * @param pagesText 页数成就文本，为空则不显示
     * @param minutesText 时长成就文本，为空则不显示
     */
    fun showGoalCompletedNotification(
        context: Context,
        pagesText: String?,
        minutesText: String?
    ) {
        val parts = listOfNotNull(pagesText, minutesText)
        if (parts.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val content = context.getString(
            R.string.notification_goal_content,
            parts.joinToString(context.getString(R.string.list_separator))
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_GOALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(createLargeIcon(context))
            .setContentTitle(context.getString(R.string.notification_goal_title))
            .setContentText(content)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(NOTIFICATION_ID_GOAL, notification)
    }

    /**
     * 发送 WebDAV 同步失败通知（仅在拥有通知权限时）。
     */
    fun showSyncErrorNotification(context: Context, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_sync_error_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        manager.notify(NOTIFICATION_ID_SYNC_ERROR, notification)
    }

    /**
     * 创建通知大图标。优先使用应用启动图标，回退到前景 drawable。
     */
    private fun createLargeIcon(context: Context): Bitmap? {
        return runCatching {
            val drawable = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground) ?: return null
            drawable.toBitmap(width = 128, height = 128, config = Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
}
