package com.synclisten.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.synclisten.cloud.CloudCandidate
import com.synclisten.cloud.CloudDownloader
import com.synclisten.cloud.CloudResolution
import com.synclisten.cloud.CloudTrack
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudDownloadDeviceTest {
    @Test fun downloadsAndVerifiesSyntheticAudio() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "cloud-device-test-${java.util.UUID.randomUUID()}.mp3")
        val bytes = "ID3synthetic audio bytes".toByteArray()
        ServerSocket(0).use { server ->
            server.soTimeout = 10_000
            val worker = Thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (true) { if (input.readLine().isNullOrEmpty()) break }
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(bytes)
                    output.flush()
                }
            }.apply { isDaemon = true; start() }
            try {
                val track = CloudTrack("qq", "song", "Test", listOf("Artist"), mediaId = "media")
                val resolution = CloudResolution(track, listOf(CloudCandidate("http://127.0.0.1:${server.localPort}/audio", 128, "mp3")), "", "", "test")
                val result = CloudDownloader().download(resolution, file, { resolution }, { true })
                assertEquals(bytes.size.toLong(), result.size)
                assertEquals(bytes.toList(), file.readBytes().toList())
                assertEquals(64, result.sha256.length)
                worker.join(10_000)
            } finally { file.delete() }
        }
    }
}
