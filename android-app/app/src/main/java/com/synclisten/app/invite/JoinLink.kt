package com.synclisten.app.invite

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class JoinLink(
    val roomId: String,
    val token: String,
    val serverUrl: String,
)

object JoinLinkCodec {
    fun encode(link: JoinLink): String =
        "synclisten://join?roomId=${encodeValue(link.roomId)}&token=${encodeValue(link.token)}&server=${encodeValue(link.serverUrl)}"

    fun parse(value: String?): JoinLink? = runCatching {
        val uri = URI(value ?: return null)
        if (uri.scheme != "synclisten" || uri.host != "join" || uri.path !in listOf("", null)) return null
        val pairs = uri.rawQuery?.split("&")?.map {
            val parts = it.split("=", limit = 2)
            if (parts.size != 2) return null
            decodeValue(parts[0]) to decodeValue(parts[1])
        } ?: return null
        if (pairs.map { it.first }.toSet() != REQUIRED_FIELDS || pairs.size != REQUIRED_FIELDS.size) return null
        val parameters = pairs.toMap()
        val roomId = parameters["roomId"]?.takeIf(String::isNotBlank) ?: return null
        val token = parameters["token"]?.takeIf(String::isNotBlank) ?: return null
        val server = parameters["server"]?.takeIf(String::isNotBlank) ?: return null
        if (!isValidServer(server)) return null
        JoinLink(roomId, token, server.trimEnd('/'))
    }.getOrNull()

    private fun isValidServer(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.query == null &&
            uri.fragment == null
    }.getOrDefault(false)

    private fun encodeValue(value: String) =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun decodeValue(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private val REQUIRED_FIELDS = setOf("roomId", "token", "server")
}

@Singleton
class JoinLinkInbox @Inject constructor() {
    private val mutablePending = MutableStateFlow<JoinLink?>(null)
    val pending: StateFlow<JoinLink?> = mutablePending

    fun accept(rawLink: String?) {
        mutablePending.value = JoinLinkCodec.parse(rawLink)
    }

    fun clear() {
        mutablePending.value = null
    }
}
