package com.synclisten.shared.util

object AppLogger {
    fun debug(tag: String, message: String) {
        println("[DEBUG] $tag: $message")
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        System.err.println("[ERROR] $tag: $message")
        throwable?.printStackTrace(System.err)
    }
}
