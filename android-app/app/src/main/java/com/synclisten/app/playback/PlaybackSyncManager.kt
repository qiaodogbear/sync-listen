package com.synclisten.app.playback

import com.synclisten.app.domain.model.PlaybackState
import com.synclisten.app.domain.model.MemberRole
import com.synclisten.app.util.AppLogger
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun MemberRole.canControlPlayback(): Boolean = this == MemberRole.HOST

data class PlaybackSyncState(
    val expectedPositionMs: Long = 0,
    val syncErrorMs: Long = 0,
    val connected: Boolean = true,
)

class PlaybackSyncManager(
    private val player: PlaybackPort,
    private val clock: ServerTimeProvider,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutableState = MutableStateFlow(PlaybackSyncState())
    val state: StateFlow<PlaybackSyncState> = mutableState

    suspend fun apply(authoritative: PlaybackState) {
        val trackId = authoritative.trackId ?: run {
            player.pause()
            return
        }
        if (player.state.value.trackId != trackId) player.prepare(trackId)
        if (!authoritative.isPlaying) {
            player.seekTo(authoritative.positionMs)
            player.pause()
            update(authoritative.positionMs)
            return
        }
        val executeAt = authoritative.executeAtServerTimeMs
        if (executeAt != null) {
            wait((executeAt - clock.estimatedServerNowMs()).coerceAtLeast(0))
            player.seekTo(authoritative.positionMs)
            player.play()
            update(authoritative.positionMs)
            return
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
            return
        }
        if (abs(error) > SEEK_THRESHOLD_MS) player.seekTo(expected)
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
        AppLogger.debug("PlaybackSync", "expected=$expected error=$error connected=${mutableState.value.connected}")
    }

    companion object {
        const val SEEK_THRESHOLD_MS = 300L
    }
}
