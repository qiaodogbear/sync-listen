package com.synclisten.shared.playback

import com.synclisten.shared.domain.model.PlaybackState
import com.synclisten.shared.domain.model.MemberRole
import com.synclisten.sync.DriftSeekDecision
import com.synclisten.sync.DriftSeekPolicy
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
    private val seekPolicy = DriftSeekPolicy()
    private var lastSpeedChangeAt: Long? = null
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
                seekPolicy.clearObservation()
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
            seekPolicy.reset()
            setSpeed(1f)
            player.pause()
            scheduled = null
            return
        }
        if (player.state.value.trackId != track || player.state.value.status == PlayerStatus.WAITING_FOR_CACHE) {
            scheduled = null
            seekPolicy.reset()
            player.prepare(track)
        }
        if (epoch != connectionEpoch || player.state.value.status in setOf(PlayerStatus.WAITING_FOR_CACHE, PlayerStatus.ERROR)) return
        if (player.state.value.status in setOf(PlayerStatus.PREPARING, PlayerStatus.BUFFERING)) {
            seekPolicy.clearObservation()
            mutableState.value = state.value.copy(correction = "等待音频准备")
            return
        }
        if (!authoritative.isPlaying) {
            scheduled = null
            seekPolicy.clearObservation()
            setSpeed(1f)
            if (player.state.value.isCloud) player.pause()
            if (!player.state.value.isCloud || abs(player.currentPositionMs() - authoritative.positionMs) > 20) {
                player.seekTo(authoritative.positionMs)
            }
            if (!player.state.value.isCloud) player.pause()
            update(authoritative.positionMs, correction = "已暂停")
            return
        }
        if (!clock.isCalibrated()) {
            seekPolicy.clearObservation()
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
                if (!player.state.value.isCloud || abs(player.currentPositionMs() - authoritative.positionMs) > 20) {
                    player.seekTo(authoritative.positionMs)
                }
                wait((executeAt - clock.estimatedServerNowMs()).coerceAtLeast(0))
            }
            if (!state.value.connected || epoch != connectionEpoch) return
            if (player.state.value.status in setOf(PlayerStatus.PREPARING, PlayerStatus.BUFFERING, PlayerStatus.ERROR)) {
                mutableState.value = state.value.copy(correction = "等待音频准备")
                return
            }
            val late = (clock.estimatedServerNowMs() - executeAt).coerceAtLeast(0)
            val target = clamp(authoritative.positionMs + late)
            scheduled = key
            seekPolicy.markSeek(clock.estimatedServerNowMs())
            if ((remaining <= 0 || late > 40) &&
                (!player.state.value.isCloud || abs(player.currentPositionMs() - target) > SEEK_THRESHOLD_MS)) {
                player.seekTo(target)
            }
            if (player.state.value.status in setOf(PlayerStatus.PREPARING, PlayerStatus.BUFFERING, PlayerStatus.ERROR)) {
                mutableState.value = state.value.copy(correction = "等待音频准备")
                return
            }
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
        val decision = if (uncertainty <= 150) seekPolicy.observe(now, error, uncertainty, player.state.value.isCloud, speedCorrectionEnabled)
            else { seekPolicy.clearObservation(); DriftSeekDecision.WITHIN_TOLERANCE }
        when {
            uncertainty > 150 -> { setSpeed(1f); action = "网络不确定度过高，暂停纠偏" }
            decision == DriftSeekDecision.SEEK -> {
                setSpeed(1f)
                player.seekTo(expected)
                action = "大误差定位"
            }
            !speedCorrectionEnabled -> {
                setSpeed(1f)
                action = when (decision) { DriftSeekDecision.OBSERVING -> "等待偏差稳定"; DriftSeekDecision.COOLDOWN -> "纠偏冷却"; else -> "容差内" }
            }
            abs(error) >= enter || (state.value.playbackSpeed != 1f && abs(error) > exit) -> {
                setCorrectionSpeed((1.0 + error / 5_000.0).coerceIn(0.98, 1.02).toFloat(), now)
                action = "比例速度微调"
            }
            else -> setSpeed(1f)
        }
        if (player.state.value.status in setOf(PlayerStatus.PREPARING, PlayerStatus.BUFFERING, PlayerStatus.ERROR)) {
            mutableState.value = state.value.copy(correction = "等待音频准备")
            return
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
            seekPolicy.reset()
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
        seekPolicy.reset()
        lastSpeedChangeAt = null
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
        lastSpeedChangeAt = clock.estimatedServerNowMs()
    }

    private fun setCorrectionSpeed(speed: Float, now: Long) {
        val rounded = (kotlin.math.round(speed * 200) / 200).coerceIn(0.98f, 1.02f)
        val last = lastSpeedChangeAt
        if (last == null || now < last || now - last >= 2_000) setSpeed(rounded)
    }

    companion object {
        const val SPEED_THRESHOLD_MS = 40L
        const val SEEK_THRESHOLD_MS = 300L
        const val FORCE_RESYNC_THRESHOLD_MS = 1_000L
        const val CATCH_UP_SPEED = 1.02f
        const val SLOW_DOWN_SPEED = 0.98f
    }
}
