package com.synclisten.app.util

import android.util.Log

object AppLogger {
    fun debug(tag: String, message: String) {
        Log.d("SyncListen/$tag", message)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        Log.e("SyncListen/$tag", message, throwable)
    }
}

