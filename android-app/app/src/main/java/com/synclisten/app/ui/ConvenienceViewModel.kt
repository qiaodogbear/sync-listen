package com.synclisten.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synclisten.app.nearby.LanNearbyManager
import com.synclisten.app.nearby.NearbyRoom
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class ConvenienceViewModel @Inject constructor(
    val nearby: LanNearbyManager,
    val session: RoomSessionController,
    private val home: HomeController,
) : ViewModel() {
    fun join(room: NearbyRoom, nickname: String) {
        if (home.state.value is HomeState.InRoom || home.state.value is HomeState.Loading) return
        viewModelScope.launch { home.joinRoom(room.serverUrl, room.code, nickname.trim()) }
    }
}
