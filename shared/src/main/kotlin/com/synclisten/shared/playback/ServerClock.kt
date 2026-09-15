package com.synclisten.shared.playback

import com.synclisten.shared.data.RepositoryResult
import com.synclisten.shared.data.RoomRepository
import com.synclisten.protocol.TimeSample
import com.synclisten.protocol.estimateClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

fun interface LocalClock { fun nowMs(): Long }

interface ServerTimeProvider {
    fun estimatedServerNowMs(): Long
    fun uncertaintyMs(): Long = 0
    fun isCalibrated(): Boolean = true
}

data class ServerClockState(
    val serverOffsetMs: Long = 0,
    val rttMs: Long = 0,
    val sampledAtMs: Long = 0,
    val error: String? = null,
    val uncertaintyMs: Long = 0,
    val sampleCount: Int = 0,
)

class ServerClock(
    private val repository: RoomRepository,
    private val localClock: LocalClock,
) : ServerTimeProvider {
    private val mutableState = MutableStateFlow(ServerClockState())
    val state: StateFlow<ServerClockState> = mutableState

    suspend fun refresh(sampleCount: Int = 5) {
        val samples = buildList {
            repeat(sampleCount.coerceIn(1, 8)) {
                val start = localClock.nowMs()
                val response = withTimeoutOrNull(3_000) { repository.getServerTime() }
                val end = localClock.nowMs()
                val time = (response as? RepositoryResult.Success)?.value
                if (time != null) {
                    TimeSample.from(start, time.serverReceivedAtMs ?: time.serverTimeMs,
                        time.serverSentAtMs ?: time.serverTimeMs, end)?.let { add(it) }
                }
            }
        }
        val estimate = estimateClock(samples)
        mutableState.value = if (estimate == null) state.value.copy(error = "服务器时间校准失败")
        else ServerClockState(estimate.offsetMs, estimate.rttMs, estimate.sampledAtMs,
            uncertaintyMs = estimate.uncertaintyMs, sampleCount = samples.size)
    }

    fun reset() { mutableState.value = ServerClockState() }
    fun isStale(): Boolean = state.value.sampleCount == 0 ||
        localClock.nowMs() - state.value.sampledAtMs > 90_000
    override fun isCalibrated(): Boolean = !isStale()
    override fun uncertaintyMs(): Long = state.value.uncertaintyMs +
        ((localClock.nowMs() - state.value.sampledAtMs).coerceAtLeast(0) / 10_000)
    override fun estimatedServerNowMs(): Long = localClock.nowMs() + state.value.serverOffsetMs
}
