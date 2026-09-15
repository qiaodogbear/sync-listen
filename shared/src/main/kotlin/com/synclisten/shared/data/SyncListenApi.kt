package com.synclisten.shared.data

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path

interface SyncListenApi {
    @POST("api/rooms")
    suspend fun createRoom(@Body request: CreateRoomRequest): CreateRoomResponse

    @POST("api/rooms/{roomId}/join")
    suspend fun joinRoom(@Path("roomId") roomId: String, @Body request: JoinRoomRequest): JoinRoomResponse

    @POST("api/rooms/join")
    suspend fun joinRoomByCode(@Body request: JoinRoomRequest): JoinRoomResponse

    @GET("api/rooms/{roomId}")
    suspend fun getRoom(@Path("roomId") roomId: String): RoomSnapshot

    @DELETE("api/rooms/{roomId}/members/{userId}")
    suspend fun leaveRoom(@Path("roomId") roomId: String, @Path("userId") userId: String)

    @GET("api/rooms/{roomId}/playlist")
    suspend fun getPlaylist(@Path("roomId") roomId: String): PlaylistResponse

    @GET("api/time")
    suspend fun getServerTime(): ServerTimeResponse

    @POST("api/rooms/{roomId}/playback/play")
    suspend fun play(@Path("roomId") roomId: String, @Body command: TrackPlaybackCommand): PlaybackResponse

    @POST("api/rooms/{roomId}/playback/pause")
    suspend fun pause(@Path("roomId") roomId: String, @Body command: TrackPlaybackCommand): PlaybackResponse

    @POST("api/rooms/{roomId}/playback/seek")
    suspend fun seek(@Path("roomId") roomId: String, @Body command: TrackPlaybackCommand): PlaybackResponse

    @POST("api/rooms/{roomId}/playback/next")
    suspend fun next(@Path("roomId") roomId: String, @Body command: NextPlaybackCommand): PlaybackResponse

    @PUT("api/rooms/{roomId}/members/{userId}/role")
    suspend fun changeMemberRole(
        @Path("roomId") roomId: String,
        @Path("userId") userId: String,
        @Body body: ChangeRoleRequest,
    ): ChangeRoleResponse

    @DELETE("api/rooms/{roomId}/tracks/{trackId}")
    suspend fun deleteTrack(
        @Path("roomId") roomId: String,
        @Path("trackId") trackId: String,
    )

    @PUT("api/rooms/{roomId}/playlist/reorder")
    suspend fun reorderPlaylist(
        @Path("roomId") roomId: String,
        @Body body: ReorderRequest,
    ): ReorderResponse

    @Multipart
    @POST("api/rooms/{roomId}/tracks")
    suspend fun uploadTrack(
        @Path("roomId") roomId: String,
        @Part file: MultipartBody.Part,
        @Part("title") title: RequestBody,
        @Part("artist") artist: RequestBody,
        @Part("durationMs") durationMs: RequestBody,
        @Part("fileHash") fileHash: RequestBody,
        @Part("uploaderId") uploaderId: RequestBody,
        @Part("uploaderName") uploaderName: RequestBody,
    ): UploadTrackResponse
}
