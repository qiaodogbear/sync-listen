package com.synclisten.shared.data

import com.synclisten.cloud.CloudResolution
import com.synclisten.cloud.CloudSearchRequest
import com.synclisten.cloud.CloudSearchResponse
import com.synclisten.cloud.CloudTrack
import com.synclisten.cloud.CloudLyrics
import com.synclisten.cloud.CloudTrackReference
import com.synclisten.cloud.CloudPrepareRequest
import com.synclisten.cloud.CloudReadyRequest
import com.synclisten.cloud.CloudCommitRequest
import com.synclisten.shared.domain.model.ErrorResponse
import java.io.IOException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

interface RoomRemoteDataSource {
    suspend fun addCloudTrack(roomId: String, reference: CloudTrackReference): CloudTrackResponse = unsupported()
    suspend fun prepareCloud(roomId: String, request: CloudPrepareRequest): PlaybackResponse = unsupported()
    suspend fun cloudReady(roomId: String, request: CloudReadyRequest): PlaybackResponse = unsupported()
    suspend fun commitCloud(roomId: String, request: CloudCommitRequest): PlaybackResponse = unsupported()
    suspend fun cloudLyrics(roomId: String, track: CloudTrack): CloudLyrics = unsupported()
    suspend fun searchCloud(roomId: String, request: CloudSearchRequest): CloudSearchResponse = unsupported()
    suspend fun resolveCloud(roomId: String, track: CloudTrack): CloudResolution = unsupported()
    suspend fun createRoom(request: CreateRoomRequest): CreateRoomResponse = unsupported()
    suspend fun joinRoom(request: JoinRoomRequest): JoinRoomResponse = unsupported()
    suspend fun joinRoomById(roomId: String, request: JoinRoomRequest): JoinRoomResponse = unsupported()
    suspend fun getRoom(roomId: String): RoomSnapshot = unsupported()
    suspend fun leaveRoom(roomId: String, userId: String): Unit = unsupported()
    suspend fun getPlaylist(roomId: String): PlaylistResponse = unsupported()
    suspend fun getServerTime(): ServerTimeResponse = unsupported()
    suspend fun play(roomId: String, command: TrackPlaybackCommand): PlaybackResponse = unsupported()
    suspend fun pause(roomId: String, command: TrackPlaybackCommand): PlaybackResponse = unsupported()
    suspend fun seek(roomId: String, command: TrackPlaybackCommand): PlaybackResponse = unsupported()
    suspend fun next(roomId: String, command: NextPlaybackCommand): PlaybackResponse = unsupported()
    suspend fun changeMemberRole(roomId: String, userId: String, role: String): ChangeRoleResponse = unsupported()
    suspend fun deleteTrack(roomId: String, trackId: String): Unit = unsupported()
    suspend fun reorderPlaylist(roomId: String, orderedTrackIds: List<String>): ReorderResponse = unsupported()

    private fun unsupported(): Nothing = error("Remote operation is not implemented")
}

sealed interface RepositoryResult<out T> {
    data class Success<T>(val value: T) : RepositoryResult<T>
    data class Failure(val message: String, val code: String? = null) : RepositoryResult<Nothing>
}

