package com.discountscreener.android.domain.logging

import android.util.Log

interface AppLogger {
    fun error(tag: String, message: String, throwable: Throwable? = null)
}

object NoOpAppLogger : AppLogger {
    override fun error(tag: String, message: String, throwable: Throwable?) = Unit
}

class AndroidAppLogger : AppLogger {
    override fun error(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) {
            Log.e(tag, message)
        } else {
            Log.e(tag, message, throwable)
        }
    }
}
