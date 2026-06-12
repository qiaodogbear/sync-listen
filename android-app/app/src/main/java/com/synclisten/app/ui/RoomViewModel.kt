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
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
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
) : ViewModel() {
    val connection: StateFlow<RoomConnectionState> = socketClient.connection
    val snapshot: StateFlow<RoomSnapshot?> = socketClient.snapshot
    val downloads = downloadQueueManager.state
    val player = playerController.state
    val clock = serverClock.state
    private val mutableCacheSummary = MutableStateFlow(CacheSummary())
    val cacheSummary: StateFlow<CacheSummary> = mutableCacheSummary

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
}
