package com.synclisten.shared

import com.synclisten.shared.data.*
import com.synclisten.shared.domain.model.PlaybackState
import com.synclisten.shared.playback.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SyncCheckpointTest {
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

    @Test fun desktopCheckpointsUseSeekCooldownWithoutSpeedChanges() = runBlocking {
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
        assertEquals(listOf("seek"), commands)
        player.state.value = player.state.value.copy(positionMs = 900)
        now += 500
        manager.checkpoint()
        assertEquals(listOf("seek"), commands)
        manager.setConnected(false)
        manager.checkpoint()
        assertEquals(listOf("seek"), commands)
    }
}
