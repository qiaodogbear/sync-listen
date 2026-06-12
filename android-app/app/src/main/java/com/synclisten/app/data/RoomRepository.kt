package com.synclisten.app.data

import com.synclisten.app.domain.model.ErrorResponse
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import retrofit2.HttpException

interface RoomRemoteDataSource {
    suspend fun createRoom(request: CreateRoomRequest): CreateRoomResponse = unsupported()
    suspend fun joinRoom(roomId: String, request: JoinRoomRequest): JoinRoomResponse = unsupported()
    suspend fun getRoom(roomId: String): RoomSnapshot = unsupported()
    suspend fun getPlaylist(roomId: String): PlaylistResponse = unsupported()
    suspend fun getServerTime(): ServerTimeResponse = unsupported()
    suspend fun play(roomId: String, command: TrackPlaybackCommand): PlaybackResponse = unsupported()
    suspend fun pause(roomId: String, command: TrackPlaybackCommand): PlaybackResponse = unsupported()
    suspend fun seek(roomId: String, command: TrackPlaybackCommand): PlaybackResponse = unsupported()
    suspend fun next(roomId: String, command: NextPlaybackCommand): PlaybackResponse = unsupported()

    private fun unsupported(): Nothing = error("Remote operation is not implemented")
}

sealed interface RepositoryResult<out T> {
    data class Success<T>(val value: T) : RepositoryResult<T>
    data class Failure(val message: String, val code: String? = null) : RepositoryResult<Nothing>
}

@Singleton
class RoomRepository @Inject constructor(
    private val remote: RoomRemoteDataSource,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun createRoom(name: String, userId: String, displayName: String): RepositoryResult<CreateRoomResponse> =
        request { remote.createRoom(CreateRoomRequest(name, userId, displayName)) }

    suspend fun joinRoom(
        roomId: String,
        roomCode: String,
        userId: String,
        displayName: String,
    ): RepositoryResult<JoinRoomResponse> = request {
        remote.joinRoom(roomId, JoinRoomRequest(userId, displayName, roomCode = roomCode))
    }

    suspend fun getRoom(roomId: String): RepositoryResult<RoomSnapshot> = request { remote.getRoom(roomId) }

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

    private suspend fun <T> request(block: suspend () -> T): RepositoryResult<T> =
        runCatching { block() }.fold(
            onSuccess = { RepositoryResult.Success(it) },
            onFailure = ::mapFailure,
        )

    private fun mapFailure(error: Throwable): RepositoryResult.Failure = when (error) {
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
