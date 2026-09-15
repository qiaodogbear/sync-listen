package com.synclisten.shared.data

import com.synclisten.protocol.DeviceCredential
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

class DeviceAuthInterceptor(private val settings: SettingsStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val identity = runBlocking { IdentityManager(settings).ensureIdentity() }
        val url = request.url
        val origin = "${url.scheme}://${url.host}:${url.port}"
        val credential = DeviceCredential.forServer(identity.deviceSecret, origin, identity.userId)
        return chain.proceed(request.newBuilder()
            .header("X-User-Id", identity.userId)
            .header("Authorization", "Bearer $credential")
            .build())
    }
}
