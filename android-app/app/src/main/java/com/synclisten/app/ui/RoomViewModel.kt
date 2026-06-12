package com.synclisten.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synclisten.app.data.RoomConnectionState
import com.synclisten.app.data.RoomRepository
import com.synclisten.app.data.RoomSnapshot
import com.synclisten.app.data.RoomWebSocketClient
import com.synclisten.app.data.SettingsStore
import com.synclisten.app.transfer.DownloadQueueManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@HiltViewModel
class RoomViewModel @Inject constructor(
    private val homeController: HomeController,
    private val settingsStore: SettingsStore,
    private val socketClient: RoomWebSocketClient,
    private val repository: RoomRepository,
    private val downloadQueueManager: DownloadQueueManager,
) : ViewModel() {
    val connection: StateFlow<RoomConnectionState> = socketClient.connection
    val snapshot: StateFlow<RoomSnapshot?> = socketClient.snapshot
    val downloads = downloadQueueManager.state

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
            }
        }
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
    }
}
