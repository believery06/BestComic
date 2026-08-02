package com.mangareader.utils

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionUtils {

    /**
     * 判断当前是否具备存储访问能力。
     * Android 11+ 使用 Storage Access Framework (SAF)，不再依赖
     * MANAGE_EXTERNAL_STORAGE / 「管理所有文件」权限，因此直接返回 true。
     * 低版本仍检查 READ_EXTERNAL_STORAGE 运行时权限。
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 请求存储权限。
     * Android 11+ 不再跳转「所有文件访问权限」设置页（Manifest 未声明该权限，
     * 跳转后用户也无法授予），调用方应自行使用 OpenDocumentTree 等 SAF 方式。
     */
    fun requestStoragePermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 由调用方通过 SAF 选择目录，此处不再执行任何操作。
        } else {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_CODE_STORAGE
            )
        }
    }

    const val REQUEST_CODE_STORAGE = 1001
}
