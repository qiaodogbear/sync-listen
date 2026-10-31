package com.synclisten.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.synclisten.app.playback.Media3PlayerEngine
import com.synclisten.app.playback.PlayerEngineEvent
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudStreamDeviceTest {
    @Test fun media3PlaysBeforeTheFullResponseArrives() {
        ActivityScenario.launch(MainActivity::class.java).use { verifyPartialStream() }
    }

    private fun verifyPartialStream() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val playing = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        val pcmSize = 1_600_000
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(pcmSize + 36); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(pcmSize)
        }.array()
        ServerSocket(0).use { server ->
            server.soTimeout = 20_000
            val worker = Thread {
                runCatching {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        val out = socket.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${pcmSize + 44}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(header)
                        out.write(ByteArray(96_000))
                        out.flush()
                        releaseServer.await(20, TimeUnit.SECONDS)
                    }
                }
            }.apply { isDaemon = true; start() }
            val engine = Media3PlayerEngine(instrumentation.targetContext)
            engine.setEventListener { event ->
                when (event) {
                    is PlayerEngineEvent.Ready -> ready.countDown()
                    is PlayerEngineEvent.Position -> if (event.positionMs > 0) playing.countDown()
                    is PlayerEngineEvent.Error -> { failure.set(event.message); ready.countDown(); playing.countDown() }
                    else -> Unit
                }
            }
            try {
                instrumentation.runOnMainSync { engine.load("http://127.0.0.1:${server.localPort}/audio") }
                assertTrue("Media3 did not prepare a partial response", ready.await(15, TimeUnit.SECONDS))
                assertNull(failure.get())
                instrumentation.runOnMainSync { engine.play() }
                assertTrue("Playback position did not advance", playing.await(10, TimeUnit.SECONDS))
                assertNull(failure.get())
            } finally {
                instrumentation.runOnMainSync { engine.release() }
                releaseServer.countDown()
                worker.join(5_000)
            }
        }
    }
}
