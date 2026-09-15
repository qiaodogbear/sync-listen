package com.synclisten.shared.data

import com.synclisten.protocol.DeviceCredential
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class SettingsStoreTest {
    @Test fun concurrentIdentityInitializationKeepsOneCredential() = runBlocking {
        val store = MemorySettingsStore()
        val results = (1..32).map { async(Dispatchers.Default) { IdentityManager(store).ensureIdentity() } }.awaitAll()
        assertEquals(1, results.map { it.userId }.toSet().size)
        assertEquals(1, results.map { it.deviceSecret }.toSet().size)
        assertTrue(results.first().deviceSecret.isNotBlank())
    }
    @Test fun persistsIndependentUpdatesAndLoadsIdentity() = runBlocking {
        val file = Files.createTempDirectory("sync-settings-").resolve("settings.json").toFile()
        try {
            val store = FileSettingsStore(file)
            val original = IdentityManager(store).ensureIdentity()
            coroutineScope {
                launch { store.update(displayName = "Alice") }
                launch { store.update(serverUrl = "http://192.168.1.2:3000") }
            }
            val restored = FileSettingsStore(file).settings.first()
            assertEquals(original.deviceSecret, restored.deviceSecret)
            assertEquals("Alice", restored.displayName)
            assertEquals("http://192.168.1.2:3000", restored.serverUrl)
        } finally { file.delete(); file.parentFile.delete() }
    }
    @Test fun credentialsAreScopedToOriginAndUser() {
        val a = DeviceCredential.forServer("secret", "http://a:80", "user")
        val b = DeviceCredential.forServer("secret", "http://b:80", "user")
        assertNotEquals(a, b)
        assertNotEquals(a, DeviceCredential.forServer("secret", "http://a:80", "other"))
        assertTrue(DeviceCredential.matches(DeviceCredential.hash(a), a))
        assertFalse(DeviceCredential.matches(DeviceCredential.hash(a), b))
        assertFalse(DeviceCredential.matches(null, a))
    }
}
