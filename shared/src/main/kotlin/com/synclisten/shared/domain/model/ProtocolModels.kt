package com.synclisten.shared.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class MemberRole {
    HOST,
    ADMIN,
    MEMBER,
}

@Serializable
enum class RoomStatus {
    ACTIVE,
    CLOSED,
}

@Serializable
enum class TrackStatus {
    UPLOADING,
    READY,
    FAILED,
}

@Serializable
enum class TransferStatus {
    QUEUED,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    FAILED,
}

@Serializable
data class Member(
    val userId: String,
    val displayName: String,
    val role: MemberRole,
    val connected: Boolean,
    val joinedAt: Long,
)

@Serializable
data class Room(
    val roomId: String,
    val roomCode: String,
    val name: String,
    val hostUserId: String,
    val status: RoomStatus,
    val createdAt: Long,
)

@Serializable
data class Track(
    val trackId: String,
    val roomId: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val fileName: String,
    val fileSize: Long,
    val fileHash: String,
    val uploaderId: String,
    val uploaderName: String,
    val orderIndex: Int,
    val status: TrackStatus,
    val createdAt: Long,
)

@Serializable
data class PlaybackState(
    val trackId: String?,
    val positionMs: Long,
    val isPlaying: Boolean,
    val serverTimeMs: Long,
    val executeAtServerTimeMs: Long? = null,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: JsonElement? = null,
)

@Serializable
data class ErrorResponse(
    val error: ApiError,
)

@Serializable
enum class WebSocketEventType {
    ROOM_JOINED,
    MEMBER_JOINED,
    MEMBER_LEFT,
    TRACK_ADDED,
    TRACK_UPLOAD_PROGRESS,
    TRACK_READY,
    TRACK_REMOVED,
    PLAYLIST_UPDATED,
    PLAY,
    PAUSE,
    SEEK,
    NEXT,
    SYNC,
    DOWNLOAD_HINT,
    ERROR,
}

@Serializable
data class WebSocketEnvelope(
    val type: WebSocketEventType,
    val payload: JsonObject,
    val serverTimeMs: Long,
)
