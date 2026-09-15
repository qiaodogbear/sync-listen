package com.synclisten.shared.data

import com.synclisten.shared.domain.model.Member
import com.synclisten.shared.domain.model.MemberRole
import com.synclisten.shared.domain.model.PlaybackState
import com.synclisten.shared.domain.model.WebSocketEnvelope
import com.synclisten.shared.domain.model.WebSocketEventType
import com.synclisten.shared.util.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface RoomEvent {
    data class Snapshot(val value: RoomSnapshot, val serverTimeMs: Long) : RoomEvent
    data class MemberJoined(val member: Member) : RoomEvent
    data class MemberLeft(val userId: String) : RoomEvent
    data class RoleChanged(val userId: String, val newRole: MemberRole) : RoomEvent
    data class TrackRemoved(val trackId: String) : RoomEvent
    data class Playlist(val value: PlaylistResponse, val serverTimeMs: Long) : RoomEvent
    data class Playback(val value: PlaybackState) : RoomEvent
    data class Other(val envelope: WebSocketEnvelope) : RoomEvent
}

class RoomEventParser(private val json: Json) {
    fun parse(text: String): RoomEvent {
        val envelope = json.decodeFromString<WebSocketEnvelope>(text)
        return when (envelope.type) {
            WebSocketEventType.ROOM_JOINED ->
                RoomEvent.Snapshot(json.decodeFromJsonElement(envelope.payload), envelope.serverTimeMs)
            WebSocketEventType.MEMBER_JOINED ->
                RoomEvent.MemberJoined(json.decodeFromJsonElement<MemberPayload>(envelope.payload).member)
            WebSocketEventType.MEMBER_LEFT ->
                RoomEvent.MemberLeft(json.decodeFromJsonElement<MemberLeftPayload>(envelope.payload).userId)
            WebSocketEventType.ROLE_CHANGED -> {
                val p = json.decodeFromJsonElement<RoleChangedPayload>(envelope.payload)
                RoomEvent.RoleChanged(p.userId, p.role)
            }
            WebSocketEventType.TRACK_REMOVED ->
                RoomEvent.TrackRemoved(json.decodeFromJsonElement<TrackRemovedPayload>(envelope.payload).trackId)
            WebSocketEventType.PLAYLIST_UPDATED ->
                RoomEvent.Playlist(json.decodeFromJsonElement(envelope.payload), envelope.serverTimeMs)
            WebSocketEventType.PLAY,
            WebSocketEventType.PAUSE,
            WebSocketEventType.SEEK,
            WebSocketEventType.NEXT,
            WebSocketEventType.SYNC,
            -> RoomEvent.Playback(json.decodeFromJsonElement(envelope.payload))
            else -> RoomEvent.Other(envelope)
        }
    }
}

@kotlinx.serialization.Serializable
private data class MemberPayload(val member: Member)

@kotlinx.serialization.Serializable
private data class MemberLeftPayload(val userId: String)

@kotlinx.serialization.Serializable
private data class RoleChangedPayload(val userId: String, val role: MemberRole)

@kotlinx.serialization.Serializable
private data class TrackRemovedPayload(val trackId: String)

class RoomEventReducer {
    private var latestPlaylistServerTimeMs = Long.MIN_VALUE

    fun apply(current: RoomSnapshot?, event: RoomEvent): RoomSnapshot? = when (event) {
        is RoomEvent.Snapshot -> {
            latestPlaylistServerTimeMs = event.serverTimeMs
            event.value.copy(playlist = normalizePlaylist(event.value.playlist))
        }
        is RoomEvent.MemberJoined -> current?.copy(
            members = current.members.filterNot { it.userId == event.member.userId } + event.member,
        )
        is RoomEvent.MemberLeft -> current?.copy(
            members = current.members.map {
                if (it.userId == event.userId) it.copy(connected = false) else it
            },
        )
        is RoomEvent.RoleChanged -> current?.copy(
            members = current.members.map {
                if (it.userId == event.userId) it.copy(role = event.newRole) else it
            },
        )
        is RoomEvent.TrackRemoved -> current?.copy(
            playlist = current.playlist.filter { it.trackId != event.trackId },
        )
        is RoomEvent.Playlist -> if (event.serverTimeMs < latestPlaylistServerTimeMs) {
            current
        } else {
            latestPlaylistServerTimeMs = event.serverTimeMs
            current?.copy(playlist = normalizePlaylist(event.value.playlist))
        }
        is RoomEvent.Playback -> current?.copy(playbackState = event.value)
        is RoomEvent.Other -> current
    }

    private fun normalizePlaylist(playlist: List<com.synclisten.shared.domain.model.Track>) =
        playlist.distinctBy { it.trackId }.sortedBy { it.orderIndex }
}

