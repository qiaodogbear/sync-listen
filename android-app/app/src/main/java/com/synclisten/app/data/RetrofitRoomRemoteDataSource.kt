package com.synclisten.app.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Singleton
class RetrofitApiFactory @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    fun create(serverUrl: String): SyncListenApi = Retrofit.Builder()
        .baseUrl("${normalizeServerUrl(serverUrl)}/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(SyncListenApi::class.java)
}

@Singleton
class RetrofitRoomRemoteDataSource @Inject constructor(
    private val settingsStore: SettingsStore,
    private val apiFactory: RetrofitApiFactory,
) : RoomRemoteDataSource {
    private suspend fun api(): SyncListenApi = apiFactory.create(settingsStore.settings.first().serverUrl)

    override suspend fun createRoom(request: CreateRoomRequest): CreateRoomResponse =
        api().createRoom(request)

    override suspend fun joinRoom(request: JoinRoomRequest): JoinRoomResponse =
        api().joinRoomByCode(request)

    override suspend fun joinRoomById(roomId: String, request: JoinRoomRequest): JoinRoomResponse =
        api().joinRoom(roomId, request)

    override suspend fun getRoom(roomId: String): RoomSnapshot = api().getRoom(roomId)

    override suspend fun leaveRoom(roomId: String, userId: String) {
        api().leaveRoom(roomId, userId)
    }

    override suspend fun getPlaylist(roomId: String): PlaylistResponse = api().getPlaylist(roomId)

    override suspend fun getServerTime(): ServerTimeResponse = api().getServerTime()

    override suspend fun play(roomId: String, command: TrackPlaybackCommand): PlaybackResponse =
        api().play(roomId, command)

    override suspend fun pause(roomId: String, command: TrackPlaybackCommand): PlaybackResponse =
        api().pause(roomId, command)

    override suspend fun seek(roomId: String, command: TrackPlaybackCommand): PlaybackResponse =
        api().seek(roomId, command)

    override suspend fun next(roomId: String, command: NextPlaybackCommand): PlaybackResponse =
        api().next(roomId, command)

    override suspend fun changeMemberRole(roomId: String, userId: String, role: String): ChangeRoleResponse =
        api().changeMemberRole(roomId, userId, ChangeRoleRequest(role))

    override suspend fun deleteTrack(roomId: String, trackId: String) {
        api().deleteTrack(roomId, trackId)
    }

    override suspend fun reorderPlaylist(roomId: String, orderedTrackIds: List<String>): ReorderResponse =
        api().reorderPlaylist(roomId, ReorderRequest(orderedTrackIds))
}
