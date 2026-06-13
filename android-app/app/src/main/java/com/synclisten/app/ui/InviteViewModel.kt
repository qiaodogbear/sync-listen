package com.synclisten.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synclisten.app.data.SettingsStore
import com.synclisten.app.invite.JoinLink
import com.synclisten.app.invite.JoinLinkCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class InviteViewModel @Inject constructor(
    homeController: HomeController,
    settingsStore: SettingsStore,
) : ViewModel() {
    private val mutableLink = MutableStateFlow<String?>(null)
    val link: StateFlow<String?> = mutableLink

    init {
        viewModelScope.launch {
            val session = homeController.state.value as? HomeState.InRoom ?: return@launch
            val token = session.joinToken ?: return@launch
            val server = settingsStore.settings.first().serverUrl
            mutableLink.value = JoinLinkCodec.encode(JoinLink(session.room.roomId, token, server))
        }
    }
}
