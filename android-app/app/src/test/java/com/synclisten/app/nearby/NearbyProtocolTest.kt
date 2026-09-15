package com.synclisten.app.nearby

import org.junit.Assert.*
import org.junit.Test

class NearbyProtocolTest {
    private val room = NearbyRoom("ABC123", "http://192.168.1.5:38571", "房间")
    private fun invite(id: String = "a".repeat(36)) = NearbyInvitation(id, "听友", room)

    @Test fun acceptsOnlyLiteralPrivateIpv4Endpoints() {
        assertTrue(NearbyValidation.server(room.serverUrl))
        listOf("http://127.0.0.1:80", "http://192.168.1.5:80/path", "https://example.com:443",
            "http://user@192.168.1.5:80", "http://192.168.1.999:80", "http://192.168.1.5:80?x=1",
            "http://169.254.169.254:80/latest/meta-data").forEach { assertFalse(it, NearbyValidation.server(it)) }
    }
    @Test fun boundsProfileNamesAndVersions() {
        assertTrue(NearbyValidation.profile(NearbyProfile("a".repeat(36), "听友", room)))
        assertFalse(NearbyValidation.profile(NearbyProfile("a".repeat(36), "x".repeat(41))))
        assertFalse(NearbyValidation.profile(NearbyProfile("a".repeat(36), "x", version = 2)))
        assertFalse(NearbyValidation.name("x\ny"))
    }
    @Test fun invitationNeedsExplicitTakeAndExpiresWithoutServerClock() {
        var now = 1_000L
        val gate = NearbyInvitationGate({ now }) { false }
        assertEquals(202, gate.receive(invite()))
        assertNotNull(gate.pending.value)
        now += 60_000
        assertNull(gate.take())
    }
    @Test fun replayAndBusyCannotReplacePendingInvitation() {
        val gate = NearbyInvitationGate({ 1_000 }) { false }
        assertEquals(202, gate.receive(invite()))
        assertEquals(409, gate.receive(invite("b".repeat(36))))
        assertEquals(invite(), gate.take())
        assertEquals(409, gate.receive(invite()))
        assertEquals(409, NearbyInvitationGate({ 1_000 }) { true }.receive(invite()))
    }
    @Test fun malformedAndRepeatedRequestsAreRateLimited() {
        val gate = NearbyInvitationGate({ 1_000 }) { false }
        repeat(5) { assertEquals(400, gate.receive(invite("invalid"))) }
        assertEquals(429, gate.receive(invite()))
    }
}
