package com.synclisten.app.host.server

import com.synclisten.app.data.CreateRoomResponse
import com.synclisten.app.domain.model.ErrorResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HostFileRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun uploadsDeduplicatesAndDownloadsReadyAudio() = testApplication {
        val root = createTempDir(prefix = "host-files-")
        application { hostServerModule(store(), HostRoomHub(), storage = HostStorage(root)) }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        val room = createRoom(client)
        val bytes = "fake mp3 bytes".encodeToByteArray()
        val hash = sha256(bytes)

        val first = client.post("/api/rooms/${room.room.roomId}/tracks") {
            setBody(uploadBody(bytes, hash))
        }.body<HostUploadResponse>()
        val second = client.post("/api/rooms/${room.room.roomId}/tracks") {
            setBody(uploadBody(bytes, hash))
        }.body<HostUploadResponse>()

        assertEquals(false, first.deduplicated)
        assertEquals(true, second.deduplicated)
        assertEquals(1, File(root, "audio").listFiles()?.size)
        assertArrayEquals(bytes, client.get("/api/tracks/${first.track.trackId}/download").bodyAsBytes())
    }

    @Test
    fun rejectsHashMismatchAndDeletesTemporaryUpload() = testApplication {
        val root = createTempDir(prefix = "host-files-")
        application { hostServerModule(store(), HostRoomHub(), storage = HostStorage(root)) }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        val room = createRoom(client)

        val response = client.post("/api/rooms/${room.room.roomId}/tracks") {
            setBody(uploadBody("bad".encodeToByteArray(), "0".repeat(64)))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("HASH_MISMATCH", json.decodeFromString<ErrorResponse>(response.bodyAsText()).error.code)
        assertEquals(0, File(root, "uploads").listFiles()?.size)
    }

    private suspend fun createRoom(client: io.ktor.client.HttpClient) = client.post("/api/rooms") {
        contentType(ContentType.Application.Json)
        setBody("""{"name":"Friday","userId":"host","displayName":"Alice"}""")
    }.body<CreateRoomResponse>()

    private fun uploadBody(bytes: ByteArray, hash: String) = MultiPartFormDataContent(formData {
        append("title", "Song")
        append("artist", "")
        append("durationMs", "1000")
        append("fileHash", hash)
        append("uploaderId", "host")
        append("uploaderName", "Alice")
        append("file", bytes, Headers.build {
            append(HttpHeaders.ContentType, "audio/mpeg")
            append(HttpHeaders.ContentDisposition, ContentDisposition.File.withParameter("filename", "song.mp3").toString())
        })
    })

    private fun store(): HostRoomStore {
        val ids = AtomicInteger()
        return HostRoomStore(idFactory = { "id-${ids.incrementAndGet()}" }, roomCodeFactory = { "ABC123" })
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
