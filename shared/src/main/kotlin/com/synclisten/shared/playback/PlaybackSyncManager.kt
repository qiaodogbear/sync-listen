package com.synclisten.shared.playback

import com.synclisten.shared.domain.model.PlaybackState
import com.synclisten.shared.domain.model.MemberRole
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun MemberRole.canControlPlayback(): Boolean = this == MemberRole.HOST || this == MemberRole.ADMIN

data class PlaybackSyncState(
    val expectedPositionMs: Long = 0,
    val syncErrorMs: Long = 0,
    val playbackSpeed: Float = NORMAL_SPEED,
    val connected: Boolean = true,
    val checkpointCount: Long = 0,
    val correction: String = "等待同步",
) {
    companion object { const val NORMAL_SPEED = 1.0f }
}

class PlaybackSyncManager(
    private val player: PlaybackPort,
    private val clock: ServerTimeProvider,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val speedCorrectionEnabled: Boolean = true,
) {
    private val applyMutex = Mutex()
    private val mutableState = MutableStateFlow(PlaybackSyncState())
    val state: StateFlow<PlaybackSyncState> = mutableState
    private var reference: PlaybackState? = null
    private var receivedAt = 0L
    private var scheduled: Triple<String, Long, Long>? = null
    private var lastSeekAt: Long? = null
    private var connectionEpoch = 0L

    suspend fun apply(authoritative: PlaybackState) = applyMutex.withLock {
        reference = authoritative
        receivedAt = clock.estimatedServerNowMs()
        correct(authoritative)
    }

    /** Local checkpoints reuse the timeline, never re-execute a scheduled command. */
    suspend fun checkpoint() {
        if (!state.value.connected || !applyMutex.tryLock()) return
        try {
            val target = reference ?: return
            if (!clock.isCalibrated() || clock.estimatedServerNowMs() - receivedAt > 15_000) {
                setSpeed(1f)
                mutableState.value = state.value.copy(correction = "校时或状态过期，保持本地播放")
                return
            }
            mutableState.value = state.value.copy(checkpointCount = state.value.checkpointCount + 1)
            if (target.isPlaying || player.state.value.status == PlayerStatus.WAITING_FOR_CACHE) correct(target)
        } finally { applyMutex.unlock() }
    }

    private suspend fun correct(authoritative: PlaybackState) {
        val epoch = connectionEpoch
        val track = authoritative.trackId ?: run {
            setSpeed(1f)
            player.pause()
            scheduled = null
            return
        }
        if (player.state.value.trackId != track || player.state.value.status == PlayerStatus.WAITING_FOR_CACHE) {
            scheduled = null
            lastSeekAt = null
            player.prepare(track)
        }
        if (epoch != connectionEpoch || player.state.value.status in setOf(PlayerStatus.WAITING_FOR_CACHE, PlayerStatus.ERROR)) return
        if (!authoritative.isPlaying) {
            scheduled = null
            setSpeed(1f)
            player.seekTo(authoritative.positionMs)
            player.pause()
            update(authoritative.positionMs, correction = "已暂停")
            return
        }
        if (!clock.isCalibrated()) {
            setSpeed(1f)
            update(player.currentPositionMs(), correction = "等待时钟校准")
            return
        }
        val executeAt = authoritative.executeAtServerTimeMs
        val key = executeAt?.let { Triple(track, it, authoritative.positionMs) }
        if (executeAt != null && key != scheduled) {
            setSpeed(1f)
            val remaining = executeAt - clock.estimatedServerNowMs()
            if (remaining > 0) {
                if (player.state.value.status == PlayerStatus.PLAYING) player.pause()
                // Seek/prepare ahead of the deadline; only play is left at the deadline.
                player.seekTo(authoritative.positionMs)
                wait((executeAt - clock.estimatedServerNowMs()).coerceAtLeast(0))
            }
            if (!state.value.connected || epoch != connectionEpoch) return
            val late = (clock.estimatedServerNowMs() - executeAt).coerceAtLeast(0)
            val target = clamp(authoritative.positionMs + late)
            if (remaining <= 0 || late > 40) player.seekTo(target)
            scheduled = key
            lastSeekAt = clock.estimatedServerNowMs()
            player.play()
            update(target, correction = "计划播放")
            return
        }
        val now = clock.estimatedServerNowMs()
        val expected = clamp(authoritative.positionMs +
            (now - (executeAt ?: authoritative.serverTimeMs)).coerceAtLeast(0))
        val error = expected - player.currentPositionMs()
        if (player.state.value.status == PlayerStatus.ENDED && expected >= player.state.value.durationMs) {
            setSpeed(1f)
            update(expected, error, "曲目结束")
            return
        }
        val uncertainty = clock.uncertaintyMs()
        val enter = maxOf(SPEED_THRESHOLD_MS, uncertainty * 2)
        val exit = maxOf(20L, uncertainty * 2)
        var action = "容差内"
        when {
            uncertainty > 150 -> { setSpeed(1f); action = "网络不确定度过高，暂停纠偏" }
            abs(error) > SEEK_THRESHOLD_MS && (lastSeekAt == null || now - lastSeekAt!! >= 2_000) -> {
                setSpeed(1f)
                player.seekTo(expected)
                lastSeekAt = now
                action = "大误差定位"
            }
            !speedCorrectionEnabled -> {
                setSpeed(1f)
                if (abs(error) >= enter && (lastSeekAt == null || now - lastSeekAt!! >= 2_000)) {
                    player.seekTo(expected)
                    lastSeekAt = now
                    action = "桌面定位纠偏"
                }
            }
            abs(error) >= enter || (state.value.playbackSpeed != 1f && abs(error) > exit) -> {
                setSpeed((1.0 + error / 5_000.0).coerceIn(0.98, 1.02).toFloat())
                action = "比例速度微调"
            }
            else -> setSpeed(1f)
        }
        player.play()
        update(expected, error, action)
    }

    fun setConnected(connected: Boolean) {
        if (!connected) {
            connectionEpoch++
            setSpeed(1f)
            reference = null
            scheduled = null
        }
        mutableState.value = state.value.copy(
            connected = connected,
            correction = when {
                !connected -> "已断线，保持本地播放"
                !state.value.connected -> "等待新快照"
                else -> state.value.correction
            },
        )
    }

    fun reset() {
        connectionEpoch++
        setSpeed(1f)
        reference = null
        scheduled = null
        lastSeekAt = null
        mutableState.value = PlaybackSyncState()
    }

    private fun clamp(position: Long): Long = player.state.value.durationMs.let {
        if (it > 0) position.coerceIn(0, it) else position.coerceAtLeast(0)
    }

    private fun update(expected: Long, error: Long = expected - player.currentPositionMs(), correction: String) {
        mutableState.value = state.value.copy(expectedPositionMs = expected, syncErrorMs = error, correction = correction)
    }

    private fun setSpeed(speed: Float) {
        if (state.value.playbackSpeed == speed) return
        player.setPlaybackSpeed(speed)
        mutableState.value = state.value.copy(playbackSpeed = speed)
    }

    companion object {
        const val SPEED_THRESHOLD_MS = 40L
        const val SEEK_THRESHOLD_MS = 300L
        const val FORCE_RESYNC_THRESHOLD_MS = 1_000L
        const val CATCH_UP_SPEED = 1.02f
        const val SLOW_DOWN_SPEED = 0.98f
    }
}
