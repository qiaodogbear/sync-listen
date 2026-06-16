package com.synclisten.shared.playback

import com.synclisten.shared.data.RepositoryResult
import com.synclisten.shared.data.RoomRepository
import com.synclisten.shared.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun interface LocalClock {
    fun nowMs(): Long
}

interface ServerTimeProvider {
    fun estimatedServerNowMs(): Long
}

data class ServerClockState(
    val serverOffsetMs: Long = 0,
    val rttMs: Long = 0,
    val sampledAtMs: Long = 0,
    val error: String? = null,
)

private data class ClockSample(
    val offsetMs: Long,
    val rttMs: Long,
    val sampledAtMs: Long,
)

class ServerClock(
    private val repository: RoomRepository,
    private val localClock: LocalClock,
) : ServerTimeProvider {
    private val mutableState = MutableStateFlow(ServerClockState())
    val state: StateFlow<ServerClockState> = mutableState

    suspend fun refresh(sampleCount: Int = DEFAULT_SAMPLE_COUNT) {
        val samples = buildList {
            repeat(sampleCount.coerceAtLeast(1)) {
                val startedAt = localClock.nowMs()
                val response = repository.getServerTime()
                val endedAt = localClock.nowMs()
                val serverTime = (response as? RepositoryResult.Success)?.value?.serverTimeMs
                if (serverTime != null && serverTime > 0 && endedAt >= startedAt) {
                    val rtt = endedAt - startedAt
                    val midpoint = startedAt + rtt / 2
                    add(ClockSample(serverTime - midpoint, rtt, endedAt))
                }
            }
        }
        val best = samples.minByOrNull { it.rttMs }
        if (best == null) {
            mutableState.value = mutableState.value.copy(error = "服务器时间校准失败")
            AppLogger.error("ServerClock", "no valid server time samples")
            return
        }
        mutableState.value = ServerClockState(best.offsetMs, best.rttMs, best.sampledAtMs)
        AppLogger.debug("ServerClock", "offset=${best.offsetMs} rtt=${best.rttMs}")
    }

    fun isStale(): Boolean {
        val sampledAt = mutableState.value.sampledAtMs
        return sampledAt > 0 && (localClock.nowMs() - sampledAt) > MAX_AGE_MS
    }

    override fun estimatedServerNowMs(): Long {
        if (isStale()) {
            AppLogger.debug("ServerClock", "offset stale, scheduling refresh")
        }
        return localClock.nowMs() + mutableState.value.serverOffsetMs
    }

    private companion object {
        const val DEFAULT_SAMPLE_COUNT = 5
        const val MAX_AGE_MS = 300_000L // 5 minutes
    }
}
