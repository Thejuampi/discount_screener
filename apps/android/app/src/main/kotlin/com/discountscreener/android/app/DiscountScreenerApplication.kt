package com.discountscreener.android.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Debug
import java.io.File

class DiscountScreenerApplication : Application() {
    private lateinit var crashDump: CrashDump

    override fun onCreate() {
        super.onCreate()
        crashDump = CrashDump(File(filesDir, DUMP_DIR)) { file ->
            Debug.dumpHprofData(file.absolutePath)
        }
        var previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { crashDump.capture(reason = "uncaught-${thread.name}", error = error) }
            previous?.uncaughtException(thread, error)
        }
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            // The low-memory killer sends SIGKILL next. Heap dump here often does not finish.
            runCatching {
                crashDump.capture(reason = "trim-$level", error = null, writeHeap = false)
            }
        }
        super.onTrimMemory(level)
    }

    private companion object {
        private const val DUMP_DIR = "crash-dumps"
    }
}
