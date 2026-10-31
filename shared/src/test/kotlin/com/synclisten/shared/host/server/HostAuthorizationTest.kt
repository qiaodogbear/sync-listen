package com.synclisten.shared.host.server

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import com.synclisten.cloud.CloudMusicClient
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

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
    @Test fun cloudSearchUsesTheMemberAuthenticatedContract() = testApplication {
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val body = """{"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[{"mid":"song","title":"Test","singer":[{"name":"Artist"}],"file":{"media_mid":"media","size_128mp3":123}}]}}}}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        application { hostServerModule(HostRoomStore(), HostRoomHub(), cloudMusic = CloudMusicClient(transport)) }
        val created = client.post("/api/rooms") { header("X-User-Id", "host"); header("Authorization", "Bearer " + "a".repeat(64)); contentType(ContentType.Application.Json); setBody("""{"name":"Test","userId":"host","displayName":"Alice"}""") }
        val roomId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["room"]!!.jsonObject["roomId"]!!.jsonPrimitive.content
        val response = client.post("/api/rooms/$roomId/cloud/search") { header("X-User-Id", "host"); header("Authorization", "Bearer " + "a".repeat(64)); contentType(ContentType.Application.Json); setBody("""{"source":"qq","keyword":"test"}""") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val track = Json.parseToJsonElement(response.bodyAsText()).jsonObject["tracks"]!!.jsonArray.single().jsonObject
        assertEquals("song", track["id"]!!.jsonPrimitive.content)
        assertEquals("media", track["mediaId"]!!.jsonPrimitive.content)
        assertEquals("mp3-128", track["availableQualities"]!!.jsonArray.single().jsonPrimitive.content)
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
        val room = store.createRoom(com.synclisten.shared.data.CreateRoomRequest("Test", "host", "Alice"), "host-key")
        val id = room.room.roomId
        val request = com.synclisten.shared.data.JoinRoomRequest("member", "Bob", roomCode = "ABC123")
        store.joinByCode(request, "member-key")
        store.changeRole(id, "member", com.synclisten.shared.domain.model.MemberRole.ADMIN, "host")
        assertEquals(com.synclisten.shared.domain.model.MemberRole.ADMIN, store.joinByCode(request, "member-key").member.role)
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
