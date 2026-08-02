package com.mangareader.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object OnboardingUtils {

    /**
     * Android 11+ 使用 Storage Access Framework，不再请求 MANAGE_EXTERNAL_STORAGE。
     * 低版本返回 READ_EXTERNAL_STORAGE 是否已授予。
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 低版本跳转到应用详情页，用户可手动授予存储权限。
     * Android 11+ 不再使用此 Intent，应改用 SAF 选择目录。
     */
    fun buildAppDetailsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }
}
