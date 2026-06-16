package com.synclisten.shared.host.server

import com.synclisten.shared.domain.model.WebSocketEnvelope
import com.synclisten.shared.domain.model.WebSocketEventType
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class HostRoomHub(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sessions = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()

    fun add(roomId: String, session: DefaultWebSocketServerSession) {
        sessions.computeIfAbsent(roomId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun remove(roomId: String, session: DefaultWebSocketServerSession) {
        sessions[roomId]?.remove(session)
    }

    suspend fun send(
        session: DefaultWebSocketServerSession,
        type: WebSocketEventType,
        payload: JsonObject,
    ) {
        session.send(Frame.Text(json.encodeToString(WebSocketEnvelope(type, payload, clock()))))
    }

    suspend fun broadcast(roomId: String, type: WebSocketEventType, payload: JsonObject) {
        val dead = mutableListOf<DefaultWebSocketServerSession>()
        sessions[roomId].orEmpty().toList().forEach { session ->
            runCatching { send(session, type, payload) }
                .onFailure { dead.add(session) }
        }
        dead.forEach { session ->
            runCatching { session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Send failed")) }
            sessions[roomId]?.remove(session)
        }
    }

    suspend fun closeAll() {
        sessions.values.forEach { roomSessions ->
            roomSessions.forEach { session ->
                runCatching {
                    session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Host stopped"))
                }
            }
        }
        sessions.clear()
    }
}
