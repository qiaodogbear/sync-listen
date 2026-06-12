package com.synclisten.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val controller: HomeController,
) : ViewModel() {
    val state = controller.state

    fun createRoom(name: String, displayName: String) {
        viewModelScope.launch { controller.createRoom(name, displayName) }
    }

    fun joinRoom(roomCode: String, displayName: String) {
        viewModelScope.launch { controller.joinRoom(roomCode, displayName) }
    }

    fun reset() = controller.reset()
}
