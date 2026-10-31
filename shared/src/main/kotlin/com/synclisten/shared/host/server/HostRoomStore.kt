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
import com.synclisten.protocol.DeviceCredential
import com.synclisten.cloud.CloudTrackReference
import com.synclisten.cloud.isValid
import com.synclisten.cloud.CloudReadiness
import com.synclisten.cloud.CloudReadinessSnapshot
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
    private val credentials = mutableMapOf<String, String>()
    private val members = linkedMapOf<String, Member>()
    private val tracks = mutableListOf<StoredHostTrack>()
    private var playback = PlaybackState(null, 0, false, clock(), null)
    private val cloudReadiness = CloudReadiness()

    suspend fun createRoom(request: CreateRoomRequest, credential: String = ""): CreateRoomResponse = mutex.withLock {
        if (room?.status == RoomStatus.ACTIVE) fail(409, "ACTIVE_ROOM_EXISTS")
        if (request.name.trim().length !in 1..80 || request.displayName.trim().length !in 1..40 || request.userId.length !in 1..128) fail(400, "VALIDATION_ERROR")
        if (request.protocolVersion !in 1..2) fail(400, "VALIDATION_ERROR")
        credentials.clear()
        credentials[request.userId] = DeviceCredential.hash(credential)
        val now = clock()
        val created = Room(idFactory(), roomCodeFactory(), request.name.trim(), request.userId, RoomStatus.ACTIVE, now)
        val host = Member(request.userId, request.displayName.trim(), MemberRole.HOST, false, now, request.protocolVersion)
        room = created; joinToken = idFactory(); members.clear(); members[host.userId] = host
        tracks.clear(); cloudReadiness.cancel(); playback = PlaybackState(null, 0, false, now, null)
        CreateRoomResponse(created, host, joinToken!!)
    }

    suspend fun joinByCode(request: JoinRoomRequest, credential: String = ""): JoinRoomResponse = mutex.withLock {
        val active = requireActiveRoom()
        if (request.roomCode?.uppercase() != active.roomCode) invalidJoin()
        join(active, request, credential)
    }

    suspend fun joinById(roomId: String, request: JoinRoomRequest, credential: String = ""): JoinRoomResponse = mutex.withLock {
        val active = requireActiveRoom(roomId)
        if (request.joinToken != joinToken && request.roomCode?.uppercase() != active.roomCode) invalidJoin()
        join(active, request, credential)
    }

    suspend fun snapshot(roomId: String): RoomSnapshot = mutex.withLock {
        val r = requireActiveRoom(roomId)
        RoomSnapshot(r, members.values.toList(), tracks.map { it.track }, currentPlayback())
    }

    suspend fun authenticate(roomId: String, userId: String, token: String): RoomSnapshot = mutex.withLock {
        val r = requireActiveRoom(roomId)
        if (token != joinToken) invalidJoin()
        val m = members[userId] ?: fail(404, "MEMBER_NOT_FOUND")
        if (m.protocolVersion < 2 && tracks.any { it.track.cloud != null }) {
            fail(409, "CLIENT_UPGRADE_REQUIRED", "房间包含云曲目，请更新客户端")
        }
        members[userId] = m.copy(connected = true)
        if (playback.preparation?.committed == false) playback = playback.copy(preparation = cloudReadiness.join(userId))
        RoomSnapshot(r, members.values.toList(), tracks.map { it.track }, currentPlayback())
    }

    suspend fun leave(roomId: String, userId: String) = mutex.withLock {
        if (requireActiveRoom(roomId).hostUserId == userId) { room = room!!.copy(status = RoomStatus.CLOSED); members.clear(); cloudReadiness.cancel() }
        else {
            members.remove(userId) ?: fail(404, "MEMBER_NOT_FOUND")
            if (playback.preparation?.committed == false) playback = playback.copy(preparation = cloudReadiness.disconnect(userId))
        }
    }

    suspend fun setConnected(roomId: String, userId: String, connected: Boolean): Member = mutex.withLock {
        requireActiveRoom(roomId)
        val m = members[userId] ?: fail(404, "MEMBER_NOT_FOUND")
        m.copy(connected = connected).also {
            members[userId] = it
            if (playback.preparation?.committed == false) {
                playback = playback.copy(preparation = if (connected) cloudReadiness.join(userId) else cloudReadiness.disconnect(userId))
            }
        }
    }

    suspend fun addReadyTrack(input: AddHostTrack): Track = mutex.withLock {
        requireActiveRoom(input.roomId)
        members[input.uploaderId] ?: fail(404, "MEMBER_NOT_FOUND")
        Track(idFactory(), input.roomId, input.title, input.artist, input.durationMs,
            input.fileName, input.fileSize, input.fileHash, input.uploaderId, input.uploaderName,
            (tracks.maxOfOrNull { it.track.orderIndex } ?: -1) + 1, TrackStatus.READY, clock()).also { tracks += StoredHostTrack(it, input.storagePath) }
    }

    suspend fun addCloudTrack(roomId: String, actorId: String, reference: CloudTrackReference): Track = mutex.withLock {
        requireActiveRoom(roomId)
        val member = members[actorId] ?: fail(404, "MEMBER_NOT_FOUND")
        if (member.protocolVersion < 2 || members.values.any { it.connected && it.protocolVersion < 2 }) {
            fail(409, "CLIENT_UPGRADE_REQUIRED", "房间成员需要更新客户端才能使用云曲目")
        }
        if (!reference.isValid()) fail(400, "VALIDATION_ERROR")
        val source = reference.track
        Track(idFactory(), roomId, source.title, source.artists.joinToString(" / ").ifBlank { null },
            source.durationMs ?: 0, "", 0, "", actorId, member.displayName,
            (tracks.maxOfOrNull { it.track.orderIndex } ?: -1) + 1, TrackStatus.READY, clock(), reference)
            .also { tracks += StoredHostTrack(it, "") }
    }

    suspend fun playlist(roomId: String): List<Track> = mutex.withLock {
        requireActiveRoom(roomId); tracks.map { it.track }
    }

    suspend fun storedTrack(trackId: String): StoredHostTrack = mutex.withLock {
        tracks.firstOrNull { it.track.trackId == trackId && it.track.status == TrackStatus.READY && it.track.cloud == null }
            ?: fail(404, "TRACK_FILE_NOT_FOUND")
    }

    suspend fun play(roomId: String, command: TrackPlaybackCommand): PlaybackState = mutex.withLock {
        requireHostOrAdmin(roomId, command.userId); requireReadyTrack(roomId, command.trackId)
        requireCloudPlaybackAvailable(command.trackId)
        cloudReadiness.cancel()
        schedule(command.trackId, command.positionMs)
    }

    suspend fun seek(roomId: String, command: TrackPlaybackCommand): PlaybackState = mutex.withLock {
        requireHostOrAdmin(roomId, command.userId); requireReadyTrack(roomId, command.trackId)
        requireCloudPlaybackAvailable(command.trackId)
        cloudReadiness.cancel()
        schedule(command.trackId, command.positionMs, playback.isPlaying)
    }

    suspend fun pause(roomId: String, command: TrackPlaybackCommand): PlaybackState = mutex.withLock {
        requireHostOrAdmin(roomId, command.userId); requireReadyTrack(roomId, command.trackId)
        cloudReadiness.cancel()
        PlaybackState(command.trackId, command.positionMs.coerceAtLeast(0), false, clock(), null).also { playback = it }
    }

    suspend fun next(roomId: String, command: NextPlaybackCommand): PlaybackState = mutex.withLock {
        requireHostOrAdmin(roomId, command.userId)
        val idx = tracks.indexOfFirst { it.track.trackId == playback.trackId }
        val nxt = tracks.drop(idx + 1).firstOrNull { it.track.status == TrackStatus.READY } ?: fail(409, "NO_NEXT_TRACK")
        requireCloudPlaybackAvailable(nxt.track.trackId)
        cloudReadiness.cancel()
        schedule(nxt.track.trackId, command.positionMs)
    }

    suspend fun prepareCloud(roomId: String, actorId: String, trackId: String, positionMs: Long, playAfterReady: Boolean = true): PlaybackState = mutex.withLock {
        requireHostOrAdmin(roomId, actorId)
        if (members[actorId]?.connected != true) fail(409, "MEMBER_NOT_CONNECTED", "控制者尚未连接房间")
        requireReadyTrack(roomId, trackId)
        if (tracks.first { it.track.trackId == trackId }.track.cloud == null) fail(400, "VALIDATION_ERROR", "不是云曲目")
        if (positionMs < 0) fail(400, "VALIDATION_ERROR", "播放位置无效")
        val preparation = cloudReadiness.begin(trackId, members.values.filter { it.connected }.map { it.userId }, playAfterReady)
        PlaybackState(trackId, positionMs, false, clock(), null, preparation).also { playback = it }
    }

    suspend fun reportCloudReady(roomId: String, actorId: String, trackId: String, generation: Long, ready: Boolean): PlaybackState = mutex.withLock {
        requireActiveRoom(roomId)
        if (members[actorId]?.connected != true) fail(409, "MEMBER_NOT_CONNECTED", "成员尚未连接房间")
        val preparation = try { cloudReadiness.report(generation, trackId, actorId, ready) }
            catch (_: IllegalStateException) { fail(409, "PREPARATION_STALE", "准备状态已过期") }
        playback = playback.copy(preparation = preparation)
        if (preparation.waiting.isEmpty()) {
            val committed = cloudReadiness.commit(generation, false)
            if (committed.playAfterReady) schedule(trackId, playback.positionMs, preparation = committed)
            else playback.copy(preparation = committed).also { playback = it }
        } else playback
    }

    suspend fun commitCloud(roomId: String, actorId: String, generation: Long, force: Boolean): PlaybackState = mutex.withLock {
        requireHostOrAdmin(roomId, actorId)
        if (force) requireHost(roomId, actorId)
        val preparation = try { cloudReadiness.commit(generation, force) }
            catch (_: IllegalStateException) { fail(409, "PREPARATION_NOT_READY", "成员尚未准备好或准备状态已过期") }
        if (preparation.playAfterReady) schedule(preparation.trackId, playback.positionMs, preparation = preparation)
        else playback.copy(preparation = preparation).also { playback = it }
    }

    suspend fun playback(roomId: String): PlaybackState = mutex.withLock { requireActiveRoom(roomId); currentPlayback() }

    suspend fun syncState(): Pair<String, PlaybackState>? = mutex.withLock {
        val a = room?.takeIf { it.status == RoomStatus.ACTIVE } ?: return@withLock null
        if (!playback.isPlaying || clock() < (playback.executeAtServerTimeMs ?: Long.MIN_VALUE)) return@withLock null; a.roomId to currentPlayback()
    }

    suspend fun close() = mutex.withLock {
        room = room?.copy(status = RoomStatus.CLOSED); members.clear(); tracks.clear()
        cloudReadiness.cancel()
        playback = PlaybackState(null, 0, false, clock(), null)
    }

    fun loadRecoverableRoom(): HostRecoverySnapshot? = null

    suspend fun changeRole(roomId: String, targetUserId: String, newRole: MemberRole, actorId: String) = mutex.withLock {
        requireHost(roomId, actorId)
        if (targetUserId == requireActiveRoom(roomId).hostUserId || newRole == MemberRole.HOST) fail(400, "INVALID_ROLE")
        val m = members[targetUserId] ?: fail(404, "MEMBER_NOT_FOUND")
        members[targetUserId] = m.copy(role = newRole)
        members[targetUserId]!!
    }

    suspend fun removeTrack(roomId: String, trackId: String, actorId: String) = mutex.withLock {
        requireHostOrAdmin(roomId, actorId)
        if (playback.trackId == trackId) fail(409, "CURRENT_TRACK_PROTECTED")
        if (tracks.none { it.track.trackId == trackId }) fail(404, "TRACK_NOT_FOUND")
        tracks.removeAll { it.track.trackId == trackId }
    }

    suspend fun reorderPlaylist(roomId: String, orderedTrackIds: List<String>, actorId: String) = mutex.withLock {
        requireHostOrAdmin(roomId, actorId)
        if (orderedTrackIds.size != tracks.size || orderedTrackIds.toSet() != tracks.map { it.track.trackId }.toSet()) fail(409, "PLAYLIST_CONFLICT")
        val reordered = orderedTrackIds.mapNotNull { id -> tracks.firstOrNull { it.track.trackId == id } }
        tracks.clear(); tracks.addAll(reordered)
        // Fix orderIndex
        tracks.forEachIndexed { i, stored -> tracks[i] = stored.copy(track = stored.track.copy(orderIndex = i)) }
    }

    private suspend fun join(active: Room, request: JoinRoomRequest, credential: String): JoinRoomResponse {
        if (request.displayName.trim().length !in 1..40 || request.userId.length !in 1..128) fail(400, "VALIDATION_ERROR")
        if (request.protocolVersion !in 1..2) fail(400, "VALIDATION_ERROR")
        if (request.protocolVersion < 2 && tracks.any { it.track.cloud != null }) {
            fail(409, "CLIENT_UPGRADE_REQUIRED", "房间包含云曲目，请更新客户端")
        }
        if (members.containsKey(request.userId) && !DeviceCredential.matches(credentials[request.userId], credential)) fail(403, "INVALID_CREDENTIAL")
        credentials[request.userId] = DeviceCredential.hash(credential)
        val now = clock(); val ex = members[request.userId]
        val member = Member(request.userId, request.displayName.trim(),
            ex?.role ?: MemberRole.MEMBER,
            ex?.connected ?: false, ex?.joinedAt ?: now, request.protocolVersion)
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

    private fun requireCloudPlaybackAvailable(trackId: String) {
        if (tracks.firstOrNull { it.track.trackId == trackId }?.track?.cloud != null) {
            fail(409, "CLOUD_SYNC_UNAVAILABLE", "云曲目的多人准备尚不可用")
        }
    }

    private fun schedule(trackId: String, positionMs: Long, isPlaying: Boolean = true, preparation: CloudReadinessSnapshot? = null): PlaybackState {
        val t = clock() + leadTimeMs
        return PlaybackState(trackId, positionMs.coerceAtLeast(0), isPlaying, t, t, preparation).also { playback = it }
    }

    private fun currentPlayback(): PlaybackState {
        if (!playback.isPlaying || clock() < (playback.executeAtServerTimeMs ?: Long.MIN_VALUE)) return playback
        val now = clock()
        return playback.copy(positionMs = (playback.positionMs + now - playback.serverTimeMs).coerceAtLeast(0),
            serverTimeMs = now, executeAtServerTimeMs = null)
    }

    suspend fun authorize(roomId: String, userId: String, credential: String): Member = mutex.withLock {
        requireActiveRoom(roomId)
        val member = members[userId] ?: fail(403, "INVALID_MEMBER", "User is not a room member")
        if (!DeviceCredential.matches(credentials[userId], credential)) fail(403, "INVALID_CREDENTIAL", "Member credential is invalid")
        if (member.protocolVersion < 2 && tracks.any { it.track.cloud != null }) {
            fail(409, "CLIENT_UPGRADE_REQUIRED", "房间包含云曲目，请更新客户端")
        }
        member
    }

    private fun invalidJoin(): Nothing = fail(403, "INVALID_JOIN_TOKEN")
    private fun fail(status: Int, code: String, message: String = ""): Nothing =
        throw HostServerError(status, code, message)
}
