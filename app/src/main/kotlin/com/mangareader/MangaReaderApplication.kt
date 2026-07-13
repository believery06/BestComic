package com.mangareader

import android.app.Application
import com.mangareader.utils.CrashHandler

class MangaReaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
    }
}
