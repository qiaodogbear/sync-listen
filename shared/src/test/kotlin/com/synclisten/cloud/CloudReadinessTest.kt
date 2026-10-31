package com.synclisten.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudReadinessTest {
    @Test fun waitsForOnlineMembersAndOnlyCommitsAfterReadyOrForce() {
        val barrier = CloudReadiness()
        val first = barrier.begin("song", listOf("host", "member"))
        barrier.report(first.generation, "song", "host", true)
        val failed = barrier.report(first.generation, "song", "member", false)
        assertEquals(listOf("member"), failed.waiting)
        assertEquals(listOf("member"), failed.failed)
        assertThrows(IllegalStateException::class.java) { barrier.commit(first.generation, false) }
        assertTrue(barrier.commit(first.generation, true).committed)
        assertThrows(IllegalStateException::class.java) { barrier.report(first.generation, "song", "member", true) }
    }

    @Test fun staleReportsAndDisconnectedMembersAreHandled() {
        val barrier = CloudReadiness()
        val old = barrier.begin("old", listOf("host", "member"))
        val current = barrier.begin("new", listOf("host", "member"))
        assertThrows(IllegalStateException::class.java) { barrier.report(old.generation, "old", "member", true) }
        barrier.report(current.generation, "new", "host", true)
        barrier.disconnect("member")
        barrier.join("newcomer")
        assertThrows(IllegalStateException::class.java) { barrier.commit(current.generation, false) }
        barrier.report(current.generation, "new", "newcomer", true)
        assertTrue(barrier.commit(current.generation, false).committed)
        assertFalse(barrier.snapshot()!!.waiting.isNotEmpty())
    }
}
