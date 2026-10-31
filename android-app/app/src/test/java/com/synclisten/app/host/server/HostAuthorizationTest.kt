package com.synclisten.app.host.server

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HostAuthorizationTest {
    @Test fun cloudTrackRequiresCompatibleMembersAndDoesNotBypassReadiness() = testApplication {
        application { hostServerModule(HostRoomStore(), HostRoomHub()) }
        val created = client.post("/api/rooms") {
            header("X-User-Id", "host"); header("Authorization", "Bearer " + "a".repeat(64)); contentType(ContentType.Application.Json)
            setBody("""{"name":"Test","userId":"host","displayName":"Alice","protocolVersion":2}""")
        }
        val room = Json.parseToJsonElement(created.bodyAsText()).jsonObject["room"]!!.jsonObject
        val roomId = room["roomId"]!!.jsonPrimitive.content
        val added = client.post("/api/rooms/$roomId/cloud/tracks") {
            header("X-User-Id", "host"); header("Authorization", "Bearer " + "a".repeat(64)); contentType(ContentType.Application.Json)
            setBody("""{"track":{"source":"qq","id":"song","title":"Song","artists":["Artist"],"mediaId":"media"},"format":"mp3","bitrate":128}""")
        }
        assertEquals(HttpStatusCode.Created, added.status)
        val trackId = Json.parseToJsonElement(added.bodyAsText()).jsonObject["track"]!!.jsonObject["trackId"]!!.jsonPrimitive.content
        val play = client.post("/api/rooms/$roomId/playback/play") {
            header("X-User-Id", "host"); header("Authorization", "Bearer " + "a".repeat(64)); contentType(ContentType.Application.Json)
            setBody("""{"userId":"host","trackId":"$trackId","positionMs":0}""")
        }
        assertEquals(HttpStatusCode.Conflict, play.status)
        assertTrue(play.bodyAsText().contains("CLOUD_SYNC_UNAVAILABLE"))
        val legacy = client.post("/api/rooms/join") {
            header("X-User-Id", "old"); header("Authorization", "Bearer " + "b".repeat(64)); contentType(ContentType.Application.Json)
            setBody("""{"userId":"old","displayName":"Old","roomCode":"${room["roomCode"]!!.jsonPrimitive.content}"}""")
        }
        assertEquals(HttpStatusCode.Conflict, legacy.status)
        assertTrue(legacy.bodyAsText().contains("CLIENT_UPGRADE_REQUIRED"))
    }
    @Test fun rejectsMissingCredentialsAndImpersonation() = testApplication {
        application { hostServerModule(HostRoomStore(), HostRoomHub()) }
        suspend fun create(actor: String, bodyActor: String = actor) = client.post("/api/rooms") {
            header("X-User-Id", actor)
            header("Authorization", "Bearer " + "a".repeat(64))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Test","userId":"$bodyActor","displayName":"Alice"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/rooms").status)
        assertEquals(HttpStatusCode.Forbidden, create("other", "host").status)
        val response = create("host")
        assertEquals(HttpStatusCode.Created, response.status)
        val roomId = Json.parseToJsonElement(response.bodyAsText()).jsonObject["room"]!!.jsonObject["roomId"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/rooms/$roomId").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/rooms/$roomId") {
            header("X-User-Id", "host")
            header("Authorization", "Bearer " + "b".repeat(64))
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/rooms/$roomId") {
            header("X-User-Id", "host")
            header("Authorization", "Bearer " + "a".repeat(64))
        }.status)
        val cloudPath = "/api/rooms/$roomId/cloud/search"
        val payload = """{"source":"invalid","keyword":"test"}"""
        assertEquals(HttpStatusCode.Unauthorized, client.post(cloudPath) { contentType(ContentType.Application.Json); setBody(payload) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post(cloudPath) { header("X-User-Id", "host"); header("Authorization", "Bearer " + "b".repeat(64)); contentType(ContentType.Application.Json); setBody(payload) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post(cloudPath) { header("X-User-Id", "host"); header("Authorization", "Bearer " + "a".repeat(64)); contentType(ContentType.Application.Json); setBody(payload) }.status)
    }

    @Test fun preservesAdminOnRejoinAndRejectsInvalidOrder() = kotlinx.coroutines.runBlocking {
        val store = HostRoomStore(roomCodeFactory = { "ABC123" })
        val room = store.createRoom(com.synclisten.app.data.CreateRoomRequest("Test", "host", "Alice"), "host-key")
        val id = room.room.roomId
        val request = com.synclisten.app.data.JoinRoomRequest("member", "Bob", roomCode = "ABC123")
        store.joinByCode(request, "member-key")
        store.changeRole(id, "member", com.synclisten.app.domain.model.MemberRole.ADMIN, "host")
        assertEquals(com.synclisten.app.domain.model.MemberRole.ADMIN, store.joinByCode(request, "member-key").member.role)
        try {
            store.joinByCode(request, "stolen")
            fail("An invite cannot replace the existing device credential")
        } catch (_: HostServerError) { }
        try {
            store.reorderPlaylist(id, listOf("missing"), "host")
            fail("An invalid permutation must be rejected")
        } catch (_: HostServerError) { }
        assertTrue(store.playlist(id).isEmpty())
    }
}