sealed interface RoomConnectionState {
    data object Disconnected : RoomConnectionState
    data object Connecting : RoomConnectionState
    data object Connected : RoomConnectionState
    data class Reconnecting(val attempt: Int) : RoomConnectionState
    data class Failed(val message: String) : RoomConnectionState
}

fun reconnectDelayMs(attempt: Int): Long = min(30_000L, 1_000L shl attempt.coerceAtMost(5))

class ReconnectGate {
    private var pending = false
    private var attempt = 0

    @Synchronized
    fun trySchedule(): Int? {
        if (pending) return null
        pending = true
        attempt = (attempt + 1).coerceAtMost(MAX_ATTEMPT)
        return attempt
    }

    @Synchronized
    fun complete() {
        pending = false
    }

    @Synchronized
    fun reset() {
        pending = false
        attempt = 0
    }

    @Synchronized
    fun isAtLimit(): Boolean = attempt >= MAX_ATTEMPT

    private companion object {
        const val MAX_ATTEMPT = 1_000
    }
}

fun buildWebSocketUrl(serverUrl: String, roomId: String, userId: String, token: String) =
    "${normalizeServerUrl(serverUrl)}/ws/rooms/$roomId"
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("token", token)
        .addQueryParameter("userId", userId)
        .build()

class RoomWebSocketClient(
    private val client: OkHttpClient,
    private val networkAvailable: kotlinx.coroutines.flow.StateFlow<Boolean>,
    json: Json,
) {
    private data class Target(val serverUrl: String, val roomId: String, val userId: String, val token: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val parser = RoomEventParser(json)
    private val reducer = RoomEventReducer()
    private val shouldReconnect = AtomicBoolean(false)
    private var target: Target? = null
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private val reconnectGate = ReconnectGate()

    private val mutableConnection = MutableStateFlow<RoomConnectionState>(RoomConnectionState.Disconnected)
    val connection: StateFlow<RoomConnectionState> = mutableConnection
    private val mutableSnapshot = MutableStateFlow<RoomSnapshot?>(null)
    val snapshot: StateFlow<RoomSnapshot?> = mutableSnapshot
    private val mutableLastEvent = MutableStateFlow<RoomEvent?>(null)
    val lastEvent: StateFlow<RoomEvent?> = mutableLastEvent

    fun connect(serverUrl: String, roomId: String, userId: String, token: String) {
        disconnect()
        target = Target(serverUrl, roomId, userId, token)
        shouldReconnect.set(true)
        reconnectGate.reset()
        open()
    }

    fun disconnect() {
        shouldReconnect.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectGate.reset()
        target = null
        mutableSnapshot.value = null
        mutableLastEvent.value = null
        socket?.close(1000, "Client disconnect")
        socket = null
        mutableConnection.value = RoomConnectionState.Disconnected
    }

    private fun open() {
        val current = target ?: return
        mutableConnection.value = if (mutableConnection.value == RoomConnectionState.Disconnected) {
            RoomConnectionState.Connecting
        } else {
            mutableConnection.value
        }
        val httpUrl = buildWebSocketUrl(current.serverUrl, current.roomId, current.userId, current.token)
        socket = client.newWebSocket(Request.Builder().url(httpUrl).build(), listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            reconnectGate.reset()
            mutableConnection.value = RoomConnectionState.Connected
            AppLogger.debug("WebSocket", "Connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            runCatching { parser.parse(text) }
                .onSuccess {
                    mutableLastEvent.value = it
                    mutableSnapshot.value = reducer.apply(mutableSnapshot.value, it)
                }
                .onFailure { AppLogger.error("WebSocket", "Message parse failed", it) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            scheduleReconnect("Closed: $code $reason")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            webSocket.close(code, reason)
            scheduleReconnect("Closing: $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            if (webSocket !== socket) return
            AppLogger.error("WebSocket", "Connection failed", error)
            if (response?.code in listOf(401, 403, 404, 410)) {
                shouldReconnect.set(false)
                mutableConnection.value = RoomConnectionState.Failed("房间已关闭或身份失效，请返回首页重新加入")
                return
            }
            scheduleReconnect(error.message ?: "WebSocket connection failed")
        }
    }

    private fun scheduleReconnect(message: String) {
        if (!shouldReconnect.get()) return
        if (reconnectGate.isAtLimit()) {
            mutableConnection.value = RoomConnectionState.Failed("重连次数已达上限，请检查网络后重新加入房间")
            return
        }
        val attempt = reconnectGate.trySchedule() ?: return
        mutableConnection.value = RoomConnectionState.Reconnecting(attempt)
        reconnectJob = scope.launch {
            // 如果网络不可用，等待恢复
            delay(reconnectDelayMs(attempt - 1))
            reconnectGate.complete()
            if (shouldReconnect.get()) open() else mutableConnection.value = RoomConnectionState.Failed(message)
        }
    }
}
