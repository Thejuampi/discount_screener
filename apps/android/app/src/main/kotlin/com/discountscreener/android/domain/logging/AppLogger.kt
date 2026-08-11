package com.discountscreener.android.domain.logging

import android.util.Log

interface AppLogger {
    fun error(tag: String, message: String, throwable: Throwable? = null)

    /**
     * For work that went as intended and still has to be visible — a retention sweep saying what
     * it deleted, for instance. Reporting that as an error would train the reader to ignore errors.
     */
    fun info(tag: String, message: String)
}

object NoOpAppLogger : AppLogger {
    override fun error(tag: String, message: String, throwable: Throwable?) = Unit

    override fun info(tag: String, message: String) = Unit
}

class AndroidAppLogger : AppLogger {
    override fun error(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) {
            Log.e(tag, message)
        } else {
            Log.e(tag, message, throwable)
        }
    }

    override fun info(tag: String, message: String) {
        Log.i(tag, message)
    }
}
