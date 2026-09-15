package com.synclisten.shared.data

import com.synclisten.shared.domain.model.Member
import com.synclisten.shared.domain.model.PlaybackState
import com.synclisten.shared.domain.model.Room
import com.synclisten.shared.domain.model.Track
import kotlinx.serialization.Serializable

@Serializable
data class CreateRoomRequest(val name: String, val userId: String, val displayName: String)

@Serializable
data class CreateRoomResponse(val room: Room, val member: Member, val joinToken: String)

@Serializable
data class JoinRoomRequest(
    val userId: String,
    val displayName: String,
    val joinToken: String? = null,
    val roomCode: String? = null,
)

@Serializable
data class JoinRoomResponse(val room: Room, val member: Member, val joinToken: String)

@Serializable
data class RoomSnapshot(
    val room: Room,
    val members: List<Member>,
    val playlist: List<Track>,
    val playbackState: PlaybackState,
)

@Serializable
data class PlaylistResponse(val playlist: List<Track>)

@Serializable
data class ServerTimeResponse(val serverTimeMs: Long)

@Serializable
data class TrackPlaybackCommand(val userId: String, val trackId: String, val positionMs: Long)

@Serializable
data class NextPlaybackCommand(val userId: String, val positionMs: Long = 0)

@Serializable
data class PlaybackResponse(val state: PlaybackState)

@Serializable
data class ChangeRoleRequest(val role: String)

@Serializable
data class ChangeRoleResponse(val userId: String, val role: String)

@Serializable
data class ReorderRequest(val orderedTrackIds: List<String>)

@Serializable
data class ReorderResponse(val ok: Boolean)

@Serializable
data class UploadTrackResponse(val track: com.synclisten.shared.domain.model.Track, val deduplicated: Boolean = false)
