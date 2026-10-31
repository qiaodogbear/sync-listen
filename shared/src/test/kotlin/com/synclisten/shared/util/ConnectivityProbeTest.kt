package com.synclisten.shared.util

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityProbeTest {
    @Test fun distinguishesReachableServerInvalidAddressAndServerError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(503))
            server.start()
            assertEquals(ProbeResult.Reachable, ConnectivityProbe.probe(server.url("/").toString()))
            assertEquals("/health", server.takeRequest().path)
            val failed = ConnectivityProbe.probe(server.url("/").toString())
            assertEquals(UnreachableReason.SERVER_ERROR, (failed as ProbeResult.Unreachable).reason)
            assertEquals(UnreachableReason.INVALID_ADDRESS, (ConnectivityProbe.probe("invalid") as ProbeResult.Unreachable).reason)
            assertTrue(failed.detail.contains("503"))
        }
    }
}
