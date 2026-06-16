package com.synclisten.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityManagerTest {
    @Test
    fun createsUserIdOnceAndKeepsSavedSettings() = runBlocking {
        val store = FakeSettingsStore()
        val manager = IdentityManager(store) { "generated-user" }

        val first = manager.ensureIdentity()
        store.update(userId = null, displayName = "Alice", serverUrl = "http://192.168.1.2:3000")
        val second = manager.ensureIdentity()

        assertEquals("generated-user", first.userId)
        assertEquals("generated-user", second.userId)
        assertEquals("Alice", second.displayName)
        assertEquals("http://192.168.1.2:3000", second.serverUrl)
    }
}

private class FakeSettingsStore : SettingsStore {
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
