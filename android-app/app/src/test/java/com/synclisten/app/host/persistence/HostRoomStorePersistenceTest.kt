package com.synclisten.app.host.persistence

import com.synclisten.app.data.CreateRoomRequest
import com.synclisten.app.data.JoinRoomRequest
import com.synclisten.app.data.NextPlaybackCommand
import com.synclisten.app.data.TrackPlaybackCommand
import com.synclisten.app.domain.model.MemberRole
import com.synclisten.app.host.server.AddHostTrack
import com.synclisten.app.host.server.HostRecoverySnapshot
import com.synclisten.app.host.server.HostRoomStore
import com.synclisten.app.host.server.HostServerError
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRoomStorePersistenceTest {

    @Test
    fun `create room persists and is recoverable after emergency shutdown`() = runBlocking {
        val dao = FakeHostDao()
        val s = store(dao)
        val created = s.createRoom(CreateRoomRequest("Test Room", "host", "Alice"))
        s.emergencyShutdown()

        val newStore = store(dao)
        val recovery = newStore.loadRecoverableRoom()

        assertNotNull(recovery)
        assertEquals("Test Room", recovery!!.roomName)
        assertEquals(created.room.roomCode, recovery.roomCode)
        assertEquals(1, recovery.memberCount)
        assertEquals(0, recovery.trackCount)
    }

    @Test
    fun `recover room from persistence restores all data`() = runBlocking {
        val dao = FakeHostDao()
        val original = store(dao)
        val created = original.createRoom(CreateRoomRequest("Party", "host", "Alice"))
        original.joinByCode(JoinRoomRequest("member", "Bob", roomCode = created.room.roomCode))
        val song1 = original.addReadyTrack(track(created.room.roomId, "Song1"))
        original.addReadyTrack(track(created.room.roomId, "Song2"))
        original.play(created.room.roomId, TrackPlaybackCommand("host", song1.trackId, 100))
        original.pause(created.room.roomId, TrackPlaybackCommand("host", song1.trackId, 350))

        // 模拟异常终止
        original.emergencyShutdown()

        // 模拟 App 重启，新 store 恢复
        val newStore = store(dao)
        val recovery = newStore.loadRecoverableRoom()

        assertEquals("Party", recovery!!.roomName)
        assertEquals(2, recovery.memberCount)
        assertEquals(2, recovery.trackCount)

        val snapshot = newStore.recoverRoom()
        assertEquals(2, snapshot.members.size)
        assertEquals(2, snapshot.playlist.size)
        assertFalse(snapshot.playbackState.isPlaying)
        assertEquals(song1.trackId, snapshot.playbackState.trackId)
        assertEquals(350L, snapshot.playbackState.positionMs)

        // 恢复后成员应全部离线
        assertTrue(snapshot.members.all { !it.connected })
    }

    @Test
    fun `explicit close removes recovery marker`() = runBlocking {
        val dao = FakeHostDao()
        val s = store(dao)
        s.createRoom(CreateRoomRequest("Party", "host", "Alice"))

        s.closeAndCleanup()

        val recovery = store(dao).loadRecoverableRoom()
        assertNull(recovery)
    }

    @Test
    fun `emergency shutdown preserves recovery marker`() = runBlocking {
        val dao = FakeHostDao()
        val s = store(dao)
        s.createRoom(CreateRoomRequest("Party", "host", "Alice"))

        s.emergencyShutdown()

        val recovery = store(dao).loadRecoverableRoom()
        assertNotNull(recovery)
    }

    @Test
    fun `dismiss recovery clears all persistent data`() = runBlocking {
        val dao = FakeHostDao()
        val s = store(dao)
        s.createRoom(CreateRoomRequest("Party", "host", "Alice"))

        s.emergencyShutdown()
        val newStore = store(dao)
        newStore.dismissRecovery()

        val recovery = store(dao).loadRecoverableRoom()
        assertNull(recovery)
        assertNull(dao.getRoom())
        assertTrue(dao.getMembers("party-room").isEmpty())
    }

    @Test
    fun `no recovery when store has no dao`() = runBlocking {
        val s = HostRoomStore(
            clock = { 1_000L },
            idFactory = { "id-1" },
            roomCodeFactory = { "ABC123" },
        )
        s.createRoom(CreateRoomRequest("Party", "host", "Alice"))
        s.emergencyShutdown()

        // 无 DAO 的 store 无法加载恢复数据
        val recovery = HostRoomStore(
            clock = { 1_000L },
            idFactory = { "id-2" },
            roomCodeFactory = { "ABC123" },
        ).loadRecoverableRoom()
        assertNull(recovery)
    }

    @Test
    fun `recover room with host permission works`() = runBlocking {
        val dao = FakeHostDao()
        val s = store(dao)
        val created = s.createRoom(CreateRoomRequest("Party", "host", "Alice"))
        val song = s.addReadyTrack(track(created.room.roomId, "Song1"))
        s.emergencyShutdown()

        val newStore = store(dao)
        val snapshot = newStore.recoverRoom()

        // Host 应该能控制播放
        val playing = newStore.play(
            snapshot.room.roomId,
            TrackPlaybackCommand("host", song.trackId, 0),
        )
        assertTrue(playing.isPlaying)
    }

    @Test
    fun `recover room on same room code after recovery`() = runBlocking {
        val dao = FakeHostDao()
        val s = store(dao)
        val created = s.createRoom(CreateRoomRequest("Party", "host", "Alice"))
        val originalCode = created.room.roomCode
        s.emergencyShutdown()

        val newStore = store(dao)
        val recovered = newStore.recoverRoom()

        assertEquals(originalCode, recovered.room.roomCode)
    }

    @Test
    fun `cannot recover from explicitly closed room`() = runBlocking {
        val dao = FakeHostDao()
        val s = store(dao)
        s.createRoom(CreateRoomRequest("Party", "host", "Alice"))
        s.closeAndCleanup()

        assertError("ROOM_NOT_FOUND") { store(dao).recoverRoom() }
    }

    @Test
    fun `member join and leave persists correctly`() = runBlocking {
        val dao = FakeHostDao()
        val s = store(dao)
        val created = s.createRoom(CreateRoomRequest("Party", "host", "Alice"))
        s.joinByCode(JoinRoomRequest("member", "Bob", roomCode = created.room.roomCode))

        assertEquals(2, dao.getMembers(created.room.roomId).size)

        s.leave(created.room.roomId, "member")
        assertEquals(1, dao.getMembers(created.room.roomId).size)
    }

    @Test
    fun `host leave removes recovery marker`() = runBlocking {
        val dao = FakeHostDao()
        val s = store(dao)
        val created = s.createRoom(CreateRoomRequest("Party", "host", "Alice"))

        s.leave(created.room.roomId, "host")

        val recovery = store(dao).loadRecoverableRoom()
        assertNull(recovery)
    }

    // ── Helpers ──

    private fun store(dao: HostDao): HostRoomStore {
        val id = AtomicInteger()
        return HostRoomStore(
            clock = { 1_000L },
            idFactory = { "id-${id.incrementAndGet()}" },
            roomCodeFactory = { "ABC123" },
            leadTimeMs = 1_500,
            dao = dao,
        )
    }

    private val trackIdCounter = AtomicInteger()

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

/** 内存 FakeHostDao —— 纯内存实现，不依赖 Room */
private class FakeHostDao : HostDao {
    private var room: HostRoomEntity? = null
    private val members = linkedMapOf<String, HostMemberEntity>()
    private val tracks = linkedMapOf<String, HostTrackEntity>()
    private var playback: HostPlaybackEntity? = null
    private var recoveryMarker: RecoveryMarkerEntity? = null

    override suspend fun upsertRoom(room: HostRoomEntity) { this.room = room }
    override suspend fun getRoom() = room
    override suspend fun deleteRoom() { room = null }

    override suspend fun upsertMember(member: HostMemberEntity) {
        members["${member.roomId}:${member.userId}"] = member
    }
    override suspend fun getMembers(roomId: String) = members.values.filter { it.roomId == roomId }
    override suspend fun deleteMember(roomId: String, userId: String) {
        members.remove("$roomId:$userId")
    }
    override suspend fun updateAllConnected(roomId: String, connected: Boolean) {
        members.replaceAll { _, m -> if (m.roomId == roomId) m.copy(connected = connected) else m }
    }
    override suspend fun deleteAllMembers() { members.clear() }

    override suspend fun upsertTrack(track: HostTrackEntity) { tracks[track.trackId] = track }
    override suspend fun getTracks(roomId: String) = tracks.values.filter { it.roomId == roomId }.sortedBy { it.orderIndex }
    override suspend fun deleteTrack(trackId: String) { tracks.remove(trackId) }
    override suspend fun deleteAllTracks() { tracks.clear() }

    override suspend fun upsertPlayback(playback: HostPlaybackEntity) { this.playback = playback }
    override suspend fun getPlayback(roomId: String) = playback
    override suspend fun deleteAllPlayback() { playback = null }

    override suspend fun upsertRecoveryMarker(marker: RecoveryMarkerEntity) { recoveryMarker = marker }
    override suspend fun getRecoveryMarker() = recoveryMarker
    override suspend fun deleteRecoveryMarker() { recoveryMarker = null }
}
