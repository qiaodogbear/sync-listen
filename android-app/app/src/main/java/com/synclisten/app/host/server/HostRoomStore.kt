package com.synclisten.app.host.server

import com.synclisten.app.data.CreateRoomRequest
import com.synclisten.app.data.CreateRoomResponse
import com.synclisten.app.data.JoinRoomRequest
import com.synclisten.app.data.JoinRoomResponse
import com.synclisten.app.data.NextPlaybackCommand
import com.synclisten.app.data.RoomSnapshot
import com.synclisten.app.data.TrackPlaybackCommand
import com.synclisten.app.domain.model.Member
import com.synclisten.app.domain.model.MemberRole
import com.synclisten.app.domain.model.PlaybackState
import com.synclisten.app.domain.model.Room
import com.synclisten.app.domain.model.RoomStatus
import com.synclisten.app.domain.model.Track
import com.synclisten.app.domain.model.TrackStatus
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AddHostTrack(
    val roomId: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val fileName: String,
    val fileSize: Long,
    val fileHash: String,
    val storagePath: String,
    val uploaderId: String,
    val uploaderName: String,
)

data class StoredHostTrack(val track: Track, val storagePath: String)

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
        if (room?.status == RoomStatus.ACTIVE) {
            fail(409, "ACTIVE_ROOM_EXISTS", "This device already hosts an active room")
        }
        val now = clock()
        val created = Room(idFactory(), roomCodeFactory(), request.name.trim(), request.userId, RoomStatus.ACTIVE, now)
        val host = Member(request.userId, request.displayName.trim(), MemberRole.HOST, false, now)
        room = created
        joinToken = idFactory()
        members.clear()
        members[host.userId] = host
        tracks.clear()
        playback = PlaybackState(null, 0, false, now, null)
        CreateRoomResponse(created, host, joinToken!!)
    }

    suspend fun joinByCode(request: JoinRoomRequest): JoinRoomResponse = mutex.withLock {
        val active = requireActiveRoom()
        if (request.roomCode?.uppercase() != active.roomCode) invalidJoin()
        join(active, request)
    }

    suspend fun joinById(roomId: String, request: JoinRoomRequest): JoinRoomResponse = mutex.withLock {
        val active = requireActiveRoom(roomId)
        if (request.joinToken != joinToken && request.roomCode?.uppercase() != active.roomCode) invalidJoin()
        join(active, request)
    }

    suspend fun snapshot(roomId: String): RoomSnapshot = mutex.withLock {
        val active = requireActiveRoom(roomId)
        RoomSnapshot(active, members.values.toList(), tracks.map(StoredHostTrack::track), currentPlayback())
    }

    suspend fun authenticate(roomId: String, userId: String, token: String): RoomSnapshot = mutex.withLock {
        val active = requireActiveRoom(roomId)
        if (token != joinToken) invalidJoin()
        val member = members[userId] ?: fail(404, "MEMBER_NOT_FOUND", "Member does not exist")
        members[userId] = member.copy(connected = true)
        RoomSnapshot(active, members.values.toList(), tracks.map(StoredHostTrack::track), currentPlayback())
    }

    suspend fun leave(roomId: String, userId: String) = mutex.withLock {
        val active = requireActiveRoom(roomId)
        if (active.hostUserId == userId) {
            room = active.copy(status = RoomStatus.CLOSED)
            members.clear()
        } else {
            members.remove(userId) ?: fail(404, "MEMBER_NOT_FOUND", "Member does not exist")
        }
    }

    suspend fun setConnected(roomId: String, userId: String, connected: Boolean): Member = mutex.withLock {
        requireActiveRoom(roomId)
        val member = members[userId] ?: fail(404, "MEMBER_NOT_FOUND", "Member does not exist")
        member.copy(connected = connected).also { members[userId] = it }
    }

    suspend fun addReadyTrack(input: AddHostTrack): Track = mutex.withLock {
        requireActiveRoom(input.roomId)
        members[input.uploaderId] ?: fail(404, "MEMBER_NOT_FOUND", "Uploader is not a room member")
        val track = Track(
            trackId = idFactory(),
            roomId = input.roomId,
            title = input.title,
            artist = input.artist,
            durationMs = input.durationMs,
            fileName = input.fileName,
            fileSize = input.fileSize,
            fileHash = input.fileHash,
            uploaderId = input.uploaderId,
            uploaderName = input.uploaderName,
            orderIndex = tracks.size,
            status = TrackStatus.READY,
            createdAt = clock(),
        )
        tracks += StoredHostTrack(track, input.storagePath)
        track
    }

    suspend fun playlist(roomId: String): List<Track> = mutex.withLock {
        requireActiveRoom(roomId)
        tracks.map(StoredHostTrack::track)
    }

    suspend fun storedTrack(trackId: String): StoredHostTrack = mutex.withLock {
        tracks.firstOrNull { it.track.trackId == trackId && it.track.status == TrackStatus.READY }
            ?: fail(404, "TRACK_FILE_NOT_FOUND", "Track file does not exist")
    }

    suspend fun play(roomId: String, command: TrackPlaybackCommand): PlaybackState = mutex.withLock {
        requireHost(roomId, command.userId)
        requireReadyTrack(roomId, command.trackId)
        schedule(command.trackId, command.positionMs)
    }

    suspend fun seek(roomId: String, command: TrackPlaybackCommand): PlaybackState = mutex.withLock {
        requireHost(roomId, command.userId)
        requireReadyTrack(roomId, command.trackId)
        schedule(command.trackId, command.positionMs)
    }

    suspend fun pause(roomId: String, command: TrackPlaybackCommand): PlaybackState = mutex.withLock {
        requireHost(roomId, command.userId)
        requireReadyTrack(roomId, command.trackId)
        PlaybackState(command.trackId, command.positionMs.coerceAtLeast(0), false, clock(), null).also { playback = it }
    }

    suspend fun next(roomId: String, command: NextPlaybackCommand): PlaybackState = mutex.withLock {
        requireHost(roomId, command.userId)
        val currentIndex = tracks.indexOfFirst { it.track.trackId == playback.trackId }
        val next = tracks.drop(currentIndex + 1).firstOrNull { it.track.status == TrackStatus.READY }
            ?: fail(409, "NO_NEXT_TRACK", "No next ready track exists")
        schedule(next.track.trackId, command.positionMs)
    }

    suspend fun playback(roomId: String): PlaybackState = mutex.withLock {
        requireActiveRoom(roomId)
        currentPlayback()
    }

    suspend fun syncState(): Pair<String, PlaybackState>? = mutex.withLock {
        val active = room?.takeIf { it.status == RoomStatus.ACTIVE } ?: return@withLock null
        if (!playback.isPlaying) return@withLock null
        active.roomId to currentPlayback()
    }

    suspend fun close() = mutex.withLock {
        room = room?.copy(status = RoomStatus.CLOSED)
        members.clear()
        tracks.clear()
        playback = PlaybackState(null, 0, false, clock(), null)
    }

    private fun join(active: Room, request: JoinRoomRequest): JoinRoomResponse {
        val now = clock()
        val existing = members[request.userId]
        val member = Member(
            request.userId,
            request.displayName.trim(),
            if (request.userId == active.hostUserId) MemberRole.HOST else MemberRole.MEMBER,
            existing?.connected ?: false,
            existing?.joinedAt ?: now,
        )
        members[member.userId] = member
        return JoinRoomResponse(active, member, joinToken!!)
    }

    private fun requireActiveRoom(expectedId: String? = null): Room {
        val current = room ?: fail(404, "ROOM_NOT_FOUND", "Room does not exist")
        if (expectedId != null && current.roomId != expectedId) fail(404, "ROOM_NOT_FOUND", "Room does not exist")
        if (current.status != RoomStatus.ACTIVE) fail(410, "ROOM_CLOSED", "Room is closed")
        return current
    }

    private fun requireHost(roomId: String, userId: String) {
        val active = requireActiveRoom(roomId)
        if (active.hostUserId != userId) fail(403, "HOST_REQUIRED", "Only the host can control playback")
    }

    private fun requireReadyTrack(roomId: String, trackId: String) {
        val track = tracks.firstOrNull { it.track.roomId == roomId && it.track.trackId == trackId }?.track
        if (track?.status != TrackStatus.READY) fail(409, "TRACK_NOT_READY", "Track is not ready for playback")
    }

    private fun schedule(trackId: String, positionMs: Long): PlaybackState {
        val executeAt = clock() + leadTimeMs
        return PlaybackState(trackId, positionMs.coerceAtLeast(0), true, executeAt, executeAt).also { playback = it }
    }

    private fun currentPlayback(): PlaybackState {
        if (!playback.isPlaying) return playback
        val now = clock()
        return playback.copy(
            positionMs = (playback.positionMs + now - playback.serverTimeMs).coerceAtLeast(0),
            serverTimeMs = now,
            executeAtServerTimeMs = null,
        )
    }

    private fun invalidJoin(): Nothing = fail(403, "INVALID_JOIN_TOKEN", "Join credentials are invalid")

    private fun fail(status: Int, code: String, message: String): Nothing =
        throw HostServerError(status, code, message)
}
