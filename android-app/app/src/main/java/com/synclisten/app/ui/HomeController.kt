package com.synclisten.app.ui

import com.synclisten.app.data.CreateRoomResponse
import com.synclisten.app.data.IdentityManager
import com.synclisten.app.data.JoinRoomResponse
import com.synclisten.app.data.RepositoryResult
import com.synclisten.app.data.RoomRepository
import com.synclisten.app.data.SettingsStore
import com.synclisten.app.domain.model.Member
import com.synclisten.app.domain.model.Room
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

sealed interface HomeState {
    data object Idle : HomeState
    data object Loading : HomeState
    data class InRoom(val room: Room, val member: Member, val joinToken: String?) : HomeState
    data class Error(val message: String) : HomeState
}

@Singleton
class HomeController @Inject constructor(
    private val repository: RoomRepository,
    private val settingsStore: SettingsStore,
    private val identityManager: IdentityManager,
) {
    private val submitMutex = Mutex()
    private val mutableState = MutableStateFlow<HomeState>(HomeState.Idle)
    val state: StateFlow<HomeState> = mutableState

    suspend fun createRoom(name: String, displayName: String) {
        submit {
            val identity = saveAndReadIdentity(displayName)
            when (val result = repository.createRoom(name.trim(), identity.userId, identity.displayName)) {
                is RepositoryResult.Success -> result.value.toHomeState()
                is RepositoryResult.Failure -> HomeState.Error(result.message)
            }
        }
    }

    suspend fun joinRoom(roomCode: String, displayName: String) {
        submit {
            val identity = saveAndReadIdentity(displayName)
            when (val result = repository.joinRoom(roomCode.trim(), identity.userId, identity.displayName)) {
                is RepositoryResult.Success -> result.value.toHomeState()
                is RepositoryResult.Failure -> HomeState.Error(result.message)
            }
        }
    }

    fun reset() {
        mutableState.value = HomeState.Idle
    }

    private suspend fun submit(block: suspend () -> HomeState) {
        if (!submitMutex.tryLock()) return
        mutableState.value = HomeState.Loading
        try {
            mutableState.value = block()
        } finally {
            submitMutex.unlock()
        }
    }

    private suspend fun saveAndReadIdentity(displayName: String) =
        settingsStore.update(displayName = displayName.trim()).let { identityManager.ensureIdentity() }

    private fun CreateRoomResponse.toHomeState() = HomeState.InRoom(room, member, joinToken)
    private fun JoinRoomResponse.toHomeState() = HomeState.InRoom(room, member, joinToken)
}
