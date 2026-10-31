package com.synclisten.shared

import com.synclisten.sync.DriftSeekDecision
import com.synclisten.sync.DriftSeekPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class DriftSeekPolicyTest {
    @Test fun cloudRequiresSustainedDriftAndLimitsRepeatedSeeks() {
        val policy = DriftSeekPolicy()
        fun observe(time: Long, error: Long) = policy.observe(time, error, 10, isCloud = true, canChangeSpeed = false)
        assertEquals(DriftSeekDecision.WITHIN_TOLERANCE, observe(0, 100))
        assertEquals(DriftSeekDecision.OBSERVING, observe(1_000, 700))
        assertEquals(DriftSeekDecision.WITHIN_TOLERANCE, observe(2_000, 100))
        assertEquals(DriftSeekDecision.OBSERVING, observe(3_000, 700))
        assertEquals(DriftSeekDecision.SEEK, observe(5_000, 700))
        assertEquals(DriftSeekDecision.OBSERVING, observe(5_500, 700))
        assertEquals(DriftSeekDecision.COOLDOWN, observe(10_000, 700))
        assertEquals(DriftSeekDecision.SEEK, observe(35_000, 700))
    }

    @Test fun directionChangesRestartObservationAndExplicitCommandsResetIt() {
        val policy = DriftSeekPolicy()
        fun observe(time: Long, error: Long) = policy.observe(time, error, 0, isCloud = false, canChangeSpeed = true)
        assertEquals(DriftSeekDecision.OBSERVING, observe(0, 1_000))
        assertEquals(DriftSeekDecision.OBSERVING, observe(1_500, -1_000))
        assertEquals(DriftSeekDecision.OBSERVING, observe(3_000, -1_000))
        assertEquals(DriftSeekDecision.SEEK, observe(3_500, -1_000))
        policy.reset()
        assertEquals(DriftSeekDecision.OBSERVING, observe(4_000, 1_000))
        assertEquals(DriftSeekDecision.SEEK, observe(6_000, 1_000))
    }
}
