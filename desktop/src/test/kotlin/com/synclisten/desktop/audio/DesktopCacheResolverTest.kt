package com.synclisten.desktop.audio

import com.synclisten.shared.domain.model.Track
import com.synclisten.shared.domain.model.TrackStatus
import com.synclisten.protocol.DeviceCredential
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DesktopCacheResolverTest {
    private fun track(hash: String, size: Long) = Track("track", "room", "Test", null, 1000,
        "test.wav", size, hash, "host", "Alice", 0, TrackStatus.READY, 0)
    @Test fun verifiesContentBeforePlaybackAndReusesCache() = runBlocking {
        val dir = Files.createTempDirectory("sync-cache-").toFile()
        val server = MockWebServer()
        val client = OkHttpClient()
        server.start()
        try {
            val cache = DesktopCacheResolver(dir)
            val track = track(DeviceCredential.hash("audio"), 5)
            assertNull(cache.resolvePath(track.trackId))
            server.enqueue(MockResponse().setBody("audio"))
            cache.ensureDownloaded(track, server.url("/").toString(), client)
            assertNotNull(cache.resolvePath(track.trackId))
            cache.ensureDownloaded(track, server.url("/").toString(), client)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); client.connectionPool.evictAll(); dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }
    @Test fun rejectsWrongHashAndClearsPartialFile() = runBlocking {
        val dir = Files.createTempDirectory("sync-cache-").toFile()
        val server = MockWebServer()
        val client = OkHttpClient()
        server.start()
        try {
            val cache = DesktopCacheResolver(dir)
            server.enqueue(MockResponse().setBody("wrong"))
            try {
                cache.ensureDownloaded(track(DeviceCredential.hash("audio"), 5), server.url("/").toString(), client)
                fail("Corrupt audio must not be exposed to the player")
            } catch (_: IllegalStateException) { }
            assertNull(cache.resolvePath("track"))
            assertEquals(0, dir.listFiles()!!.size)
        } finally { server.shutdown(); client.connectionPool.evictAll(); dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }
}
