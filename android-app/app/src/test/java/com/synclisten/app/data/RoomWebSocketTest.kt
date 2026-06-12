package com.synclisten.app.data

import com.synclisten.app.domain.model.MemberRole
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomWebSocketTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun roomJoinedRestoresAuthoritativeSnapshot() {
        val event = RoomEventParser(json).parse(
            """
            {"type":"ROOM_JOINED","payload":{
              "room":{"roomId":"room-1","roomCode":"ABC123","name":"Room","hostUserId":"host","status":"ACTIVE","createdAt":1},
              "members":[{"userId":"host","displayName":"Alice","role":"HOST","connected":true,"joinedAt":1}],
              "playlist":[],
              "playbackState":{"trackId":null,"positionMs":0,"isPlaying":false,"serverTimeMs":1,"executeAtServerTimeMs":null}
            },"serverTimeMs":2}
            """.trimIndent(),
        )

        val snapshot = RoomEventReducer().apply(null, event)

        assertEquals("room-1", snapshot?.room?.roomId)
        assertEquals(MemberRole.HOST, snapshot?.members?.single()?.role)
    }

    @Test
    fun reconnectDelayUsesCappedExponentialBackoff() {
        assertEquals(1_000, reconnectDelayMs(0))
        assertEquals(8_000, reconnectDelayMs(3))
        assertEquals(30_000, reconnectDelayMs(10))
    }

    @Test
    fun websocketHandshakeUrlKeepsHttpSchemeForOkHttp() {
        val url = buildWebSocketUrl("http://10.0.2.2:3000", "room-1", "user-1", "token")

        assertEquals("http", url.scheme)
        assertEquals("/ws/rooms/room-1", url.encodedPath)
        assertEquals("token", url.queryParameter("token"))
    }
}