class RoomRepository(
    private val remote: RoomRemoteDataSource,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun addCloudTrack(roomId: String, reference: CloudTrackReference): RepositoryResult<CloudTrackResponse> =
        cloudRequest { remote.addCloudTrack(roomId, reference) }
    suspend fun prepareCloud(roomId: String, request: CloudPrepareRequest): RepositoryResult<PlaybackResponse> =
        cloudRequest { remote.prepareCloud(roomId, request) }
    suspend fun cloudReady(roomId: String, request: CloudReadyRequest): RepositoryResult<PlaybackResponse> =
        cloudRequest { remote.cloudReady(roomId, request) }
    suspend fun commitCloud(roomId: String, request: CloudCommitRequest): RepositoryResult<PlaybackResponse> =
        cloudRequest { remote.commitCloud(roomId, request) }
    suspend fun cloudLyrics(roomId: String, track: CloudTrack): RepositoryResult<CloudLyrics> =
        cloudRequest { remote.cloudLyrics(roomId, track) }
    suspend fun searchCloud(roomId: String, source: String, keyword: String, page: Int = 1): RepositoryResult<CloudSearchResponse> =
        cloudRequest { remote.searchCloud(roomId, CloudSearchRequest(source, keyword, page)) }

    suspend fun resolveCloud(roomId: String, track: CloudTrack): RepositoryResult<CloudResolution> =
        cloudRequest { remote.resolveCloud(roomId, track) }

    private suspend fun <T> cloudRequest(block: suspend () -> T): RepositoryResult<T> =
        try { RepositoryResult.Success(block()) } catch (error: HttpException) {
            if (error.code() == 404) RepositoryResult.Failure("房间或云音乐接口不存在，请检查房间状态和服务器版本", "CLOUD_NOT_FOUND")
            else mapFailure(error)
        } catch (error: Throwable) { mapFailure(error) }
    suspend fun createRoom(name: String, userId: String, displayName: String): RepositoryResult<CreateRoomResponse> =
        request { remote.createRoom(CreateRoomRequest(name, userId, displayName, protocolVersion = 2)) }

    suspend fun joinRoom(roomCode: String, userId: String, displayName: String): RepositoryResult<JoinRoomResponse> =
        request {
            remote.joinRoom(JoinRoomRequest(userId, displayName, roomCode = roomCode, protocolVersion = 2))
    }

    suspend fun joinRoomByLink(
        roomId: String,
        token: String,
        userId: String,
        displayName: String,
    ): RepositoryResult<JoinRoomResponse> = request {
        remote.joinRoomById(roomId, JoinRoomRequest(userId, displayName, joinToken = token, protocolVersion = 2))
    }

    suspend fun getRoom(roomId: String): RepositoryResult<RoomSnapshot> = request { remote.getRoom(roomId) }

    suspend fun leaveRoom(roomId: String, userId: String): RepositoryResult<Unit> =
        request { remote.leaveRoom(roomId, userId) }

    suspend fun getPlaylist(roomId: String): RepositoryResult<PlaylistResponse> =
        request { remote.getPlaylist(roomId) }

    suspend fun getServerTime(): RepositoryResult<ServerTimeResponse> = request { remote.getServerTime() }

    suspend fun play(roomId: String, command: TrackPlaybackCommand): RepositoryResult<PlaybackResponse> =
        request { remote.play(roomId, command) }

    suspend fun pause(roomId: String, command: TrackPlaybackCommand): RepositoryResult<PlaybackResponse> =
        request { remote.pause(roomId, command) }

    suspend fun seek(roomId: String, command: TrackPlaybackCommand): RepositoryResult<PlaybackResponse> =
        request { remote.seek(roomId, command) }

    suspend fun next(roomId: String, command: NextPlaybackCommand): RepositoryResult<PlaybackResponse> =
        request { remote.next(roomId, command) }

    suspend fun changeMemberRole(
        roomId: String,
        userId: String,
        role: String,
    ): RepositoryResult<ChangeRoleResponse> =
        request { remote.changeMemberRole(roomId, userId, role) }

    suspend fun deleteTrack(roomId: String, trackId: String): RepositoryResult<Unit> =
        request { remote.deleteTrack(roomId, trackId) }

    suspend fun reorderPlaylist(
        roomId: String,
        orderedTrackIds: List<String>,
    ): RepositoryResult<ReorderResponse> =
        request { remote.reorderPlaylist(roomId, orderedTrackIds) }

    private suspend fun <T> request(block: suspend () -> T): RepositoryResult<T> =
        runCatching { block() }.fold(
            onSuccess = { RepositoryResult.Success(it) },
            onFailure = ::mapFailure,
        )

    private fun mapFailure(error: Throwable): RepositoryResult.Failure = when (error) {
        is kotlinx.coroutines.CancellationException -> throw error
        is IOException -> RepositoryResult.Failure("无法连接服务器，请检查地址和网络")
        is HttpException -> {
            val parsed = error.response()?.errorBody()?.string()?.let {
                runCatching { json.decodeFromString<ErrorResponse>(it) }.getOrNull()
            }
            RepositoryResult.Failure(
                message = parsed?.error?.message ?: "服务器请求失败 (${error.code()})",
                code = parsed?.error?.code,
            )
        }
        else -> RepositoryResult.Failure(error.message ?: "发生未知错误")
    }
}
