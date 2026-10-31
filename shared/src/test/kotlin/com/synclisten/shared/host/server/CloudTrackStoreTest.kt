package com.synclisten.shared.host.server

import com.synclisten.cloud.CloudTrack
import com.synclisten.cloud.CloudTrackReference
import com.synclisten.shared.data.CreateRoomRequest
import com.synclisten.shared.data.JoinRoomRequest
import com.synclisten.shared.domain.model.MemberRole
import com.synclisten.shared.domain.model.Track
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTrackStoreTest {
    @Test fun cloudReferenceKeepsStableIdentityAndIsNotAStoredFile() = runBlocking {
        val store = HostRoomStore()
        val room = store.createRoom(CreateRoomRequest("Test", "host", "Alice", protocolVersion = 2)).room
        val reference = CloudTrackReference(CloudTrack("qq", "song", "Song", listOf("Artist"), mediaId = "media"), "mp3", 128)
        val cloud = store.addCloudTrack(room.roomId, "host", reference)
        assertEquals(reference, store.snapshot(room.roomId).playlist.single().cloud)
        assertThrows(HostServerError::class.java) { runBlocking { store.storedTrack(cloud.trackId) } }
        val serialized = Json.encodeToString(cloud)
        assertFalse(serialized.contains("http"))
        assertEquals(reference, Json.decodeFromString<Track>(serialized).cloud)
    }

    @Test fun olderLocalTrackPayloadStillDecodes() {
        val local = """{"trackId":"a","roomId":"r","title":"Song","artist":null,"durationMs":1000,"fileName":"a.mp3","fileSize":10,"fileHash":"hash","uploaderId":"u","uploaderName":"Alice","orderIndex":0,"status":"READY","createdAt":1}"""
        assertNull(Json.decodeFromString<Track>(local).cloud)
    }

    @Test fun incompatibleMembersAreRejectedBeforeCloudTracksAreShared() = runBlocking {
        val store = HostRoomStore(roomCodeFactory = { "ABC123" })
        val room = store.createRoom(CreateRoomRequest("Test", "host", "Alice", protocolVersion = 2)).room
        store.joinByCode(JoinRoomRequest("legacy", "Bob", roomCode = room.roomCode))
        store.setConnected(room.roomId, "legacy", true)
        val reference = CloudTrackReference(CloudTrack("qq", "song", "Song", listOf("Artist")), "mp3", 128)
        val error = assertThrows(HostServerError::class.java) { runBlocking { store.addCloudTrack(room.roomId, "host", reference) } }
        assertEquals("CLIENT_UPGRADE_REQUIRED", error.code)
        store.setConnected(room.roomId, "legacy", false)
        store.addCloudTrack(room.roomId, "host", reference)
        val joinError = assertThrows(HostServerError::class.java) {
            runBlocking { store.joinByCode(JoinRoomRequest("old", "Old", roomCode = room.roomCode)) }
        }
        assertEquals("CLIENT_UPGRADE_REQUIRED", joinError.code)
    }

    @Test fun preparationWaitsForMembersAndOnlyHostCanForce() = runBlocking {
        val store = HostRoomStore(clock = { 10_000 }, roomCodeFactory = { "ABC123" })
        val room = store.createRoom(CreateRoomRequest("Test", "host", "Alice", protocolVersion = 2)).room
        store.joinByCode(JoinRoomRequest("member", "Bob", roomCode = room.roomCode, protocolVersion = 2))
        store.setConnected(room.roomId, "host", true)
        store.setConnected(room.roomId, "member", true)
        val reference = CloudTrackReference(CloudTrack("qq", "song", "Song", listOf("Artist")), "mp3", 128)
        val track = store.addCloudTrack(room.roomId, "host", reference)
        val preparation = store.prepareCloud(room.roomId, "host", track.trackId, 0)
        val generation = preparation.preparation!!.generation
        assertFalse(preparation.isPlaying)
        val partial = store.reportCloudReady(room.roomId, "host", track.trackId, generation, true)
        assertEquals(listOf("member"), partial.preparation!!.waiting)
        store.changeRole(room.roomId, "member", MemberRole.ADMIN, "host")
        val denied = assertThrows(HostServerError::class.java) { runBlocking { store.commitCloud(room.roomId, "member", generation, true) } }
        assertEquals("HOST_REQUIRED", denied.code)
        val committed = store.reportCloudReady(room.roomId, "member", track.trackId, generation, true)
        assertTrue(committed.isPlaying)
        assertEquals(11_500L, committed.executeAtServerTimeMs)
        val stale = assertThrows(HostServerError::class.java) { runBlocking { store.reportCloudReady(room.roomId, "member", track.trackId, generation, true) } }
        assertEquals("PREPARATION_STALE", stale.code)
    }
}
