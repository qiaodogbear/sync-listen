package com.synclisten.app.host.server

import com.synclisten.app.data.CreateRoomRequest
import com.synclisten.app.data.JoinRoomRequest
import com.synclisten.app.data.NextPlaybackCommand
import com.synclisten.app.data.TrackPlaybackCommand
import com.synclisten.app.domain.model.MemberRole
import com.synclisten.app.domain.model.RoomStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRoomStoreTest {
    @Test
    fun createsJoinsSnapshotsAndClosesSingleRoom() = runBlocking {
        val store = store()
        val created = store.createRoom(CreateRoomRequest("Friday", "host", "Alice"))

        assertEquals(MemberRole.HOST, created.member.role)
        val joined = store.joinByCode(JoinRoomRequest("member", "Bob", roomCode = created.room.roomCode))
        assertEquals(MemberRole.MEMBER, joined.member.role)
        assertEquals(2, store.snapshot(created.room.roomId).members.size)

        store.leave(created.room.roomId, "member")
        assertEquals(1, store.snapshot(created.room.roomId).members.size)
        store.leave(created.room.roomId, "host")

        assertError("ROOM_CLOSED") { store.snapshot(created.room.roomId) }
    }

    @Test
    fun rejectsWrongJoinTokenAndSecondActiveRoom() = runBlocking {
        val store = store()
        val created = store.createRoom(CreateRoomRequest("Friday", "host", "Alice"))

        assertError("INVALID_JOIN_TOKEN") {
            store.joinById(created.room.roomId, JoinRoomRequest("member", "Bob", joinToken = "wrong"))
        }
        assertError("ACTIVE_ROOM_EXISTS") {
            store.createRoom(CreateRoomRequest("Other", "other-host", "Eve"))
        }
    }

    @Test
    fun allocatesUniqueContinuousTrackOrderConcurrently() = runBlocking {
        val store = store()
        val room = store.createRoom(CreateRoomRequest("Friday", "host", "Alice")).room

        val tracks = (0 until 8).map { index ->
            async {
                store.addReadyTrack(
                    AddHostTrack(
                        roomId = room.roomId,
                        title = "Track $index",
                        artist = null,
                        durationMs = 1000,
                        fileName = "$index.mp3",
                        fileSize = 10,
                        fileHash = index.toString().padStart(64, '0'),
                        storagePath = "/tmp/$index",
                        uploaderId = "host",
                        uploaderName = "Alice",
                    ),
                )
            }
        }.awaitAll()

        assertEquals((0 until 8).toList(), tracks.map { it.orderIndex }.sorted())
    }

    @Test
    fun enforcesHostPlaybackAndSchedulesCommands() = runBlocking {
        val store = store()
        val created = store.createRoom(CreateRoomRequest("Friday", "host", "Alice"))
        store.joinByCode(JoinRoomRequest("member", "Bob", roomCode = created.room.roomCode))
        val first = store.addReadyTrack(track(created.room.roomId, "first"))
        val second = store.addReadyTrack(track(created.room.roomId, "second"))

        assertError("HOST_REQUIRED") {
            store.play(created.room.roomId, TrackPlaybackCommand("member", first.trackId, 0))
        }
        val playing = store.play(created.room.roomId, TrackPlaybackCommand("host", first.trackId, 100))
        assertTrue(playing.isPlaying)
        assertEquals(2_500L, playing.executeAtServerTimeMs)

        val paused = store.pause(created.room.roomId, TrackPlaybackCommand("host", first.trackId, 350))
        assertFalse(paused.isPlaying)
        val next = store.next(created.room.roomId, NextPlaybackCommand("host"))
        assertEquals(second.trackId, next.trackId)
    }

    @Test
    fun exposesAdvancingSyncOnlyWhilePlaying() = runBlocking {
        var now = 1_000L
        val ids = AtomicInteger()
        val store = HostRoomStore(
            clock = { now },
            idFactory = { "id-${ids.incrementAndGet()}" },
            roomCodeFactory = { "ABC123" },
            leadTimeMs = 0,
        )
        val room = store.createRoom(CreateRoomRequest("Friday", "host", "Alice")).room
        val track = store.addReadyTrack(track(room.roomId, "first"))
        store.play(room.roomId, TrackPlaybackCommand("host", track.trackId, 100))
        now = 1_350

        val sync = store.syncState()

        assertEquals(room.roomId, sync?.first)
        assertEquals(450L, sync?.second?.positionMs)
        store.pause(room.roomId, TrackPlaybackCommand("host", track.trackId, 450))
        assertEquals(null, store.syncState())
    }

    private fun store(): HostRoomStore {
        val id = AtomicInteger()
        return HostRoomStore(
            clock = { 1_000L },
            idFactory = { "id-${id.incrementAndGet()}" },
            roomCodeFactory = { "ABC123" },
            leadTimeMs = 1_500,
        )
    }

    private fun track(roomId: String, name: String) = AddHostTrack(
        roomId = roomId,
        title = name,
        artist = null,
        durationMs = 1000,
        fileName = "$name.mp3",
        fileSize = 10,
        fileHash = name.padEnd(64, '0').take(64),
        storagePath = "/tmp/$name",
        uploaderId = "host",
        uploaderName = "Alice",
    )

    private suspend fun assertError(code: String, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull() as HostServerError
        assertEquals(code, error.code)
    }
}
