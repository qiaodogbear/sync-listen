package com.synclisten.app.data

import com.synclisten.app.domain.model.MemberRole
import com.synclisten.app.domain.model.Track
import com.synclisten.app.domain.model.TrackStatus
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

    @Test
    fun syncMessageAllowsMissingScheduledExecutionTime() {
        val event = RoomEventParser(json).parse(
            """
            {"type":"SYNC","payload":{
              "trackId":"track","positionMs":500,"isPlaying":true,"serverTimeMs":1000
            },"serverTimeMs":1000}
            """.trimIndent(),
        )

        assertEquals(500, (event as RoomEvent.Playback).value.positionMs)
    }

    @Test
    fun reconnectGateAllowsOnlyOnePendingReconnect() {
        val gate = ReconnectGate()

        assertEquals(1, gate.trySchedule())
        assertEquals(null, gate.trySchedule())
        gate.complete()
        assertEquals(2, gate.trySchedule())
    }

    @Test
    fun playlistReducerIgnoresStaleEventsAndNormalizesServerOrder() {
        val reducer = RoomEventReducer()
        val initial = RoomEventParser(json).parse(
            """
            {"type":"ROOM_JOINED","payload":{
              "room":{"roomId":"room-1","roomCode":"ABC123","name":"Room","hostUserId":"host","status":"ACTIVE","createdAt":1},
              "members":[],
              "playlist":[],
              "playbackState":{"trackId":null,"positionMs":0,"isPlaying":false,"serverTimeMs":1,"executeAtServerTimeMs":null}
            },"serverTimeMs":100}
            """.trimIndent(),
        )
        var snapshot = reducer.apply(null, initial)
        val newest = RoomEvent.Playlist(
            PlaylistResponse(listOf(track("b", 1), track("a", 0), track("a", 0))),
            serverTimeMs = 300,
        )
        val stale = RoomEvent.Playlist(PlaylistResponse(listOf(track("old", 0))), serverTimeMs = 200)

        snapshot = reducer.apply(snapshot, newest)
        snapshot = reducer.apply(snapshot, stale)

        assertEquals(listOf("a", "b"), snapshot?.playlist?.map { it.trackId })
    }

    private fun track(id: String, orderIndex: Int) = Track(
        trackId = id,
        roomId = "room-1",
        title = id,
        artist = null,
        durationMs = 1_000,
        fileName = "$id.mp3",
        fileSize = 1,
        fileHash = id.padEnd(64, '0'),
        uploaderId = "host",
        uploaderName = "Alice",
        orderIndex = orderIndex,
        status = TrackStatus.READY,
        createdAt = 1,
    )
}
