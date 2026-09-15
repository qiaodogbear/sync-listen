package com.synclisten.shared.host.server

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HostAuthorizationTest {
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
