package com.synclisten.cloud

import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

object CloudCover {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    fun fetch(value: String): ByteArray? {
        val url = value.toHttpUrlOrNull() ?: return null
        val host = url.host
        if (url.username.isNotEmpty() || url.password.isNotEmpty() ||
            !(host == "music.126.net" || host.endsWith(".music.126.net") || host == "kwcdn.kuwo.cn" || host.endsWith(".kwcdn.kuwo.cn"))) return null
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful || !response.header("Content-Type").orEmpty().startsWith("image/", ignoreCase = true)) return null
                val body = response.body ?: return null
                if (body.contentLength() > 3 * 1024 * 1024) return null
                body.byteStream().use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 3 * 1024 * 1024) return null
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
        } catch (_: Exception) { null }
    }
}
