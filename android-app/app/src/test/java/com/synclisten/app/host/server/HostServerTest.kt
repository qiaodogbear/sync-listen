package com.synclisten.app.host.server

import com.synclisten.app.data.CreateRoomResponse
import com.synclisten.app.data.JoinRoomResponse
import com.synclisten.app.data.RoomSnapshot
import com.synclisten.app.domain.model.ErrorResponse
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.readText
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostServerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun servesHealthTimeAndRoomLifecycle() = testApplication {
        application { hostServerModule(store(), HostRoomHub()) }
        val client = createClient { defaultRequest { header("X-User-Id", "host"); header("Authorization", "Bearer " + "a".repeat(64)) }; install(ContentNegotiation) { json(json) } }

        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/time").status)
        val created = client.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Friday","userId":"host","displayName":"Alice"}""")
        }.body<CreateRoomResponse>()
        val memberClient = createClient {
            defaultRequest { header("X-User-Id", "member"); header("Authorization", "Bearer " + "a".repeat(64)) }
            install(ContentNegotiation) { json(json) }
        }
        val joinResponse = memberClient.post("/api/rooms/join") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"member","displayName":"Bob","roomCode":"${created.room.roomCode}"}""")
        }
        assertEquals(joinResponse.bodyAsText(), HttpStatusCode.OK, joinResponse.status)
        val joined = joinResponse.body<JoinRoomResponse>()

        assertEquals("member", joined.member.userId)
        assertEquals(2, client.get("/api/rooms/${created.room.roomId}").body<RoomSnapshot>().members.size)
        assertEquals(
            HttpStatusCode.NoContent,
            memberClient.delete("/api/rooms/${created.room.roomId}/members/member").status,
        )
    }

    @Test
    fun mapsHostErrorsToExistingErrorEnvelope() = testApplication {
        application { hostServerModule(store(), HostRoomHub()) }
        val response = client.get("/api/rooms/missing") { header("X-User-Id", "host"); header("Authorization", "Bearer " + "a".repeat(64)) }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("ROOM_NOT_FOUND", json.decodeFromString<ErrorResponse>(response.bodyAsText()).error.code)
    }

    @Test
    fun websocketAuthenticatesAndSendsAuthoritativeSnapshot() = testApplication {
        val store = store()
        application { hostServerModule(store, HostRoomHub()) }
        val api = createClient { defaultRequest { header("X-User-Id", "host"); header("Authorization", "Bearer " + "a".repeat(64)) }; install(ContentNegotiation) { json(json) } }
        val created = api.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Friday","userId":"host","displayName":"Alice"}""")
        }.body<CreateRoomResponse>()
        val socket = createClient { defaultRequest { header("X-User-Id", "host"); header("Authorization", "Bearer " + "a".repeat(64)) }; install(WebSockets) }

        socket.webSocket(
            "/ws/rooms/${created.room.roomId}?token=${created.joinToken}&userId=host",
        ) {
            val envelope = (incoming.receive() as Frame.Text).readText()
            assertTrue(envelope.contains("\"type\":\"ROOM_JOINED\""))
            assertTrue(envelope.contains("\"roomId\":\"${created.room.roomId}\""))
            val joined = (incoming.receive() as Frame.Text).readText()
            assertTrue(joined.contains("\"type\":\"MEMBER_JOINED\""))
            assertTrue(joined.contains("\"member\":{\"userId\":\"host\""))
        }
    }

    @Test
    fun closingHubSendsGoingAwayCloseFrameToConnectedMembers() = testApplication {
        val store = store()
        val hub = HostRoomHub()
        application { hostServerModule(store, hub) }
        val api = createClient { defaultRequest { header("X-User-Id", "host"); header("Authorization", "Bearer " + "a".repeat(64)) }; install(ContentNegotiation) { json(json) } }
        val created = api.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Friday","userId":"host","displayName":"Alice"}""")
        }.body<CreateRoomResponse>()
        val socket = createClient { defaultRequest { header("X-User-Id", "host"); header("Authorization", "Bearer " + "a".repeat(64)) }; install(WebSockets) }

        socket.webSocket(
            "/ws/rooms/${created.room.roomId}?token=${created.joinToken}&userId=host",
        ) {
            incoming.receive()
            incoming.receive()
            hub.closeAll()

            assertEquals(CloseReason.Codes.GOING_AWAY, closeReason.await()?.knownReason)
        }
    }

    private fun store(): HostRoomStore {
        val ids = AtomicInteger()
        return HostRoomStore(
            clock = { 1_000L },
            idFactory = { "id-${ids.incrementAndGet()}" },
            roomCodeFactory = { "ABC123" },
        )
    }
}
