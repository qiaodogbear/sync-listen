package com.synclisten.sync

import kotlin.math.abs

enum class DriftSeekDecision { WITHIN_TOLERANCE, OBSERVING, COOLDOWN, SEEK }

/** A seek interrupts audio; require a persistent, directional error before applying it. */
class DriftSeekPolicy {
    private var since: Long? = null
    private var direction = 0
    private var lastSeekAt: Long? = null

    fun observe(now: Long, error: Long, uncertainty: Long, isCloud: Boolean, canChangeSpeed: Boolean): DriftSeekDecision {
        val threshold = maxOf(if (canChangeSpeed) 800L else if (isCloud) 500L else 300L, uncertainty * 2)
        if (abs(error) <= threshold) {
            since = null
            direction = 0
            return DriftSeekDecision.WITHIN_TOLERANCE
        }
        val sign = if (error > 0) 1 else -1
        if (since == null || direction != sign || now < since!!) {
            since = now
            direction = sign
        }
        if (now - since!! < 2_000) return DriftSeekDecision.OBSERVING
        val cooldown = if (isCloud && !canChangeSpeed) 30_000L else 15_000L
        if (lastSeekAt?.let { now - it < cooldown } == true) return DriftSeekDecision.COOLDOWN
        markSeek(now)
        return DriftSeekDecision.SEEK
    }

    fun markSeek(now: Long) {
        lastSeekAt = now
        since = null
        direction = 0
    }

    fun clearObservation() { since = null; direction = 0 }
    fun reset() { clearObservation(); lastSeekAt = null }
}
