package com.synclisten.cloud

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CloudDownloadTest {
    private val track = CloudTrack("qq", "song", "Test", listOf("Artist"), mediaId = "media")

    @Test fun refreshesExpiredAddressAndChecksSavedBytes() = runBlocking {
        val server = MockWebServer()
        val dir = Files.createTempDirectory("cloud-download-test-").toFile()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/mpeg").setBody("ID3test"))
            val first = CloudResolution(track, listOf(CloudCandidate(server.url("/expired").toString(), 128, "mp3")), "", "", "qq")
            val fresh = first.copy(candidates = listOf(CloudCandidate(server.url("/fresh").toString(), 128, "mp3")))
            val states = mutableListOf<CloudDownloadProgress>()
            var confirmations = 0
            val result = CloudDownloader().download(first, dir.resolve("test.mp3"), { fresh }, { confirmations++; true }, { states.add(it) })
            assertEquals(7, result.size)
            assertEquals("ID3test", result.file.readText())
            assertEquals(64, result.sha256.length)
            assertEquals(2, confirmations)
            assertEquals(7, states.last().received)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun refusesChangedTrackAndLeavesNoPartialFile() = runBlocking {
        val server = MockWebServer()
        val dir = Files.createTempDirectory("cloud-download-test-").toFile()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(403))
            val first = CloudResolution(track, listOf(CloudCandidate(server.url("/expired").toString(), 128, "mp3")), "", "", "qq")
            try {
                CloudDownloader().download(first, dir.resolve("test.mp3"), { first.copy(track = track.copy(id = "other")) }, { true })
                fail("Different song must not be accepted")
            } catch (_: CloudDownloadException) { }
            assertEquals(0, dir.listFiles()!!.size)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun doesNotOverwriteExistingDestination() = runBlocking {
        val dir = Files.createTempDirectory("cloud-download-test-").toFile()
        try {
            val file = dir.resolve("existing.mp3").apply { writeText("keep") }
            val result = CloudResolution(track, emptyList(), "", "", "qq")
            try {
                CloudDownloader().download(result, file, { result }, { true })
                fail("Existing file must not be overwritten")
            } catch (_: CloudDownloadException) { }
            assertEquals("keep", file.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun doesNotDownloadWithoutConfirmation() = runBlocking {
        val server = MockWebServer()
        val dir = Files.createTempDirectory("cloud-download-test-").toFile()
        server.start()
        try {
            val resolution = CloudResolution(track, listOf(CloudCandidate(server.url("/audio").toString(), 128, "mp3")), "Another song", "", "gateway")
            try {
                CloudDownloader().download(resolution, dir.resolve("test.mp3"), { resolution }, { false })
                fail("Unconfirmed resolution must not be downloaded")
            } catch (_: CloudDownloadException) { }
            assertEquals(0, server.requestCount)
            assertEquals(0, dir.listFiles()!!.size)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun rejectsHtmlAndInvalidAudioBeforeTryingNextCandidate() = runBlocking {
        val server = MockWebServer()
        val dir = Files.createTempDirectory("cloud-download-test-").toFile()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>error</html>"))
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody("not audio"))
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody("ID3valid"))
            val result = CloudResolution(track, listOf("html", "invalid", "valid").map { CloudCandidate(server.url("/$it").toString(), 128, "mp3") }, "", "", "qq")
            val saved = CloudDownloader().download(result, dir.resolve("test.mp3"), { fail("Refresh not needed"); result }, { true })
            assertEquals("ID3valid", saved.file.readText())
            assertEquals(3, server.requestCount)
            assertEquals(1, dir.listFiles()!!.size)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }
}
