package com.synclisten.desktop.audio

import com.synclisten.shared.playback.PlayerEngineEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class DesktopPlayerEngineTest {
    private class Output : AudioOutput {
        val bytes = AtomicLong()
        @Volatile var running = false
        override val playedFrames get() = bytes.get() / 2
        override fun start() { running = true }
        override fun stop() { running = false }
        override fun write(bytes: ByteArray, length: Int) { this.bytes.addAndGet(length.toLong()); Thread.sleep(5) }
        override fun drain() = Unit
        override fun close() { running = false }
    }
    @Test fun httpStreamPreparesBeforeTheWholeFileArrives() {
        val format = AudioFormat(8000f, 16, 1, true, false)
        val wav = ByteArrayOutputStream().also { bytes ->
            AudioInputStream(ByteArrayInputStream(ByteArray(320_000)), format, 160_000).use {
                AudioSystem.write(it, AudioFileFormat.Type.WAVE, bytes)
            }
        }.toByteArray()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(wav)).throttleBody(16_384, 1, TimeUnit.SECONDS))
            server.start()
            val output = Output()
            val engine = DesktopPlayerEngine { output }
            val events = LinkedBlockingQueue<PlayerEngineEvent>()
            engine.setEventListener { events.offer(it) }
            try {
                engine.load(server.url("/audio?token=secret").toString())
                assertTrue("A buffered stream should prepare without receiving the entire file", events.poll(3, TimeUnit.SECONDS) is PlayerEngineEvent.Ready)
                assertEquals(0, output.bytes.get())
                engine.play()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (output.bytes.get() == 0L && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue("Playback should begin while the response is still streaming", output.bytes.get() > 0)
                assertTrue(output.bytes.get() < 320_000)
            } finally { engine.release() }
        }
    }

    @Test fun httpErrorsDoNotExposeSignedUrls() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.start()
            val engine = DesktopPlayerEngine { Output() }
            val events = LinkedBlockingQueue<PlayerEngineEvent>()
            engine.setEventListener { events.offer(it) }
            try {
                engine.load(server.url("/audio?token=secret").toString())
                val event = events.poll(5, TimeUnit.SECONDS)
                assertTrue(event is PlayerEngineEvent.Error)
                assertFalse((event as PlayerEngineEvent.Error).message.contains("secret"))
            } finally { engine.release() }
        }
    }
    @Test fun loadIsSilentPlayReachesEndAndReleaseIsReusable() {
        val file = Files.createTempFile("sync-audio-", ".wav").toFile()
        val format = AudioFormat(8000f, 16, 1, true, false)
        AudioInputStream(ByteArrayInputStream(ByteArray(16000)), format, 8000).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, file)
        }
        val outputs = mutableListOf<Output>()
        val engine = DesktopPlayerEngine { Output().also { outputs.add(it) } }
        val events = LinkedBlockingQueue<PlayerEngineEvent>()
        engine.setEventListener { events.offer(it) }
        try {
            repeat(2) {
                engine.load(file.absolutePath)
                assertTrue(events.poll(5, TimeUnit.SECONDS) is PlayerEngineEvent.Ready)
                Thread.sleep(80)
                assertEquals(0, outputs.last().bytes.get())
                assertFalse(outputs.last().running)
                engine.play()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var ended = false
                while (System.nanoTime() < deadline) {
                    val event = events.poll(100, TimeUnit.MILLISECONDS)
                    assertFalse(event is PlayerEngineEvent.Error)
                    if (event is PlayerEngineEvent.Ended) { ended = true; break }
                }
                assertTrue("Audio must reach EOF without null queue entries", ended)
                assertEquals(16000, outputs.last().bytes.get())
                engine.release()
                events.clear()
            }
        } finally { engine.release(); file.delete() }
    }
    @Test fun seekWhilePausedDoesNotAutoplay() {
        val file = Files.createTempFile("sync-seek-", ".wav").toFile()
        val format = AudioFormat(8000f, 16, 1, true, false)
        AudioInputStream(ByteArrayInputStream(ByteArray(32000)), format, 16000).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, file)
        }
        val output = Output()
        val engine = DesktopPlayerEngine { output }
        val events = LinkedBlockingQueue<PlayerEngineEvent>()
        engine.setEventListener { events.offer(it) }
        try {
            engine.load(file.absolutePath)
            assertTrue(events.poll(5, TimeUnit.SECONDS) is PlayerEngineEvent.Ready)
            engine.seekTo(1000)
            assertTrue(events.poll(5, TimeUnit.SECONDS) is PlayerEngineEvent.Ready)
            Thread.sleep(80)
            assertEquals(0, output.bytes.get())
            assertFalse(output.running)
        } finally { engine.release(); file.delete() }
    }
}
