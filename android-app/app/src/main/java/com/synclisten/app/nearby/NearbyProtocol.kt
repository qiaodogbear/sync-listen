package com.synclisten.app.nearby

import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class NearbyRoom(val code: String, val serverUrl: String, val name: String)
@Serializable
data class NearbyProfile(val id: String, val name: String, val room: NearbyRoom? = null, val version: Int = 1)
@Serializable
data class NearbyInvitation(val id: String, val sender: String, val room: NearbyRoom)
data class PendingNearbyInvitation(val invitation: NearbyInvitation, val expiresAtMs: Long)
data class NearbyPeer(val serviceName: String, val endpoint: String, val nonce: String, val profile: NearbyProfile, val lastSeenAtMs: Long = 0)

object NearbyValidation {
    fun isLanIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } }) return false
        val b = parts.map { it.toIntOrNull() ?: return false }
        if (b.any { it !in 0..255 }) return false
        return b[0] == 10 || (b[0] == 172 && b[1] in 16..31) ||
            (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254)
    }
    fun server(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "http" && isLanIpv4(uri.host.orEmpty()) &&
            uri.port in 1..65535 && uri.userInfo == null && uri.query == null &&
            uri.fragment == null && uri.path in listOf("", "/")
    }.getOrDefault(false)
    fun name(value: String): Boolean = value.isNotBlank() && value.length <= 40 && value.none(Char::isISOControl)
    fun id(value: String): Boolean = value.matches(Regex("[a-f0-9-]{36}"))
    fun room(value: NearbyRoom): Boolean = value.code.matches(Regex("[A-Z0-9]{6}")) && server(value.serverUrl) && name(value.name)
    fun profile(value: NearbyProfile): Boolean = value.version == 1 && id(value.id) && name(value.name) &&
        (value.room == null || room(value.room))
}

/** One pending dialog, bounded replay history and a local monotonic expiry. No auto-join. */
class NearbyInvitationGate(private val nowMs: () -> Long, private val busy: () -> Boolean) {
    private val mutablePending = MutableStateFlow<PendingNearbyInvitation?>(null)
    val pending: StateFlow<PendingNearbyInvitation?> = mutablePending
    private val received = LinkedHashMap<String, Long>()
    private val attempts = ArrayDeque<Long>()

    @Synchronized fun receive(invitation: NearbyInvitation): Int {
        expire()
        val now = nowMs()
        while (attempts.isNotEmpty() && now - attempts.first() >= 60_000) attempts.removeFirst()
        if (attempts.size >= 5) return 429
        attempts.addLast(now)
        if (!NearbyValidation.id(invitation.id) || !NearbyValidation.name(invitation.sender) ||
            !NearbyValidation.room(invitation.room)) return 400
        if (received.containsKey(invitation.id)) return 409
        if (busy() || pending.value != null) return 409
        received[invitation.id] = now
        mutablePending.value = PendingNearbyInvitation(invitation, now + 60_000)
        return 202
    }

    @Synchronized fun expire() {
        val now = nowMs()
        received.entries.removeAll { now - it.value >= 120_000 }
        if (pending.value?.expiresAtMs?.let { it <= now } == true) mutablePending.value = null
    }

    @Synchronized fun take(): NearbyInvitation? {
        expire()
        val value = pending.value?.invitation
        mutablePending.value = null
        return value
    }
    @Synchronized fun clear() { mutablePending.value = null }
}
