package com.synclisten.protocol

import kotlin.math.abs

data class TimeSample(val offsetMs: Long, val networkRttMs: Long, val receivedAtMs: Long) {
    companion object {
        fun from(t1: Long, t2: Long, t3: Long, t4: Long): TimeSample? {
            val elapsed = t4 - t1
            val processing = t3 - t2
            if (t2 <= 0 || t3 <= 0 || elapsed !in 0..10_000 || processing !in 0..elapsed) return null
            return TimeSample(((t2 - t1) + (t3 - t4)) / 2, elapsed - processing, t4)
        }
    }
}

data class ClockEstimate(val offsetMs: Long, val rttMs: Long, val uncertaintyMs: Long, val sampledAtMs: Long)

/** Low-delay samples limit queueing bias; dispersion is exposed, not hidden as accuracy. */
fun estimateClock(samples: List<TimeSample>): ClockEstimate? {
    val best = samples.minByOrNull { it.networkRttMs } ?: return null
    val usable = samples.filter { it.networkRttMs <= best.networkRttMs + 5 }.sortedBy { it.offsetMs }
    val offset = usable[usable.size / 2].offsetMs
    val dispersion = usable.maxOf { abs(it.offsetMs - offset) }
    return ClockEstimate(offset, best.networkRttMs, (best.networkRttMs + 1) / 2 + dispersion + 1,
        samples.maxOf { it.receivedAtMs })
}

/** Epoch-compatible clock immune to manual wall-clock edits after construction. */
class MonotonicEpochClock(
    private val epochMs: Long,
    private val ticksMs: () -> Long,
) {
    private val origin = ticksMs()
    fun nowMs(): Long = epochMs + ticksMs() - origin
}
