package com.synclisten.shared

import com.synclisten.shared.data.*
import com.synclisten.shared.domain.model.PlaybackState
import com.synclisten.shared.playback.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SyncCheckpointTest {
    @Test fun scheduledSeekIsNotBlockedByAutomaticCorrectionCooldown() = runBlocking {
        var now = 1_000L
        val seeks = mutableListOf<Long>()
        val player = object : PlaybackPort {
            override val state = MutableStateFlow(PlayerControllerState(trackId = "track", status = PlayerStatus.PLAYING))
            override suspend fun prepare(trackId: String) = Unit
            override fun currentPositionMs() = 0L
            override fun play() = Unit
            override fun pause() = Unit
            override fun seekTo(positionMs: Long) { seeks += positionMs }
            override fun setPlaybackSpeed(speed: Float) = Unit
        }
        val manager = PlaybackSyncManager(player, object : ServerTimeProvider { override fun estimatedServerNowMs() = now },
            wait = { now += it }, speedCorrectionEnabled = false)
        manager.apply(PlaybackState("track", 1_000, true, now, null))
        now += 2_000
        manager.checkpoint()
        assertEquals(listOf(3_000L), seeks)
        manager.apply(PlaybackState("track", 9_000, true, now + 500, now + 500))
        assertEquals(9_000L, seeks.last())
    }
    @Test fun desktopSmallPersistentOffsetDoesNotRestartAudio() = runBlocking {
        var now = 1_000L
        var seeks = 0
        val player = object : PlaybackPort {
            override val state = MutableStateFlow(PlayerControllerState(trackId = "track", status = PlayerStatus.PLAYING, isCloud = true))
            override suspend fun prepare(trackId: String) = Unit
            override fun currentPositionMs() = now - 100
            override fun play() = Unit
            override fun pause() = Unit
            override fun seekTo(positionMs: Long) { seeks++ }
            override fun setPlaybackSpeed(speed: Float) = Unit
        }
        val manager = PlaybackSyncManager(player, object : ServerTimeProvider { override fun estimatedServerNowMs() = now }, speedCorrectionEnabled = false)
        repeat(61) { step ->
            if (step % 10 == 0) manager.apply(PlaybackState("track", now, true, now, null)) else manager.checkpoint()
            now += 500
        }
        assertEquals("Small stable offset should not repeatedly reopen a cloud stream", 0, seeks)
    }
    @Test fun cloudSeekWaitsForDecoderBeforeStartingPlayback() = runBlocking {
        var now = 2_000L
        var plays = 0
        val player = object : PlaybackPort {
            override val state = MutableStateFlow(PlayerControllerState(trackId = "track", status = PlayerStatus.READY, isCloud = true))
            override suspend fun prepare(trackId: String) = Unit
            override fun play() { plays++ }
            override fun pause() = Unit
            override fun seekTo(positionMs: Long) {
                state.value = state.value.copy(positionMs = positionMs, status = PlayerStatus.BUFFERING)
            }
            override fun setPlaybackSpeed(speed: Float) = Unit
        }
        val clock = object : ServerTimeProvider { override fun estimatedServerNowMs() = now }
        val manager = PlaybackSyncManager(player, clock, speedCorrectionEnabled = false)
        manager.apply(PlaybackState("track", 0, true, 1_000, 1_000))
        assertEquals(0, plays)
        assertEquals("等待音频准备", manager.state.value.correction)
        player.state.value = player.state.value.copy(status = PlayerStatus.READY)
        now += 10
        manager.checkpoint()
        assertEquals(1, plays)
    }

    @Test fun acceptsLegacyAndExtendedTimeResponses() = runBlocking {
        for (extended in listOf(false, true)) {
            val times = (if (extended) listOf(1_000L, 1_100L) else listOf(1_000L, 1_020L)).iterator()
            val remote = object : RoomRemoteDataSource {
                override suspend fun getServerTime() = if (extended) ServerTimeResponse(2_090, 2_010, 2_090)
                    else ServerTimeResponse(2_010)
            }
            val clock = ServerClock(RoomRepository(remote), LocalClock { times.next() })
            clock.refresh(1)
            assertEquals(1_000, clock.state.value.serverOffsetMs)
            assertEquals(20, clock.state.value.rttMs)
            assertEquals(1, clock.state.value.sampleCount)
        }
    }

    @Test fun desktopCheckpointsIgnoreSmallOffsetsWithoutSpeedChanges() = runBlocking {
        var now = 1_000L
        val commands = mutableListOf<String>()
        val player = object : PlaybackPort {
            override val state = MutableStateFlow(PlayerControllerState(trackId = "track", positionMs = 900))
            override suspend fun prepare(trackId: String) = Unit
            override fun play() = Unit
            override fun pause() = Unit
            override fun seekTo(positionMs: Long) { commands += "seek"; state.value = state.value.copy(positionMs = positionMs) }
            override fun setPlaybackSpeed(speed: Float) { commands += "speed" }
        }
        val clock = object : ServerTimeProvider { override fun estimatedServerNowMs() = now }
        val manager = PlaybackSyncManager(player, clock, speedCorrectionEnabled = false)
        manager.apply(PlaybackState("track", 1_000, true, 1_000, null))
        assertEquals(emptyList<String>(), commands)
        player.state.value = player.state.value.copy(positionMs = 900)
        now += 500
        manager.checkpoint()
        assertEquals(emptyList<String>(), commands)
        manager.setConnected(false)
        manager.checkpoint()
        assertEquals(emptyList<String>(), commands)
    }
}
