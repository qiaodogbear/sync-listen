package com.synclisten.app.playback

import com.synclisten.app.data.RepositoryResult
import com.synclisten.app.data.RoomRepository
import com.synclisten.app.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton
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

@Singleton
class ServerClock @Inject constructor(
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

    override fun estimatedServerNowMs(): Long = localClock.nowMs() + mutableState.value.serverOffsetMs

    private companion object {
        const val DEFAULT_SAMPLE_COUNT = 5
    }
}
