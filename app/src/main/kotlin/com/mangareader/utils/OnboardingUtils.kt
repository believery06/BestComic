package com.mangareader.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

object OnboardingUtils {

    /**
     * Returns true if the user has granted MANAGE_EXTERNAL_STORAGE on Android 11+.
     * On lower API levels the app always has storage access through legacy paths.
     */
    @Suppress("UNUSED_PARAMETER")
    fun hasManageAllFilesPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * Builds the intent that opens the system screen where the user can
     * grant "All files access" to the app. We do NOT use ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
     * because that only works on Android 11+; on Android 13+ Google also
     * accepts ACTION_APPLICATION_DETAILS_SETTINGS as a fallback.
     */
    fun buildManageAllFilesIntent(context: Context): Intent {
        val packageName = context.packageName
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        } else {
            // Fallback for older devices: open app details so the user can grant
            // the legacy WRITE_EXTERNAL_STORAGE permission.
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        }
    }
}
