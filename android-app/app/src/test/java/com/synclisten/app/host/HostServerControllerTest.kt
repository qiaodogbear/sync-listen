package com.synclisten.app.host

import java.net.InetAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostServerControllerTest {
    @Test
    fun startsUsingAdvertisedAddressAndStopsRuntime() = runBlocking {
        val launcher = FakeHostRuntimeLauncher()
        val controller = DefaultHostServerController({ InetAddress.getByName("192.168.43.1") }, launcher)

        val started = controller.start()
        controller.stop()

        assertEquals("http://192.168.43.1:38571", launcher.startedWith)
        assertTrue(started is HostServerState.Running)
        assertEquals(HostServerState.Stopped, controller.state.value)
        assertEquals(1, launcher.stopCalls)
    }

    @Test
    fun failsWithoutReachableAddressAndDoesNotLaunch() = runBlocking {
        val launcher = FakeHostRuntimeLauncher()
        val controller = DefaultHostServerController({ null }, launcher)

        val state = controller.start()

        assertTrue(state is HostServerState.Error)
        assertEquals(null, launcher.startedWith)
    }

    @Test
    fun duplicateStartReturnsExistingRunningState() = runBlocking {
        val launcher = FakeHostRuntimeLauncher()
        val controller = DefaultHostServerController({ InetAddress.getByName("192.168.1.2") }, launcher)

        controller.start()
        controller.start()

        assertEquals(1, launcher.startCalls)
    }
}

private class FakeHostRuntimeLauncher : HostRuntimeLauncher {
    override val state = MutableStateFlow<HostServerState>(HostServerState.Stopped)
    var startedWith: String? = null
    var startCalls = 0
    var stopCalls = 0

    override suspend fun start(advertisedUrl: String): HostServerState {
        startCalls += 1
        startedWith = advertisedUrl
        return HostServerState.Running(HOST_LOCAL_URL, advertisedUrl, HOST_SERVER_PORT).also { state.value = it }
    }

    override suspend fun stop() {
        stopCalls += 1
        state.value = HostServerState.Stopped
    }
}
