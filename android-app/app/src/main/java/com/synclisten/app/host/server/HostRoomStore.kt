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
import com.synclisten.app.host.persistence.HostDao
import com.synclisten.app.host.persistence.HostMemberEntity
import com.synclisten.app.host.persistence.HostPlaybackEntity
import com.synclisten.app.host.persistence.HostRoomEntity
import com.synclisten.app.host.persistence.HostTrackEntity
import com.synclisten.app.host.persistence.RecoveryMarkerEntity
import com.synclisten.protocol.DeviceCredential
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

data class HostRecoverySnapshot(
    val roomId: String,
    val roomName: String,
    val roomCode: String,
    val memberCount: Int,
    val trackCount: Int,
    val currentTrackTitle: String?,
)

class HostRoomStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val roomCodeFactory: () -> String = {
        buildString { repeat(6) { append("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[Random.nextInt(36)]) } }
    },
    private val leadTimeMs: Long = 1_500,
    private val dao: HostDao? = null,
    private val transaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    private val mutex = Mutex()
    private var room: Room? = null
    private var joinToken: String? = null
    private val credentials = mutableMapOf<String, String>()
    private val members = linkedMapOf<String, Member>()
    private val tracks = mutableListOf<StoredHostTrack>()
    private var playback = PlaybackState(null, 0, false, clock(), null)

    // Roll back the in-memory mirror when its database transaction does not commit.
    private suspend fun <T> mutate(block: suspend () -> T): T = mutex.withLock {
        val oldRoom = room
        val oldToken = joinToken
        val oldMembers = members.toMap()
        val oldCredentials = credentials.toMap()
        val oldTracks = tracks.toList()
        val oldPlayback = playback
        try {
            var result: Result<T>? = null
            transaction { result = Result.success(block()) }
            result!!.getOrThrow()
        } catch (error: Throwable) {
            room = oldRoom
            joinToken = oldToken
            members.clear(); members.putAll(oldMembers)
            credentials.clear(); credentials.putAll(oldCredentials)
            tracks.clear(); tracks.addAll(oldTracks)
            playback = oldPlayback
            throw error
        }
    }

    // ── 持久化辅助方法 ──

    private suspend fun persistRoom(r: Room) {
        dao?.upsertRoom(
            HostRoomEntity(
                roomId = r.roomId,
                roomCode = r.roomCode,
                name = r.name,
                hostUserId = r.hostUserId,
                status = r.status.name,
                joinToken = joinToken ?: "",
                createdAt = r.createdAt,
            )
        )
    }

    private suspend fun persistMember(m: Member) {
        val r = room ?: return
        dao?.upsertMember(
            HostMemberEntity(
                roomId = r.roomId,
                userId = m.userId,
                displayName = m.displayName,
                role = m.role.name,
                connected = m.connected,
                joinedAt = m.joinedAt,
                credentialHash = credentials[m.userId].orEmpty(),
            )
        )
    }

    private suspend fun persistTrack(stored: StoredHostTrack) {
        dao?.upsertTrack(
            HostTrackEntity(
                trackId = stored.track.trackId,
                roomId = stored.track.roomId,
                title = stored.track.title,
                artist = stored.track.artist,
                durationMs = stored.track.durationMs,
                fileName = stored.track.fileName,
                fileSize = stored.track.fileSize,
                fileHash = stored.track.fileHash,
                storagePath = stored.storagePath,
                uploaderId = stored.track.uploaderId,
                uploaderName = stored.track.uploaderName,
                orderIndex = stored.track.orderIndex,
                status = stored.track.status.name,
                createdAt = stored.track.createdAt,
            )
        )
    }

    private suspend fun persistPlayback(pb: PlaybackState) {
        val r = room ?: return
        dao?.upsertPlayback(
            HostPlaybackEntity(
                roomId = r.roomId,
                trackId = pb.trackId,
                positionMs = pb.positionMs,
                isPlaying = pb.isPlaying,
                serverTimeMs = pb.serverTimeMs,
                executeAtServerTimeMs = pb.executeAtServerTimeMs,
            )
        )
    }

    private suspend fun persistRecoveryMarker() {
        val r = room ?: return
        dao?.upsertRecoveryMarker(
            RecoveryMarkerEntity(
                roomId = r.roomId,
                hostUserId = r.hostUserId,
                roomName = r.name,
                roomCode = r.roomCode,
                memberCount = members.size,
                trackCount = tracks.size,
                disconnectedAt = clock(),
            )
        )
    }

    private suspend fun deleteRecoveryMarker() {
        dao?.deleteRecoveryMarker()
    }

    private suspend fun clearAllPersistence() {
        dao?.deleteAllTracks()
        dao?.deleteAllMembers()
        dao?.deleteAllPlayback()
        dao?.deleteRoom()
        dao?.deleteRecoveryMarker()
    }

    // ── 房间创建 ──

    suspend fun createRoom(request: CreateRoomRequest, credential: String = ""): CreateRoomResponse = mutate {
        if (room?.status == RoomStatus.ACTIVE) {
            fail(409, "ACTIVE_ROOM_EXISTS", "This device already hosts an active room")
        }
        validateIdentity(request.userId, request.displayName)
        if (request.name.trim().length !in 1..80) fail(400, "VALIDATION_ERROR", "Room name must be 1-80 characters")
        clearAllPersistence()
        credentials.clear()
        credentials[request.userId] = DeviceCredential.hash(credential)
        val now = clock()
        val created = Room(idFactory(), roomCodeFactory(), request.name.trim(), request.userId, RoomStatus.ACTIVE, now)
        val host = Member(request.userId, request.displayName.trim(), MemberRole.HOST, false, now)
        room = created
        joinToken = idFactory()
        members.clear()
        members[host.userId] = host
        tracks.clear()
        playback = PlaybackState(null, 0, false, now, null)
        // 持久化写入
        persistRoom(created)
        persistMember(host)
        persistPlayback(playback)
        persistRecoveryMarker()
        CreateRoomResponse(created, host, joinToken!!)
    }

    // ── 加入 ──

    suspend fun joinByCode(request: JoinRoomRequest, credential: String = ""): JoinRoomResponse = mutate {
        val active = requireActiveRoom()
        if (request.roomCode?.uppercase() != active.roomCode) invalidJoin()
        join(active, request, credential)
    }

    suspend fun joinById(roomId: String, request: JoinRoomRequest, credential: String = ""): JoinRoomResponse = mutate {
        val active = requireActiveRoom(roomId)
        if (request.joinToken != joinToken && request.roomCode?.uppercase() != active.roomCode) invalidJoin()
        join(active, request, credential)
    }

    // ── 快照 ──

    suspend fun snapshot(roomId: String): RoomSnapshot = mutex.withLock {
        val active = requireActiveRoom(roomId)
        RoomSnapshot(active, members.values.toList(), tracks.map(StoredHostTrack::track), currentPlayback())
    }

    // ── 认证 ──

    suspend fun authenticate(roomId: String, userId: String, token: String): RoomSnapshot = mutate {
        val active = requireActiveRoom(roomId)
        if (token != joinToken) invalidJoin()
        val member = members[userId] ?: fail(404, "MEMBER_NOT_FOUND", "Member does not exist")
        members[userId] = member.copy(connected = true)
        persistMember(members[userId]!!)
        RoomSnapshot(active, members.values.toList(), tracks.map(StoredHostTrack::track), currentPlayback())
    }

    // ── 离房 ──

    suspend fun leave(roomId: String, userId: String) = mutate {
        val active = requireActiveRoom(roomId)
        if (active.hostUserId == userId) {
            room = active.copy(status = RoomStatus.CLOSED)
            persistRoom(room!!)
            deleteRecoveryMarker()
            members.clear()
        } else {
            members.remove(userId) ?: fail(404, "MEMBER_NOT_FOUND", "Member does not exist")
            dao?.deleteMember(roomId, userId)
        }
    }

    // ── 连接状态 ──

    suspend fun setConnected(roomId: String, userId: String, connected: Boolean): Member = mutate {
        requireActiveRoom(roomId)
        val member = members[userId] ?: fail(404, "MEMBER_NOT_FOUND", "Member does not exist")
        member.copy(connected = connected).also {
            members[userId] = it
            persistMember(it)
        }
    }

    // ── 曲目 ──

    suspend fun addReadyTrack(input: AddHostTrack): Track = mutate {
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
            orderIndex = (tracks.maxOfOrNull { it.track.orderIndex } ?: -1) + 1,
            status = TrackStatus.READY,
            createdAt = clock(),
        )
        val stored = StoredHostTrack(track, input.storagePath)
        tracks += stored
        persistTrack(stored)
        persistRecoveryMarker()
        track
    }

    suspend fun playlist(roomId: String): List<Track> = mutex.withLock {
        requireActiveRoom(roomId)
        tracks.map(StoredHostTrack::track)
    }

    suspend fun changeRole(roomId: String, targetUserId: String, newRole: MemberRole, actorId: String) = mutate {
        requireHost(roomId, actorId)
        if (newRole == MemberRole.HOST) fail(400, "INVALID_ROLE", "Role must be ADMIN or MEMBER")
        val member = members[targetUserId] ?: fail(404, "MEMBER_NOT_FOUND", "Member does not exist")
        if (member.role == MemberRole.HOST) fail(400, "CANNOT_CHANGE_HOST", "Cannot change host's role")
        members[targetUserId] = member.copy(role = newRole)
        persistMember(members[targetUserId]!!)
    }

    suspend fun removeTrack(roomId: String, trackId: String, actorId: String) = mutate {
        requireHostOrAdmin(roomId, actorId)
        if (playback.trackId == trackId) fail(409, "CURRENT_TRACK_PROTECTED", "Switch tracks before removing the current track")
        if (tracks.none { it.track.trackId == trackId }) fail(404, "TRACK_NOT_FOUND", "Track does not exist")
        tracks.removeAll { it.track.trackId == trackId }
        dao?.deleteTrack(trackId)
        persistRecoveryMarker()
    }

    suspend fun reorderPlaylist(roomId: String, orderedTrackIds: List<String>, actorId: String) = mutate {
        requireHostOrAdmin(roomId, actorId)
        if (orderedTrackIds.size != tracks.size || orderedTrackIds.toSet() != tracks.map { it.track.trackId }.toSet()) {
            fail(409, "PLAYLIST_CONFLICT", "Reorder must contain every current track exactly once")
        }
        val reordered = mutableListOf<StoredHostTrack>()
        for ((index, tid) in orderedTrackIds.withIndex()) {
            val stored = tracks.firstOrNull { it.track.trackId == tid } ?: continue
            val updatedTrack = stored.track.copy(orderIndex = index)
            val updatedStored = stored.copy(track = updatedTrack)
            reordered += updatedStored
            dao?.upsertTrack(
                HostTrackEntity(
                    trackId = updatedTrack.trackId, roomId = updatedTrack.roomId,
                    title = updatedTrack.title, artist = updatedTrack.artist,
                    durationMs = updatedTrack.durationMs, fileName = updatedTrack.fileName,
                    fileSize = updatedTrack.fileSize, fileHash = updatedTrack.fileHash,
                    storagePath = stored.storagePath, uploaderId = updatedTrack.uploaderId,
                    uploaderName = updatedTrack.uploaderName, orderIndex = index,
                    status = updatedTrack.status.name, createdAt = updatedTrack.createdAt,
                )
            )
        }
        tracks.clear()
        tracks.addAll(reordered)
    }

    suspend fun storedTrack(trackId: String): StoredHostTrack = mutex.withLock {
        tracks.firstOrNull { it.track.trackId == trackId && it.track.status == TrackStatus.READY }
            ?: fail(404, "TRACK_FILE_NOT_FOUND", "Track file does not exist")
    }

    // ── 播放控制 ──

    suspend fun play(roomId: String, command: TrackPlaybackCommand): PlaybackState = mutate {
        requireHostOrAdmin(roomId, command.userId)
        requireReadyTrack(roomId, command.trackId)
        schedule(command.trackId, command.positionMs)
    }

    suspend fun seek(roomId: String, command: TrackPlaybackCommand): PlaybackState = mutate {
        requireHostOrAdmin(roomId, command.userId)
        requireReadyTrack(roomId, command.trackId)
        schedule(command.trackId, command.positionMs, playback.isPlaying)
    }

    suspend fun pause(roomId: String, command: TrackPlaybackCommand): PlaybackState = mutate {
        requireHostOrAdmin(roomId, command.userId)
        requireReadyTrack(roomId, command.trackId)
        PlaybackState(command.trackId, command.positionMs.coerceAtLeast(0), false, clock(), null)
            .also { playback = it; persistPlayback(it) }
    }

    suspend fun next(roomId: String, command: NextPlaybackCommand): PlaybackState = mutate {
        requireHostOrAdmin(roomId, command.userId)
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
        if (!playback.isPlaying || clock() < (playback.executeAtServerTimeMs ?: Long.MIN_VALUE)) return@withLock null
        active.roomId to currentPlayback()
    }

    // ── 关闭语义 ──

    /** 显式关闭 —— 清理 RecoveryMarker，不可恢复 */
    suspend fun closeAndCleanup() = mutate {
        room = room?.copy(status = RoomStatus.CLOSED)
        deleteRecoveryMarker()
        clearAllPersistence()
        members.clear()
        tracks.clear()
        playback = PlaybackState(null, 0, false, clock(), null)
    }

    /** 异常终止 —— 保留 RecoveryMarker，可恢复 */
    suspend fun emergencyShutdown() = mutate {
        val r = room ?: return@mutate
        room = r.copy(status = RoomStatus.CLOSED)
        persistRoom(room!!)
        persistPlayback(currentPlayback().copy(isPlaying = false))
        persistRecoveryMarker()
    }

    /** 兼容旧调用 —— 等同于 closeAndCleanup */
    @Deprecated("Use closeAndCleanup() or emergencyShutdown()", ReplaceWith("closeAndCleanup()"))
    suspend fun close() = closeAndCleanup()

    // ── 恢复 ──

    suspend fun loadRecoverableRoom(): HostRecoverySnapshot? {
        val d = dao ?: return null
        val marker = d.getRecoveryMarker() ?: return null
        val roomEntity = d.getRoom() ?: run { d.deleteRecoveryMarker(); return null }
        // ACTIVE（force-stop 未调用 emergencyShutdown）或 CLOSED（正常 emergencyShutdown）均可恢复
        if (roomEntity.status != "ACTIVE" && roomEntity.status != "CLOSED") return null
        val tracks = d.getTracks(roomEntity.roomId)
        val currentTrackId = d.getPlayback(roomEntity.roomId)?.trackId
        val currentTrack = tracks.firstOrNull { it.trackId == currentTrackId }
        return HostRecoverySnapshot(
            roomId = roomEntity.roomId,
            roomName = roomEntity.name,
            roomCode = roomEntity.roomCode,
            memberCount = marker.memberCount,
            trackCount = marker.trackCount,
            currentTrackTitle = currentTrack?.title,
        )
    }

    suspend fun recoverRoom(): RoomSnapshot = mutate {
        val d = dao ?: fail(500, "PERSISTENCE_UNAVAILABLE", "Persistence layer is not available")
        d.getRecoveryMarker() ?: fail(409, "ROOM_NOT_RECOVERABLE", "No recovery marker exists")
        val roomEntity = d.getRoom() ?: fail(404, "ROOM_NOT_FOUND", "No room to recover")
        if (roomEntity.status != "ACTIVE" && roomEntity.status != "CLOSED") fail(409, "ROOM_NOT_RECOVERABLE", "Room is not in a recoverable state")
        val now = clock()

        // 重建房间
        val recovered = Room(
            roomId = roomEntity.roomId,
            roomCode = roomEntity.roomCode,
            name = roomEntity.name,
            hostUserId = roomEntity.hostUserId,
            status = RoomStatus.ACTIVE,
            createdAt = roomEntity.createdAt,
        )
        room = recovered
        joinToken = roomEntity.joinToken

        // 重建成员（全部离线）
        members.clear()
        credentials.clear()
        val memberEntities = d.getMembers(roomEntity.roomId)
        for (me in memberEntities) {
            val member = Member(
                userId = me.userId,
                displayName = me.displayName,
                role = try { MemberRole.valueOf(me.role) } catch (_: Exception) { MemberRole.MEMBER },
                connected = false,
                joinedAt = me.joinedAt,
            )
            members[member.userId] = member
            credentials[member.userId] = me.credentialHash
        }
        d.updateAllConnected(roomEntity.roomId, false)

        // 重建曲目
        tracks.clear()
        val trackEntities = d.getTracks(roomEntity.roomId)
        for (te in trackEntities) {
            val track = Track(
                trackId = te.trackId,
                roomId = te.roomId,
                title = te.title,
                artist = te.artist,
                durationMs = te.durationMs,
                fileName = te.fileName,
                fileSize = te.fileSize,
                fileHash = te.fileHash,
                uploaderId = te.uploaderId,
                uploaderName = te.uploaderName,
                orderIndex = te.orderIndex,
                status = try { TrackStatus.valueOf(te.status) } catch (_: Exception) { TrackStatus.READY },
                createdAt = te.createdAt,
            )
            tracks += StoredHostTrack(track, te.storagePath)
        }

        // 重播状态恢复为暂停
        val pbEntity = d.getPlayback(roomEntity.roomId)
        playback = PlaybackState(
            trackId = pbEntity?.trackId,
            positionMs = pbEntity?.positionMs?.coerceAtLeast(0) ?: 0,
            isPlaying = false,
            serverTimeMs = now,
            executeAtServerTimeMs = null,
        )

        // 更新持久化
        persistRoom(recovered)
        persistPlayback(playback)
        RoomSnapshot(recovered, members.values.toList(), tracks.map(StoredHostTrack::track), playback)
    }

    /** 忽略恢复 —— 删除 RecoveryMarker 和所有持久化数据 */
    suspend fun dismissRecovery() = mutate {
        deleteRecoveryMarker()
        clearAllPersistence()
        room = null
        joinToken = null
        members.clear()
        tracks.clear()
        playback = PlaybackState(null, 0, false, clock(), null)
    }

    // ── 内部辅助 ──

    private suspend fun join(active: Room, request: JoinRoomRequest, credential: String): JoinRoomResponse {
        validateIdentity(request.userId, request.displayName)
        if (members.containsKey(request.userId) && !DeviceCredential.matches(credentials[request.userId], credential)) {
            fail(403, "INVALID_CREDENTIAL", "Member credential is invalid")
        }
        credentials[request.userId] = DeviceCredential.hash(credential)
        val now = clock()
        val existing = members[request.userId]
        val member = Member(
            request.userId,
            request.displayName.trim(),
            existing?.role ?: MemberRole.MEMBER,
            existing?.connected ?: false,
            existing?.joinedAt ?: now,
        )
        members[member.userId] = member
        persistMember(member)
        persistRecoveryMarker()
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

    private fun requireHostOrAdmin(roomId: String, userId: String) {
        requireActiveRoom(roomId)
        val member = members[userId] ?: fail(404, "MEMBER_NOT_FOUND", "Member does not exist")
        if (member.role != MemberRole.HOST && member.role != MemberRole.ADMIN) {
            fail(403, "HOST_REQUIRED", "Only the host or admin can perform this action")
        }
    }

    private fun requireReadyTrack(roomId: String, trackId: String) {
        val track = tracks.firstOrNull { it.track.roomId == roomId && it.track.trackId == trackId }?.track
        if (track?.status != TrackStatus.READY) fail(409, "TRACK_NOT_READY", "Track is not ready for playback")
    }

    private suspend fun schedule(trackId: String, positionMs: Long, isPlaying: Boolean = true): PlaybackState {
        val executeAt = clock() + leadTimeMs
        return PlaybackState(trackId, positionMs.coerceAtLeast(0), isPlaying, executeAt, executeAt)
            .also { playback = it; persistPlayback(it) }
    }

    private fun currentPlayback(): PlaybackState {
        if (!playback.isPlaying || clock() < (playback.executeAtServerTimeMs ?: Long.MIN_VALUE)) return playback
        val now = clock()
        return playback.copy(
            positionMs = (playback.positionMs + now - playback.serverTimeMs).coerceAtLeast(0),
            serverTimeMs = now,
            executeAtServerTimeMs = null,
        )
    }

    suspend fun authorize(roomId: String, userId: String, credential: String): Member = mutex.withLock {
        requireActiveRoom(roomId)
        val member = members[userId] ?: fail(403, "INVALID_MEMBER", "User is not a room member")
        if (!DeviceCredential.matches(credentials[userId], credential)) fail(403, "INVALID_CREDENTIAL", "Member credential is invalid")
        member
    }

    private fun validateIdentity(userId: String, displayName: String) {
        if (userId.length !in 1..128 || displayName.trim().length !in 1..40) {
            fail(400, "VALIDATION_ERROR", "Invalid identity or display name")
        }
    }

    private fun invalidJoin(): Nothing = fail(403, "INVALID_JOIN_TOKEN", "Join credentials are invalid")

    private fun fail(status: Int, code: String, message: String): Nothing =
        throw HostServerError(status, code, message)
}
