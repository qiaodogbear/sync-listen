package com.synclisten.shared.host.server

import com.synclisten.shared.data.CreateRoomRequest
import com.synclisten.shared.data.CreateRoomResponse
import com.synclisten.shared.data.JoinRoomRequest
import com.synclisten.shared.data.JoinRoomResponse
import com.synclisten.shared.data.NextPlaybackCommand
import com.synclisten.shared.data.RoomSnapshot
import com.synclisten.shared.data.TrackPlaybackCommand
import com.synclisten.shared.domain.model.Member
import com.synclisten.shared.domain.model.MemberRole
import com.synclisten.shared.domain.model.PlaybackState
import com.synclisten.shared.domain.model.Room
import com.synclisten.shared.domain.model.RoomStatus
import com.synclisten.shared.domain.model.Track
import com.synclisten.shared.domain.model.TrackStatus
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AddHostTrack(
    val roomId: String, val title: String, val artist: String?, val durationMs: Long,
    val fileName: String, val fileSize: Long, val fileHash: String,
    val storagePath: String, val uploaderId: String, val uploaderName: String,
)

data class StoredHostTrack(val track: Track, val storagePath: String)

data class HostRecoverySnapshot(
    val roomId: String, val roomName: String, val roomCode: String,
    val memberCount: Int, val trackCount: Int, val currentTrackTitle: String?,
)

class HostRoomStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val roomCodeFactory: () -> String = {
        buildString { repeat(6) { append("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[Random.nextInt(36)]) } }
    },
    private val leadTimeMs: Long = 1_500,
) {
    private val mutex = Mutex()
    private var room: Room? = null
    private var joinToken: String? = null
    private val members = linkedMapOf<String, Member>()
    private val tracks = mutableListOf<StoredHostTrack>()
    private var playback = PlaybackState(null, 0, false, clock(), null)

    suspend fun createRoom(request: CreateRoomRequest): CreateRoomResponse = mutex.withLock {
        if (room?.status == RoomStatus.ACTIVE) fail(409, "ACTIVE_ROOM_EXISTS")
        val now = clock()
        val created = Room(idFactory(), roomCodeFactory(), request.name.trim(), request.userId, RoomStatus.ACTIVE, now)
        val host = Member(request.userId, request.displayName.trim(), MemberRole.HOST, false, now)
        room = created; joinToken = idFactory(); members.clear(); members[host.userId] = host
        tracks.clear(); playback = PlaybackState(null, 0, false, now, null)
        CreateRoomResponse(created, host, joinToken!!)
    }

    suspend fun joinByCode(request: JoinRoomRequest): JoinRoomResponse = mutex.withLock {
        join(requireActiveRoom(), request)
    }

    suspend fun joinById(roomId: String, request: JoinRoomRequest): JoinRoomResponse = mutex.withLock {
        val active = requireActiveRoom(roomId)
        if (request.joinToken != joinToken && request.roomCode?.uppercase() != active.roomCode) invalidJoin()
        join(active, request)
    }

    suspend fun snapshot(roomId: String): RoomSnapshot = mutex.withLock {
        val r = requireActiveRoom(roomId)
        RoomSnapshot(r, members.values.toList(), tracks.map { it.track }, currentPlayback())
    }

    suspend fun authenticate(roomId: String, userId: String, token: String): RoomSnapshot = mutex.withLock {
        val r = requireActiveRoom(roomId)
        if (token != joinToken) invalidJoin()
        val m = members[userId] ?: fail(404, "MEMBER_NOT_FOUND")
        members[userId] = m.copy(connected = true)
        RoomSnapshot(r, members.values.toList(), tracks.map { it.track }, currentPlayback())
    }

    suspend fun leave(roomId: String, userId: String) = mutex.withLock {
        if (requireActiveRoom(roomId).hostUserId == userId) { room = room!!.copy(status = RoomStatus.CLOSED); members.clear() }
        else members.remove(userId) ?: fail(404, "MEMBER_NOT_FOUND")
    }

    suspend fun setConnected(roomId: String, userId: String, connected: Boolean): Member = mutex.withLock {
        requireActiveRoom(roomId)
        val m = members[userId] ?: fail(404, "MEMBER_NOT_FOUND")
        m.copy(connected = connected).also { members[userId] = it }
    }

    suspend fun addReadyTrack(input: AddHostTrack): Track = mutex.withLock {
        requireActiveRoom(input.roomId)
        members[input.uploaderId] ?: fail(404, "MEMBER_NOT_FOUND")
        Track(input.roomId, input.roomId, input.title, input.artist, input.durationMs,
            input.fileName, input.fileSize, input.fileHash, input.uploaderId, input.uploaderName,
            tracks.size, TrackStatus.READY, clock()).also { tracks += StoredHostTrack(it, input.storagePath) }
    }

    suspend fun playlist(roomId: String): List<Track> = mutex.withLock {
        requireActiveRoom(roomId); tracks.map { it.track }
    }

    suspend fun storedTrack(trackId: String): StoredHostTrack = mutex.withLock {
        tracks.firstOrNull { it.track.trackId == trackId && it.track.status == TrackStatus.READY }
            ?: fail(404, "TRACK_FILE_NOT_FOUND")
    }

    suspend fun play(roomId: String, command: TrackPlaybackCommand): PlaybackState = mutex.withLock {
        requireHostOrAdmin(roomId, command.userId); requireReadyTrack(roomId, command.trackId)
        schedule(command.trackId, command.positionMs)
    }

    suspend fun seek(roomId: String, command: TrackPlaybackCommand): PlaybackState = mutex.withLock {
        requireHostOrAdmin(roomId, command.userId); requireReadyTrack(roomId, command.trackId)
        schedule(command.trackId, command.positionMs)
    }

    suspend fun pause(roomId: String, command: TrackPlaybackCommand): PlaybackState = mutex.withLock {
        requireHostOrAdmin(roomId, command.userId); requireReadyTrack(roomId, command.trackId)
        PlaybackState(command.trackId, command.positionMs.coerceAtLeast(0), false, clock(), null).also { playback = it }
    }

    suspend fun next(roomId: String, command: NextPlaybackCommand): PlaybackState = mutex.withLock {
        requireHostOrAdmin(roomId, command.userId)
        val idx = tracks.indexOfFirst { it.track.trackId == playback.trackId }
        val nxt = tracks.drop(idx + 1).firstOrNull { it.track.status == TrackStatus.READY } ?: fail(409, "NO_NEXT_TRACK")
        schedule(nxt.track.trackId, command.positionMs)
    }

    suspend fun playback(roomId: String): PlaybackState = mutex.withLock { requireActiveRoom(roomId); currentPlayback() }

    suspend fun syncState(): Pair<String, PlaybackState>? = mutex.withLock {
        val a = room?.takeIf { it.status == RoomStatus.ACTIVE } ?: return@withLock null
        if (!playback.isPlaying) return@withLock null; a.roomId to currentPlayback()
    }

    suspend fun close() = mutex.withLock {
        room = room?.copy(status = RoomStatus.CLOSED); members.clear(); tracks.clear()
        playback = PlaybackState(null, 0, false, clock(), null)
    }

    fun loadRecoverableRoom(): HostRecoverySnapshot? = null

    suspend fun changeRole(roomId: String, targetUserId: String, newRole: MemberRole) = mutex.withLock {
        requireHost(roomId, requireActiveRoom(roomId).hostUserId)
        val m = members[targetUserId] ?: fail(404, "MEMBER_NOT_FOUND")
        members[targetUserId] = m.copy(role = newRole)
        members[targetUserId]!!
    }

    suspend fun removeTrack(roomId: String, trackId: String) = mutex.withLock {
        requireActiveRoom(roomId)
        tracks.removeAll { it.track.trackId == trackId }
    }

    suspend fun reorderPlaylist(roomId: String, orderedTrackIds: List<String>) = mutex.withLock {
        requireActiveRoom(roomId)
        val reordered = orderedTrackIds.mapNotNull { id -> tracks.firstOrNull { it.track.trackId == id } }
        tracks.clear(); tracks.addAll(reordered)
        // Fix orderIndex
        tracks.forEachIndexed { i, stored -> tracks[i] = stored.copy(track = stored.track.copy(orderIndex = i)) }
    }

    private suspend fun join(active: Room, request: JoinRoomRequest): JoinRoomResponse {
        val now = clock(); val ex = members[request.userId]
        val member = Member(request.userId, request.displayName.trim(),
            if (request.userId == active.hostUserId) MemberRole.HOST else MemberRole.MEMBER,
            ex?.connected ?: false, ex?.joinedAt ?: now)
        members[member.userId] = member
        return JoinRoomResponse(active, member, joinToken!!)
    }

    private fun requireActiveRoom(expectedId: String? = null) = room?.also {
        if (expectedId != null && it.roomId != expectedId) fail(404, "ROOM_NOT_FOUND")
        if (it.status != RoomStatus.ACTIVE) fail(410, "ROOM_CLOSED")
    } ?: fail(404, "ROOM_NOT_FOUND")

    private fun requireHostOrAdmin(roomId: String, userId: String) {
        val member = requireActiveRoom(roomId).let { r ->
            members[userId] ?: fail(404, "MEMBER_NOT_FOUND")
        }
        if (member.role != MemberRole.HOST && member.role != MemberRole.ADMIN) fail(403, "HOST_REQUIRED")
    }

    private fun requireHost(roomId: String, userId: String) {
        if (requireActiveRoom(roomId).hostUserId != userId) fail(403, "HOST_REQUIRED")
    }

    private fun requireReadyTrack(roomId: String, trackId: String) {
        tracks.firstOrNull { it.track.roomId == roomId && it.track.trackId == trackId }
            ?.takeIf { it.track.status == TrackStatus.READY } ?: fail(409, "TRACK_NOT_READY")
    }

    private fun schedule(trackId: String, positionMs: Long): PlaybackState {
        val t = clock() + leadTimeMs
        return PlaybackState(trackId, positionMs.coerceAtLeast(0), true, t, t).also { playback = it }
    }

    private fun currentPlayback(): PlaybackState {
        if (!playback.isPlaying) return playback
        val now = clock()
        return playback.copy(positionMs = (playback.positionMs + now - playback.serverTimeMs).coerceAtLeast(0),
            serverTimeMs = now, executeAtServerTimeMs = null)
    }

    private fun invalidJoin(): Nothing = fail(403, "INVALID_JOIN_TOKEN")
    private fun fail(status: Int, code: String, message: String = ""): Nothing =
        throw HostServerError(status, code, message)
}
