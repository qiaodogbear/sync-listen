package com.synclisten.app.playback

import com.synclisten.app.domain.model.PlaybackState
import com.synclisten.app.domain.model.MemberRole
import com.synclisten.app.util.AppLogger
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
) {
    companion object {
        const val NORMAL_SPEED = 1.0f
    }
}

class PlaybackSyncManager(
    private val player: PlaybackPort,
    private val clock: ServerTimeProvider,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val applyMutex = Mutex()
    private val mutableState = MutableStateFlow(PlaybackSyncState())
    val state: StateFlow<PlaybackSyncState> = mutableState

    suspend fun apply(authoritative: PlaybackState) = applyMutex.withLock {
        val trackId = authoritative.trackId ?: run {
            setSpeed(PlaybackSyncState.NORMAL_SPEED)
            player.pause()
            return@withLock
        }
        if (player.state.value.trackId != trackId || player.state.value.status == PlayerStatus.WAITING_FOR_CACHE) player.prepare(trackId)
        if (player.state.value.status in setOf(PlayerStatus.WAITING_FOR_CACHE, PlayerStatus.ERROR)) return@withLock
        if (!authoritative.isPlaying) {
            setSpeed(PlaybackSyncState.NORMAL_SPEED)
            player.seekTo(authoritative.positionMs)
            player.pause()
            update(authoritative.positionMs)
            return@withLock
        }
        val executeAt = authoritative.executeAtServerTimeMs
        if (executeAt != null) {
            wait((executeAt - clock.estimatedServerNowMs()).coerceAtLeast(0))
            val lateBy = (clock.estimatedServerNowMs() - executeAt).coerceAtLeast(0)
            val target = (authoritative.positionMs + lateBy).let { position ->
                val duration = player.state.value.durationMs
                if (duration > 0) position.coerceAtMost(duration) else position
            }
            setSpeed(PlaybackSyncState.NORMAL_SPEED)
            player.seekTo(target)
            player.play()
            update(target)
            return@withLock
        }
        val rawExpected = (
            authoritative.positionMs +
                (clock.estimatedServerNowMs() - authoritative.serverTimeMs).coerceAtLeast(0)
            ).coerceAtLeast(0)
        val playerState = player.state.value
        val expected = if (playerState.durationMs > 0) {
            rawExpected.coerceAtMost(playerState.durationMs)
        } else {
            rawExpected
        }
        val error = expected - playerState.positionMs
        if (playerState.status == PlayerStatus.ENDED && expected >= playerState.durationMs) {
            update(expected, error)
            return@withLock
        }
        when {
            abs(error) > FORCE_RESYNC_THRESHOLD_MS -> {
                AppLogger.debug("PlaybackSync", "forcing resync error=$error")
                setSpeed(PlaybackSyncState.NORMAL_SPEED)
                player.seekTo(expected)
            }
            abs(error) > SEEK_THRESHOLD_MS -> {
                setSpeed(PlaybackSyncState.NORMAL_SPEED)
                player.seekTo(expected)
            }
            abs(error) >= SPEED_THRESHOLD_MS -> setSpeed(if (error > 0) CATCH_UP_SPEED else SLOW_DOWN_SPEED)
            else -> setSpeed(PlaybackSyncState.NORMAL_SPEED)
        }
        player.play()
        update(expected, error)
    }

    fun setConnected(connected: Boolean) {
        mutableState.value = mutableState.value.copy(connected = connected)
    }

    private fun update(expected: Long, error: Long = expected - player.state.value.positionMs) {
        mutableState.value = mutableState.value.copy(
            expectedPositionMs = expected,
            syncErrorMs = error,
        )
        AppLogger.debug(
            "PlaybackSync",
            "expected=$expected error=$error speed=${mutableState.value.playbackSpeed} connected=${mutableState.value.connected}",
        )
    }

    private fun setSpeed(speed: Float) {
        if (mutableState.value.playbackSpeed == speed) return
        player.setPlaybackSpeed(speed)
        mutableState.value = mutableState.value.copy(playbackSpeed = speed)
    }

    companion object {
        const val SPEED_THRESHOLD_MS = 80L
        const val SEEK_THRESHOLD_MS = 300L
        const val FORCE_RESYNC_THRESHOLD_MS = 1_000L
        const val CATCH_UP_SPEED = 1.02f
        const val SLOW_DOWN_SPEED = 0.98f
    }
}
