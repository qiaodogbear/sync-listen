package com.synclisten.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synclisten.app.data.RoomConnectionState
import com.synclisten.app.cache.CacheCleanup
import com.synclisten.app.cache.CacheSummary
import com.synclisten.app.data.RoomRepository
import com.synclisten.app.data.RoomSnapshot
import com.synclisten.app.data.RoomWebSocketClient
import com.synclisten.app.data.SettingsStore
import com.synclisten.app.transfer.DownloadQueueManager
import com.synclisten.app.playback.PlayerController
import com.synclisten.app.playback.ServerClock
import com.synclisten.app.playback.PlaybackSyncManager
import com.synclisten.app.data.RoomEvent
import com.synclisten.app.data.RepositoryResult
import com.synclisten.app.data.TrackPlaybackCommand
import com.synclisten.app.data.NextPlaybackCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@HiltViewModel
class RoomViewModel @Inject constructor(
    private val homeController: HomeController,
    private val settingsStore: SettingsStore,
    private val socketClient: RoomWebSocketClient,
    private val repository: RoomRepository,
    private val downloadQueueManager: DownloadQueueManager,
    private val cacheCleanup: CacheCleanup,
    private val playerController: PlayerController,
    private val serverClock: ServerClock,
    private val playbackSyncManager: PlaybackSyncManager,
) : ViewModel() {
    val connection: StateFlow<RoomConnectionState> = socketClient.connection
    val snapshot: StateFlow<RoomSnapshot?> = socketClient.snapshot
    val downloads = downloadQueueManager.state
    val player = playerController.state
    val clock = serverClock.state
    val sync = playbackSyncManager.state
    private val mutableCacheSummary = MutableStateFlow(CacheSummary())
    val cacheSummary: StateFlow<CacheSummary> = mutableCacheSummary
    private val mutableControlError = MutableStateFlow<String?>(null)
    val controlError: StateFlow<String?> = mutableControlError

    init {
        viewModelScope.launch {
            val session = homeController.state.value as? HomeState.InRoom ?: return@launch
            val settings = settingsStore.settings.first()
            socketClient.connect(
                serverUrl = settings.serverUrl,
                roomId = session.room.roomId,
                userId = session.member.userId,
                token = session.joinToken ?: return@launch,
            )
            socketClient.snapshot.filterNotNull().collect {
                downloadQueueManager.sync(it, settings.serverUrl)
                refreshCache()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                serverClock.refresh()
                delay(30_000)
            }
        }
        viewModelScope.launch {
            socketClient.connection.collect {
                playbackSyncManager.setConnected(it is RoomConnectionState.Connected)
                if (it is RoomConnectionState.Connected) downloadQueueManager.resume()
            }
        }
        viewModelScope.launch {
            socketClient.lastEvent.filterNotNull().collectLatest { event ->
                when (event) {
                    is RoomEvent.Snapshot -> playbackSyncManager.apply(event.value.playbackState)
                    is RoomEvent.Playback -> playbackSyncManager.apply(event.value)
                    else -> Unit
                }
            }
        }
    }

    fun refreshCache() {
        viewModelScope.launch { mutableCacheSummary.value = cacheCleanup.summary() }
    }

    fun clearCache() {
        viewModelScope.launch {
            cacheCleanup.clearAllExcept(player.value.trackId)
            mutableCacheSummary.value = cacheCleanup.summary()
        }
    }

    fun prepareLocal(trackId: String) {
        viewModelScope.launch { playerController.prepare(trackId) }
    }

    fun playLocal() = playerController.play()

    fun pauseLocal() = playerController.pause()

    fun seekLocal(positionMs: Long) = playerController.seekTo(positionMs)

    fun hostPlay(trackId: String) = control { session ->
        repository.play(
            session.room.roomId,
            TrackPlaybackCommand(
                session.member.userId,
                trackId,
                if (player.value.trackId == trackId) player.value.positionMs else 0,
            ),
        )
    }

    fun hostPause() = controlCurrent { session, trackId ->
        repository.pause(
            session.room.roomId,
            TrackPlaybackCommand(session.member.userId, trackId, player.value.positionMs),
        )
    }

    fun hostSeek(positionMs: Long) = controlCurrent { session, trackId ->
        repository.seek(
            session.room.roomId,
            TrackPlaybackCommand(session.member.userId, trackId, positionMs.coerceAtLeast(0)),
        )
    }

    fun hostNext() = control { session ->
        repository.next(session.room.roomId, NextPlaybackCommand(session.member.userId))
    }

    fun leave(onComplete: () -> Unit) {
        viewModelScope.launch {
            val session = homeController.state.value as? HomeState.InRoom
            if (session != null) {
                repository.leaveRoom(session.room.roomId, session.member.userId)
            }
            socketClient.disconnect()
            homeController.reset()
            onComplete()
        }
    }

    override fun onCleared() {
        socketClient.disconnect()
        playerController.release()
    }

    private fun controlCurrent(
        request: suspend (HomeState.InRoom, String) -> RepositoryResult<*>,
    ) {
        val trackId = player.value.trackId ?: snapshot.value?.playbackState?.trackId ?: return
        control { request(it, trackId) }
    }

    private fun control(request: suspend (HomeState.InRoom) -> RepositoryResult<*>) {
        viewModelScope.launch {
            val session = homeController.state.value as? HomeState.InRoom ?: return@launch
            mutableControlError.value = when (val result = request(session)) {
                is RepositoryResult.Success -> null
                is RepositoryResult.Failure -> result.message
            }
        }
    }
}
