package com.synclisten.app.host.server

import com.synclisten.app.domain.model.WebSocketEnvelope
import com.synclisten.app.domain.model.WebSocketEventType
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

    private val identities = ConcurrentHashMap<DefaultWebSocketServerSession, String>()

    fun add(roomId: String, session: DefaultWebSocketServerSession, userId: String = "") {
        identities[session] = userId
        sessions.computeIfAbsent(roomId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun remove(roomId: String, session: DefaultWebSocketServerSession) {
        sessions[roomId]?.remove(session)
        identities.remove(session)
    }

    fun isConnected(roomId: String, userId: String): Boolean = sessions[roomId].orEmpty().any { identities[it] == userId }

    suspend fun closeMember(roomId: String, userId: String) {
        sessions[roomId].orEmpty().filter { identities[it] == userId }.forEach { it.close(CloseReason(CloseReason.Codes.NORMAL, "Member left")) }
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
            remove(roomId, session)
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
        identities.clear()
    }
}
