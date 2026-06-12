package com.synclisten.app.ui

import com.synclisten.app.data.AppSettings
import com.synclisten.app.data.CreateRoomRequest
import com.synclisten.app.data.CreateRoomResponse
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val controller = HomeController(RoomRepository(remote), settings, IdentityManager(settings) { "user-1" })

        val first = async { controller.createRoom("Friday", "Alice") }
        entered.await()
        val second = async { controller.createRoom("Friday", "Alice") }
        release.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, calls)
        assertTrue(controller.state.value is HomeState.InRoom)
    }
}

private class FakeHomeSettingsStore : SettingsStore {
    private val state = MutableStateFlow(AppSettings())
    override val settings: Flow<AppSettings> = state

    override suspend fun update(userId: String?, displayName: String?, serverUrl: String?) {
        state.value = state.value.copy(
            userId = userId ?: state.value.userId,
            displayName = displayName ?: state.value.displayName,
            serverUrl = serverUrl ?: state.value.serverUrl,
        )
    }
}

private fun createResponse() = CreateRoomResponse(
    room = Room("room-1", "ABC123", "Friday", "user-1", RoomStatus.ACTIVE, 1),
    member = Member("user-1", "Alice", MemberRole.HOST, false, 1),
    joinToken = "join-token",
)
