package com.synclisten.app.playback

import com.synclisten.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ClockEstimateTest {
    @Test fun subtractsServerProcessingFromRtt() {
        val sample = TimeSample.from(1_000, 2_010, 2_090, 1_100)!!
        assertEquals(20, sample.networkRttMs)
        assertEquals(1_000, sample.offsetMs)
    }
    @Test fun rejectsImpossibleAndLongSamples() {
        assertNull(TimeSample.from(100, 1_000, 1_020, 110))
        assertNull(TimeSample.from(100, 1_000, 999, 120))
        assertNull(TimeSample.from(100, 1_000, 1_000, 20_000))
    }
    @Test fun filtersQueueingAndUsesMedianWithinLowDelaySet() {
        val result = estimateClock(listOf(TimeSample(100, 20, 1_000), TimeSample(101, 22, 2_000),
            TimeSample(500, 400, 3_000), TimeSample(99, 21, 4_000)))!!
        assertEquals(100, result.offsetMs)
        assertEquals(12, result.uncertaintyMs)
        assertEquals(4_000, result.sampledAtMs)
    }
    @Test fun monotonicEpochDoesNotReadWallClockAgain() {
        var ticks = 500L
        val clock = MonotonicEpochClock(1_000) { ticks }
        ticks += 25
        assertEquals(1_025, clock.nowMs())
    }
}
