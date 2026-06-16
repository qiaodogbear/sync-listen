package com.synclisten.app.ui

import com.synclisten.app.data.CreateRoomResponse
import com.synclisten.app.data.IdentityManager
import com.synclisten.app.data.JoinRoomResponse
import com.synclisten.app.data.RepositoryResult
import com.synclisten.app.data.RoomRepository
import com.synclisten.app.data.SettingsStore
import com.synclisten.app.domain.model.Member
import com.synclisten.app.domain.model.MemberRole
import com.synclisten.app.domain.model.Room
import com.synclisten.app.domain.model.RoomStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import com.synclisten.app.invite.JoinLink
import com.synclisten.app.host.HostServerController
import com.synclisten.app.host.HostServerState
import com.synclisten.app.host.HostRecoveryManager
import com.synclisten.app.host.server.HostRecoverySnapshot
import kotlinx.coroutines.flow.first

sealed interface HomeState {
    data object Idle : HomeState
    data object Loading : HomeState
    data class InRoom(
        val room: Room,
        val member: Member,
        val joinToken: String?,
        val inviteServerUrl: String,
        val hostedLocally: Boolean = false,
    ) : HomeState
    data class Error(val message: String) : HomeState
}

@Singleton
class HomeController @Inject constructor(
    private val repository: RoomRepository,
    private val settingsStore: SettingsStore,
    private val identityManager: IdentityManager,
    private val hostServerController: HostServerController,
    private val recoveryManager: HostRecoveryManager? = null,
) {
    private val submitMutex = Mutex()
    private val mutableState = MutableStateFlow<HomeState>(HomeState.Idle)
    val state: StateFlow<HomeState> = mutableState
    val hostServerState = hostServerController.state

    private val mutableRecovery = MutableStateFlow<HostRecoverySnapshot?>(null)
    val recoverableRoom: StateFlow<HostRecoverySnapshot?> = mutableRecovery

    suspend fun checkRecovery() {
        mutableRecovery.value = recoveryManager?.checkRecoverable()
    }

    suspend fun recoverHostedRoom() {
        submit {
            val snapshot = mutableRecovery.value ?: return@submit HomeState.Error("没有可恢复的房间")
            val previousServer = settingsStore.settings.first().serverUrl
            when (val host = recoveryManager?.launchRecovery() ?: return@submit HomeState.Error("恢复功能不可用")) {
                is HostServerState.Running -> {
                    settingsStore.update(serverUrl = host.localUrl)
                    val identity = identityManager.ensureIdentity()
                    mutableRecovery.value = null
                    HomeState.InRoom(
                        room = Room(
                            roomId = snapshot.roomId,
                            roomCode = snapshot.roomCode,
                            name = snapshot.roomName,
                            hostUserId = identity.userId,
                            status = RoomStatus.ACTIVE,
                            createdAt = 0,
                        ),
                        member = Member(
                            userId = identity.userId,
                            displayName = identity.displayName,
                            role = MemberRole.HOST,
                            connected = false,
                            joinedAt = 0,
                        ),
                        joinToken = null,
                        inviteServerUrl = host.advertisedUrl,
                        hostedLocally = true,
                    )
                }
                is HostServerState.Error -> {
                    settingsStore.update(serverUrl = previousServer)
                    HomeState.Error(host.message)
                }
                else -> HomeState.Error("恢复服务尚未就绪")
            }
        }
    }

    suspend fun dismissRecovery() {
        recoveryManager?.dismissRecovery()
        mutableRecovery.value = null
    }

    suspend fun createRoom(name: String, displayName: String) {
        submit {
            val identity = saveAndReadIdentity(displayName)
            val serverUrl = settingsStore.settings.first().serverUrl
            when (val result = repository.createRoom(name.trim(), identity.userId, identity.displayName)) {
                is RepositoryResult.Success -> result.value.toHomeState(serverUrl)
                is RepositoryResult.Failure -> HomeState.Error(result.message)
            }
        }
    }

    suspend fun createHostedRoom(name: String, displayName: String) {
        submit {
            val previousServer = settingsStore.settings.first().serverUrl
            when (val host = hostServerController.start()) {
                is HostServerState.Running -> {
                    settingsStore.update(serverUrl = host.localUrl)
                    val identity = saveAndReadIdentity(displayName)
                    when (val result = repository.createRoom(name.trim(), identity.userId, identity.displayName)) {
                        is RepositoryResult.Success -> result.value.toHomeState(host.advertisedUrl, hostedLocally = true)
                        is RepositoryResult.Failure -> {
                            settingsStore.update(serverUrl = previousServer)
                            hostServerController.stop()
                            HomeState.Error(result.message)
                        }
                    }
                }
                is HostServerState.Error -> HomeState.Error(host.message)
                else -> HomeState.Error("手机托管服务尚未就绪")
            }
        }
    }

    suspend fun joinRoom(roomCode: String, displayName: String) {
        submit {
            val identity = saveAndReadIdentity(displayName)
            val serverUrl = settingsStore.settings.first().serverUrl
            when (val result = repository.joinRoom(roomCode.trim(), identity.userId, identity.displayName)) {
                is RepositoryResult.Success -> result.value.toHomeState(serverUrl)
                is RepositoryResult.Failure -> HomeState.Error(result.message)
            }
        }
    }

    suspend fun joinRoom(serverUrl: String, roomCode: String, displayName: String) {
        settingsStore.update(serverUrl = serverUrl)
        joinRoom(roomCode, displayName)
    }

    suspend fun joinRoom(link: JoinLink, displayName: String) {
        submit {
            settingsStore.update(serverUrl = link.serverUrl)
            val identity = saveAndReadIdentity(displayName)
            when (
                val result = repository.joinRoomByLink(
                    link.roomId,
                    link.token,
                    identity.userId,
                    identity.displayName,
                )
            ) {
                is RepositoryResult.Success -> result.value.toHomeState(link.serverUrl)
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
        settingsStore.update(displayName = displayName.trim(), recentNickname = displayName.trim())
            .let { identityManager.ensureIdentity() }

    suspend fun stopHosting() = hostServerController.stop()

    private fun CreateRoomResponse.toHomeState(serverUrl: String, hostedLocally: Boolean = false) =
        HomeState.InRoom(room, member, joinToken, serverUrl, hostedLocally)

    private fun JoinRoomResponse.toHomeState(serverUrl: String) =
        HomeState.InRoom(room, member, joinToken, serverUrl)
}
