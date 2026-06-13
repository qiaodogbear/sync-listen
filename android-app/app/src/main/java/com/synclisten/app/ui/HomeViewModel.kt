package com.synclisten.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import com.synclisten.app.invite.JoinLinkInbox

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val controller: HomeController,
    private val joinLinkInbox: JoinLinkInbox,
) : ViewModel() {
    val state = controller.state
    val pendingJoinLink = joinLinkInbox.pending

    fun createRoom(name: String, displayName: String) {
        viewModelScope.launch { controller.createRoom(name, displayName) }
    }

    fun joinRoom(roomCode: String, displayName: String) {
        viewModelScope.launch { controller.joinRoom(roomCode, displayName) }
    }

    fun reset() = controller.reset()

    fun confirmJoinLink(displayName: String) {
        val link = pendingJoinLink.value ?: return
        joinLinkInbox.clear()
        viewModelScope.launch { controller.joinRoom(link, displayName) }
    }

    fun dismissJoinLink() = joinLinkInbox.clear()

    fun acceptJoinLink(rawLink: String?) = joinLinkInbox.accept(rawLink)
}
