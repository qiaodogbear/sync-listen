package com.synclisten.app.host.persistence

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.synclisten.app.data.*
import com.synclisten.app.host.HostRecoveryManager
import com.synclisten.app.host.HostServerController
import com.synclisten.app.host.HostServerState
import com.synclisten.app.host.server.HostRoomStore
import com.synclisten.app.ui.HomeController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeRecoveryStateTest {
    @Test
    fun activeAndExplicitlyEndedRoomCannotLeaveStaleRecoveryCard() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, HostPersistenceDatabase::class.java).build()
        try {
            val store = HostRoomStore(dao = database.hostDao())
            val created = store.createRoom(CreateRoomRequest("Recovery state", "host", "Alice"))
            val settings = object : SettingsStore {
                override val settings = MutableStateFlow(AppSettings())
                override suspend fun update(userId: String?, displayName: String?, serverUrl: String?,
                    recentNickname: String?, avatarEmoji: String?, deviceSecret: String?) {
                    settings.value = settings.value.copy(
                        userId = userId ?: settings.value.userId,
                        displayName = displayName ?: settings.value.displayName,
                        serverUrl = serverUrl ?: settings.value.serverUrl,
                        recentNickname = recentNickname ?: settings.value.recentNickname,
                        avatarEmoji = avatarEmoji ?: settings.value.avatarEmoji,
                        deviceSecret = deviceSecret ?: settings.value.deviceSecret,
                    )
                }
            }
            val host = object : HostServerController {
                override val state = MutableStateFlow<HostServerState>(HostServerState.Stopped)
                override suspend fun start(): HostServerState {
                    state.value = HostServerState.Running("http://127.0.0.1:38571", "http://192.168.1.2:38571", 38571)
                    return state.value
                }
                override suspend fun stop() {
                    store.closeAndCleanup()
                    state.value = HostServerState.Stopped
                }
            }
            val remote = object : RoomRemoteDataSource {
                override suspend fun createRoom(request: CreateRoomRequest) = created
            }
            val controller = HomeController(RoomRepository(remote), settings,
                IdentityManager(settings, idFactory = { "host" }), host,
                HostRecoveryManager(database.hostDao(), context))
            controller.checkRecovery()
            assertNotNull(controller.recoverableRoom.value)
            controller.createHostedRoom("Recovery state", "Alice")
            controller.checkRecovery()
            assertNull(controller.recoverableRoom.value)
            controller.stopHosting()
            controller.reset()
            assertNull(controller.recoverableRoom.value)
            controller.checkRecovery()
            assertNull(controller.recoverableRoom.value)
            assertNull(database.hostDao().getRecoveryMarker())
        } finally {
            database.close()
        }
    }
}
