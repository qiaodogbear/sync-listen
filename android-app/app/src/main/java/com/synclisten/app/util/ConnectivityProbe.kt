package com.synclisten.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

sealed interface ProbeResult {
    data object Reachable : ProbeResult
    data class Unreachable(val reason: UnreachableReason, val detail: String) : ProbeResult
}

enum class UnreachableReason {
    NETWORK_UNREACHABLE,
    CONNECTION_REFUSED,
    TIMEOUT,
    INVALID_ADDRESS,
    SERVER_ERROR,
}

object ConnectivityProbe {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        val normalized = url.trimEnd('/')
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            return@withContext ProbeResult.Unreachable(
                UnreachableReason.INVALID_ADDRESS, "地址格式无效，需要以 http:// 或 https:// 开头"
            )
        }
        try {
            withTimeout(8_000) {
                val request = Request.Builder().url("$normalized/health").build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    ProbeResult.Reachable
                } else {
                    ProbeResult.Unreachable(
                        UnreachableReason.SERVER_ERROR, "服务器返回错误 ${response.code}"
                    )
                }
            }
        } catch (e: UnknownHostException) {
            ProbeResult.Unreachable(UnreachableReason.NETWORK_UNREACHABLE, "设备可能不在同一网络")
        } catch (e: ConnectException) {
            ProbeResult.Unreachable(UnreachableReason.CONNECTION_REFUSED, "Host 已停止或端口被阻止")
        } catch (e: SocketTimeoutException) {
            ProbeResult.Unreachable(UnreachableReason.TIMEOUT, "连接超时，地址不可达")
        } catch (e: IOException) {
            ProbeResult.Unreachable(UnreachableReason.NETWORK_UNREACHABLE, e.message ?: "网络不可达")
        }
    }
}
