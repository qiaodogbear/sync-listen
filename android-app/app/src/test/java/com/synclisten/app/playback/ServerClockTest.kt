package com.synclisten.app.playback

import com.synclisten.app.data.RoomRemoteDataSource
import com.synclisten.app.data.RoomRepository
import com.synclisten.app.data.ServerTimeResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ServerClockTest {
    @Test
    fun usesLowestRttSampleToEstimateServerOffset() = runBlocking {
        val local = SequenceLocalClock(1_000, 1_100, 2_000, 2_020, 3_000, 3_060, 5_000, 6_000)
        val remote = SequenceTimeRemote(2_050, 3_010, 4_030)
        val clock = ServerClock(RoomRepository(remote), local)

        clock.refresh(sampleCount = 3)

        assertEquals(1_000, clock.state.value.serverOffsetMs)
        assertEquals(20, clock.state.value.rttMs)
        assertEquals(7_000, clock.estimatedServerNowMs())
    }

    @Test
    fun ignoresInvalidAndFailedSamples() = runBlocking {
        val local = SequenceLocalClock(1_000, 900, 2_000, 2_010)
        val remote = object : RoomRemoteDataSource {
            private var call = 0
            override suspend fun getServerTime(): ServerTimeResponse {
                call += 1
                if (call == 1) return ServerTimeResponse(0)
                error("offline")
            }
        }
        val clock = ServerClock(RoomRepository(remote), local)

        clock.refresh(sampleCount = 2)

        assertEquals(0, clock.state.value.serverOffsetMs)
        assertNotNull(clock.state.value.error)
    }
}

private class SequenceLocalClock(vararg values: Long) : LocalClock {
    private val iterator = values.iterator()
    override fun nowMs(): Long = iterator.next()
}

private class SequenceTimeRemote(vararg times: Long) : RoomRemoteDataSource {
    private val iterator = times.iterator()
    override suspend fun getServerTime() = ServerTimeResponse(iterator.next())
}
