package com.synclisten.app.playback

import com.synclisten.app.domain.model.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackSyncManagerTest {
    @Test
    fun waitsForScheduledServerTimeBeforePlaying() = runBlocking {
        val player = FakePlaybackPort()
        val delays = mutableListOf<Long>()
        val manager = PlaybackSyncManager(player, FixedServerTime(1_000)) { delays += it }

        manager.apply(state(trackId = "track", positionMs = 250, isPlaying = true, executeAt = 1_500))

        assertEquals(listOf(500L), delays)
        assertEquals(listOf("prepare:track", "seek:250", "play"), player.commands)
    }

    @Test
    fun seeksOnlyWhenSyncErrorExceedsThreshold() = runBlocking {
        val player = FakePlaybackPort(positionMs = 1_000)
        val manager = PlaybackSyncManager(player, FixedServerTime(2_500)) {}

        manager.apply(state(trackId = "track", positionMs = 1_000, isPlaying = true, serverTime = 2_000))
        assertEquals(listOf("seek:1500", "play"), player.commands)
        assertEquals(500, manager.state.value.syncErrorMs)

        player.commands.clear()
        player.mutableState.value = player.mutableState.value.copy(positionMs = 1_350)
        manager.apply(state(trackId = "track", positionMs = 1_000, isPlaying = true, serverTime = 2_000))
        assertEquals(listOf("play"), player.commands)
    }

    @Test
    fun pauseIsImmediateAndDisconnectDoesNotStopPlayback() = runBlocking {
        val player = FakePlaybackPort()
        val manager = PlaybackSyncManager(player, FixedServerTime(1_000)) {}

        manager.apply(state(trackId = "track", positionMs = 700, isPlaying = false))
        assertEquals(listOf("prepare:track", "seek:700", "pause"), player.commands)

        player.commands.clear()
        manager.setConnected(false)
        assertFalse(manager.state.value.connected)
        assertEquals(emptyList<String>(), player.commands)
    }

    @Test
    fun syncPastDurationDoesNotRestartEndedTrack() = runBlocking {
        val player = FakePlaybackPort(positionMs = 1_400).apply {
            mutableState.value = mutableState.value.copy(
                status = PlayerStatus.ENDED,
                durationMs = 1_400,
            )
        }
        val manager = PlaybackSyncManager(player, FixedServerTime(10_000)) {}

        manager.apply(state(trackId = "track", positionMs = 5_000, isPlaying = true, serverTime = 5_000))

        assertEquals(emptyList<String>(), player.commands)
        assertEquals(1_400, manager.state.value.expectedPositionMs)
        assertEquals(0, manager.state.value.syncErrorMs)
    }

    private fun state(
        trackId: String,
        positionMs: Long,
        isPlaying: Boolean,
        serverTime: Long = 1_000,
        executeAt: Long? = null,
    ) = PlaybackState(trackId, positionMs, isPlaying, serverTime, executeAt)
}

private class FixedServerTime(private val now: Long) : ServerTimeProvider {
    override fun estimatedServerNowMs() = now
}

private class FakePlaybackPort(positionMs: Long = 0) : PlaybackPort {
    val commands = mutableListOf<String>()
    val mutableState = MutableStateFlow(
        PlayerControllerState(trackId = if (positionMs > 0) "track" else null, positionMs = positionMs),
    )
    override val state: StateFlow<PlayerControllerState> = mutableState
    override suspend fun prepare(trackId: String) {
        commands += "prepare:$trackId"
        mutableState.value = mutableState.value.copy(trackId = trackId)
    }
    override fun play() { commands += "play" }
    override fun pause() { commands += "pause" }
    override fun seekTo(positionMs: Long) {
        commands += "seek:$positionMs"
        mutableState.value = mutableState.value.copy(positionMs = positionMs)
    }
    override fun setPlaybackSpeed(speed: Float) { commands += "speed:$speed" }
}
