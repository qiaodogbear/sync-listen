package com.synclisten.app.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolModelsTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun decodesTrackUsingServerFieldNames() {
        val track = json.decodeFromString<Track>(
            """
            {
              "trackId": "track-1",
              "roomId": "room-1",
              "title": "Test Song",
              "artist": null,
              "durationMs": 123000,
              "fileName": "test.flac",
              "fileSize": 456,
              "fileHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "uploaderId": "user-1",
              "uploaderName": "Alice",
              "orderIndex": 0,
              "status": "READY",
              "createdAt": 1710000000000
            }
            """.trimIndent(),
        )

        assertEquals(TrackStatus.READY, track.status)
        assertEquals("track-1", track.trackId)
    }

    @Test
    fun decodesCommonWebSocketEnvelope() {
        val event = json.decodeFromString<WebSocketEnvelope>(
            """
            {
              "type": "TRACK_READY",
              "payload": {"trackId": "track-1"},
              "serverTimeMs": 1710000000000
            }
            """.trimIndent(),
        )

        assertEquals(WebSocketEventType.TRACK_READY, event.type)
        assertEquals(1_710_000_000_000, event.serverTimeMs)
    }
}

