package com.synclisten.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import com.synclisten.app.invite.JoinLinkInbox
import com.synclisten.app.nearby.BleRoomDiscovery
import com.synclisten.app.nearby.NfcJoinManager

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val controller: HomeController,
    private val joinLinkInbox: JoinLinkInbox,
    private val bleRoomDiscovery: BleRoomDiscovery,
    private val nfcJoinManager: NfcJoinManager,
) : ViewModel() {
    val state = controller.state
    val pendingJoinLink = joinLinkInbox.pending
    val bleState = bleRoomDiscovery.state
    val nfcState = nfcJoinManager.state
    val hostServerState = controller.hostServerState

    fun createRoom(name: String, displayName: String) {
        viewModelScope.launch { controller.createRoom(name, displayName) }
    }

    fun createHostedRoom(name: String, displayName: String) {
        viewModelScope.launch { controller.createHostedRoom(name, displayName) }
    }

    fun joinRoom(roomCode: String, displayName: String) {
        viewModelScope.launch { controller.joinRoom(roomCode, displayName) }
    }

    fun joinRoom(serverUrl: String, roomCode: String, displayName: String) {
        viewModelScope.launch { controller.joinRoom(serverUrl, roomCode, displayName) }
    }

    fun reset() = controller.reset()

    fun confirmJoinLink(displayName: String) {
        val link = pendingJoinLink.value ?: return
        joinLinkInbox.clear()
        viewModelScope.launch { controller.joinRoom(link, displayName) }
    }

    fun dismissJoinLink() = joinLinkInbox.clear()

    fun acceptJoinLink(rawLink: String?) = joinLinkInbox.accept(rawLink)

    fun requiredBlePermissions() = bleRoomDiscovery.requiredPermissions(advertise = false)

    fun startBleScan() = bleRoomDiscovery.startScanning()

    fun stopBleScan() = bleRoomDiscovery.stopScanning()

    override fun onCleared() {
        bleRoomDiscovery.stopScanning()
    }
}
