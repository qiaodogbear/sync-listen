package com.synclisten.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synclisten.app.invite.JoinLink
import com.synclisten.app.invite.JoinLinkCodec
import com.synclisten.app.nearby.BleRoomDiscovery
import com.synclisten.app.nearby.BleInvite
import com.synclisten.app.playback.canControlPlayback
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class InviteViewModel @Inject constructor(
    homeController: HomeController,
    private val bleRoomDiscovery: BleRoomDiscovery,
) : ViewModel() {
    private val mutableLink = MutableStateFlow<String?>(null)
    val link: StateFlow<String?> = mutableLink
    val bleState = bleRoomDiscovery.state
    private var roomCode: String? = null
    private var serverUrl: String? = null
    private var canAdvertise = false

    init {
        viewModelScope.launch {
            val session = homeController.state.value as? HomeState.InRoom ?: return@launch
            roomCode = session.room.roomCode
            serverUrl = session.inviteServerUrl
            canAdvertise = session.member.role.canControlPlayback()
            val token = session.joinToken ?: return@launch
            mutableLink.value = JoinLinkCodec.encode(JoinLink(session.room.roomId, token, session.inviteServerUrl))
        }
    }

    fun requiredBlePermissions() = bleRoomDiscovery.requiredPermissions(advertise = true)

    fun startBleInvite() {
        if (canAdvertise) {
            val code = roomCode ?: return
            val server = serverUrl ?: return
            bleRoomDiscovery.startAdvertising(BleInvite(code, server))
        }
    }

    override fun onCleared() {
        bleRoomDiscovery.stopAdvertising()
    }
}
