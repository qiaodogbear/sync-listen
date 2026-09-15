package com.synclisten.app.nearby

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NearbyHttpTest {
    @Test fun requiresSessionNonceAndBoundsIncomingBody() = testApplication {
        val gate = NearbyInvitationGate({ 1_000 }) { false }
        application { nearbyModule("test-nonce", { NearbyProfile("a".repeat(36), "听友") }, gate) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/nearby/profile").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/nearby/invite").status)
        assertEquals(HttpStatusCode.OK, client.get("/nearby/profile") { header(NEARBY_HEADER, "test-nonce") }.status)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/nearby/invite") {
            header(NEARBY_HEADER, "test-nonce"); setBody("x".repeat(4097))
        }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/nearby/invite") {
            header(NEARBY_HEADER, "test-nonce"); setBody("{}")
        }.status)
        assertEquals(HttpStatusCode.Accepted, client.post("/nearby/invite") {
            header(NEARBY_HEADER, "test-nonce")
            setBody(Json.encodeToString(NearbyInvitation("a".repeat(36), "听友",
                NearbyRoom("ABC123", "http://192.168.1.2:38571", "测试"))))
        }.status)
        assertNotNull(gate.pending.value)
    }
}
