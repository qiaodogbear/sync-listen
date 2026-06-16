package com.synclisten.app.ui

import com.synclisten.app.data.AppSettings
import com.synclisten.app.data.CreateRoomRequest
import com.synclisten.app.data.CreateRoomResponse
import com.synclisten.app.data.JoinRoomRequest
import com.synclisten.app.data.JoinRoomResponse
import com.synclisten.app.data.IdentityManager
import com.synclisten.app.data.RoomRemoteDataSource
import com.synclisten.app.data.RoomRepository
import com.synclisten.app.data.SettingsStore
import com.synclisten.app.domain.model.Member
import com.synclisten.app.domain.model.MemberRole
import com.synclisten.app.domain.model.Room
import com.synclisten.app.domain.model.RoomStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.synclisten.app.invite.JoinLink
import com.synclisten.app.host.HOST_LOCAL_URL
import com.synclisten.app.host.HostServerController
import com.synclisten.app.host.HostServerState

class HomeControllerTest {
    @Test
    fun ignoresDuplicateCreateWhileRequestIsRunning() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val remote = object : RoomRemoteDataSource {
            override suspend fun createRoom(request: CreateRoomRequest): CreateRoomResponse {
                calls += 1
                entered.complete(Unit)
                release.await()
                return createResponse()
            }
        }
        val settings = FakeHomeSettingsStore()
        val controller = controller(remote, settings)

        val first = async { controller.createRoom("Friday", "Alice") }
        entered.await()
        val second = async { controller.createRoom("Friday", "Alice") }
        release.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, calls)
        assertTrue(controller.state.value is HomeState.InRoom)
    }

    @Test
    fun confirmsJoinLinkUsingTokenAndLinkServer() = runBlocking {
        var captured: Pair<String, JoinRoomRequest>? = null
        val remote = object : RoomRemoteDataSource {
            override suspend fun joinRoomById(roomId: String, request: JoinRoomRequest): JoinRoomResponse {
                captured = roomId to request
                return JoinRoomResponse(createResponse().room, createResponse().member, "join-token")
            }
        }
        val settings = FakeHomeSettingsStore()
        val controller = controller(remote, settings)

        controller.joinRoom(JoinLink("room-1", "invite-token", "http://server:3000"), "Bob")

        assertEquals("http://server:3000", settings.settings.first().serverUrl)
        assertEquals("room-1", captured?.first)
        assertEquals("invite-token", captured?.second?.joinToken)
        assertTrue(controller.state.value is HomeState.InRoom)
    }

    @Test
    fun hostedCreateUsesLoopbackAndAdvertisesReachableAddress() = runBlocking {
        val settings = FakeHomeSettingsStore()
        var serverDuringCreate: String? = null
        val remote = object : RoomRemoteDataSource {
            override suspend fun createRoom(request: CreateRoomRequest): CreateRoomResponse {
                serverDuringCreate = settings.settings.first().serverUrl
                return createResponse()
            }
        }
        val host = FakeHostServerController(
            HostServerState.Running(HOST_LOCAL_URL, "http://192.168.43.1:38571", 38571),
        )
        val controller = controller(remote, settings, host)

        controller.createHostedRoom("Friday", "Alice")

        val state = controller.state.value as HomeState.InRoom
        assertEquals(HOST_LOCAL_URL, serverDuringCreate)
        assertEquals("http://192.168.43.1:38571", state.inviteServerUrl)
        assertTrue(state.hostedLocally)
    }

    @Test
    fun hostedCreateRollsBackServerAndStopsWhenCreateFails() = runBlocking {
        val settings = FakeHomeSettingsStore()
        val host = FakeHostServerController(
            HostServerState.Running(HOST_LOCAL_URL, "http://192.168.43.1:38571", 38571),
        )
        val remote = object : RoomRemoteDataSource {
            override suspend fun createRoom(request: CreateRoomRequest): CreateRoomResponse {
                throw java.io.IOException("failed")
            }
        }
        val controller = controller(remote, settings, host)

        controller.createHostedRoom("Friday", "Alice")

        assertEquals(AppSettings().serverUrl, settings.settings.first().serverUrl)
        assertEquals(1, host.stopCalls)
        assertTrue(controller.state.value is HomeState.Error)
    }

    private fun controller(
        remote: RoomRemoteDataSource,
        settings: FakeHomeSettingsStore,
        host: HostServerController = FakeHostServerController(HostServerState.Stopped),
    ) = HomeController(RoomRepository(remote), settings, IdentityManager(settings) { "user-1" }, host)
}

private class FakeHomeSettingsStore : SettingsStore {
    private val state = MutableStateFlow(AppSettings())
    override val settings: Flow<AppSettings> = state

    override suspend fun update(userId: String?, displayName: String?, serverUrl: String?, recentNickname: String?) {
        state.value = state.value.copy(
            userId = userId ?: state.value.userId,
            displayName = displayName ?: state.value.displayName,
            serverUrl = serverUrl ?: state.value.serverUrl,
            recentNickname = recentNickname ?: state.value.recentNickname,
        )
    }
}

private fun createResponse() = CreateRoomResponse(
    room = Room("room-1", "ABC123", "Friday", "user-1", RoomStatus.ACTIVE, 1),
    member = Member("user-1", "Alice", MemberRole.HOST, false, 1),
    joinToken = "join-token",
)

private class FakeHostServerController(initial: HostServerState) : HostServerController {
    private val mutableState = MutableStateFlow(initial)
    override val state = mutableState
    var stopCalls = 0

    override suspend fun start(): HostServerState = mutableState.value

    override suspend fun stop() {
        stopCalls += 1
        mutableState.value = HostServerState.Stopped
    }
}
