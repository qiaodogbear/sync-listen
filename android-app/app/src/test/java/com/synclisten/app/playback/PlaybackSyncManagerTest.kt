package com.synclisten.app.playback

import com.synclisten.app.domain.model.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
    fun catchesUpWhenScheduledPlaybackIsAlreadyLate() = runBlocking {
        val player = FakePlaybackPort()
        val manager = PlaybackSyncManager(player, FixedServerTime(1_800)) {}

        manager.apply(state(trackId = "track", positionMs = 250, isPlaying = true, executeAt = 1_500))

        assertEquals(listOf("prepare:track", "seek:550", "play"), player.commands)
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
        assertEquals(listOf("speed:1.02", "play"), player.commands)
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

    @Test
    fun serializesDuplicateEventsWhileTrackIsPreparing() = runBlocking {
        val player = BlockingPreparePort()
        val manager = PlaybackSyncManager(player, FixedServerTime(1_000)) {}
        val event = state(trackId = "track", positionMs = 0, isPlaying = true, executeAt = 1_000)

        val first = async { manager.apply(event) }
        player.entered.await()
        val second = async { manager.apply(event) }
        player.release.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, player.prepareCalls)
    }

    @Test
    fun usesPlaybackSpeedToCorrectPositiveAndNegativeSmallDrift() = runBlocking {
        val player = FakePlaybackPort(positionMs = 1_000)
        val manager = PlaybackSyncManager(player, FixedServerTime(2_000)) {}

        manager.apply(state(trackId = "track", positionMs = 1_150, isPlaying = true, serverTime = 2_000))
        assertEquals(listOf("speed:1.02", "play"), player.commands)
        assertEquals(1.02f, manager.state.value.playbackSpeed)

        player.commands.clear()
        manager.apply(state(trackId = "track", positionMs = 850, isPlaying = true, serverTime = 2_000))
        assertEquals(listOf("speed:0.98", "play"), player.commands)
        assertEquals(0.98f, manager.state.value.playbackSpeed)
    }

    @Test
    fun restoresNormalSpeedInsideToleranceWithoutRepeatedCommands() = runBlocking {
        val player = FakePlaybackPort(positionMs = 1_000)
        val manager = PlaybackSyncManager(player, FixedServerTime(2_000)) {}

        manager.apply(state(trackId = "track", positionMs = 1_150, isPlaying = true, serverTime = 2_000))
        player.commands.clear()
        manager.apply(state(trackId = "track", positionMs = 1_010, isPlaying = true, serverTime = 2_000))
        assertEquals(listOf("speed:1.0", "play"), player.commands)

        player.commands.clear()
        manager.apply(state(trackId = "track", positionMs = 1_010, isPlaying = true, serverTime = 2_000))
        assertEquals(listOf("play"), player.commands)
    }

    @Test
    fun largeDriftSeeksAndRestoresNormalSpeed() = runBlocking {
        val player = FakePlaybackPort(positionMs = 1_000)
        val manager = PlaybackSyncManager(player, FixedServerTime(2_000)) {}

        manager.apply(state(trackId = "track", positionMs = 1_150, isPlaying = true, serverTime = 2_000))
        player.commands.clear()
        manager.apply(state(trackId = "track", positionMs = 2_500, isPlaying = true, serverTime = 2_000))

        assertEquals(listOf("speed:1.0", "seek:2500", "play"), player.commands)
        assertEquals(1.0f, manager.state.value.playbackSpeed)
    }

    @Test fun checkpointUsesFreshPositionAndDoesNotRepeatScheduledSeek() = runBlocking {
        var now = 1_000L
        val player = FakePlaybackPort()
        val manager = PlaybackSyncManager(player, object : ServerTimeProvider {
            override fun estimatedServerNowMs() = now
        }) { now += it }
        manager.apply(state("track", 250, true, executeAt = 1_500))
        player.commands.clear()
        now = 2_000
        player.mutableState.value = player.mutableState.value.copy(positionMs = 750)
        manager.checkpoint()
        assertEquals(listOf("play"), player.commands)
        assertEquals(0, manager.state.value.syncErrorMs)
        assertEquals(1, manager.state.value.checkpointCount)
    }

    @Test fun disconnectRestoresSpeedAndDisablesCheckpoints() = runBlocking {
        val player = FakePlaybackPort(1_000)
        val manager = PlaybackSyncManager(player, FixedServerTime(2_000)) {}
        manager.apply(state("track", 1_150, true, serverTime = 2_000))
        player.commands.clear()
        manager.setConnected(false)
        manager.checkpoint()
        assertEquals(listOf("speed:1.0"), player.commands)
        assertEquals("已断线，保持本地播放", manager.state.value.correction)
        manager.setConnected(true)
        assertEquals("等待新快照", manager.state.value.correction)
    }

    @Test fun poorClockQualityDoesNotCauseSeekOrSpeedThrashing() = runBlocking {
        val player = FakePlaybackPort(1_000)
        val manager = PlaybackSyncManager(player, object : ServerTimeProvider {
            override fun estimatedServerNowMs() = 2_000L
            override fun uncertaintyMs() = 200L
        }) {}
        manager.apply(state("track", 3_000, true, serverTime = 2_000))
        assertEquals(listOf("play"), player.commands)
    }

    @Test fun seekHasCooldownAgainstPersistentJitter() = runBlocking {
        val player = FakePlaybackPort(1_000)
        val manager = PlaybackSyncManager(player, FixedServerTime(2_000)) {}
        manager.apply(state("track", 2_000, true, serverTime = 2_000))
        player.commands.clear()
        player.mutableState.value = player.mutableState.value.copy(positionMs = 1_000)
        manager.checkpoint()
        assertFalse(player.commands.any { it.startsWith("seek") })
    }

    @Test fun proportionalCheckpointsConvergeWithClockDrift() = runBlocking {
        var now = 1_000L
        var actual = 800.0
        var speed = 1f
        val commands = FakePlaybackPort(800)
        val player = object : PlaybackPort by commands {
            override fun currentPositionMs() = actual.toLong()
            override fun setPlaybackSpeed(value: Float) { speed = value }
        }
        val manager = PlaybackSyncManager(player, object : ServerTimeProvider {
            override fun estimatedServerNowMs() = now
        }) {}
        repeat(80) { step ->
            if (step % 10 == 0) manager.apply(state("track", now, true, serverTime = now))
            else manager.checkpoint()
            actual += 500 * speed * 1.0002
            now += 500
        }
        org.junit.Assert.assertTrue(kotlin.math.abs(now - actual) < 40)
        assertFalse(commands.commands.any { it.startsWith("seek") })
    }

    @Test fun samplesFreshEnginePositionInsteadOfUiTicker() = runBlocking {
        val commands = FakePlaybackPort(1_000)
        val player = object : PlaybackPort by commands {
            override fun currentPositionMs() = 1_250L
        }
        val manager = PlaybackSyncManager(player, FixedServerTime(2_000)) {}
        manager.apply(state("track", 1_250, true, serverTime = 2_000))
        assertEquals(0, manager.state.value.syncErrorMs)
        assertEquals(listOf("play"), commands.commands)
    }

    @Test fun reconnectCannotRevivePlanFromPreviousConnection() = runBlocking {
        val player = FakePlaybackPort()
        lateinit var manager: PlaybackSyncManager
        manager = PlaybackSyncManager(player, FixedServerTime(1_000)) {
            manager.setConnected(false)
            manager.setConnected(true)
        }
        manager.apply(state("track", 250, true, executeAt = 1_500))
        assertFalse(player.commands.contains("play"))
    }

    private fun state(
        trackId: String,
        positionMs: Long,
        isPlaying: Boolean,
        serverTime: Long = 1_000,
        executeAt: Long? = null,
    ) = PlaybackState(trackId, positionMs, isPlaying, serverTime, executeAt)
}

private class BlockingPreparePort : PlaybackPort {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var prepareCalls = 0
    private val mutableState = MutableStateFlow(PlayerControllerState())
    override val state: StateFlow<PlayerControllerState> = mutableState
    override suspend fun prepare(trackId: String) {
        prepareCalls += 1
        entered.complete(Unit)
        release.await()
        mutableState.value = mutableState.value.copy(trackId = trackId)
    }
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setPlaybackSpeed(speed: Float) = Unit
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
