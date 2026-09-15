package com.synclisten.shared

import com.synclisten.shared.playback.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerStateTest {
    @Test fun pausedSeekFromEndedStillPausesEngineAndSurvivesReady() = runBlocking {
        val file = Files.createTempFile("player-state", ".wav").toFile()
        var events: (PlayerEngineEvent) -> Unit = {}
        var pauses = 0
        val engine = object : PlayerEngine {
            override fun setEventListener(listener: (PlayerEngineEvent) -> Unit) { events = listener }
            override fun load(localPath: String) { events(PlayerEngineEvent.Ready(5_000)) }
            override fun play() = Unit
            override fun pause() { pauses++ }
            override fun seekTo(positionMs: Long) = Unit
            override fun setPlaybackSpeed(speed: Float) = Unit
            override fun release() = Unit
        }
        try {
            val controller = PlayerController(engine, object : CacheFileResolver {
                override fun resolvePath(trackId: String) = file.path
            })
            controller.prepare("track")
            events(PlayerEngineEvent.Ended)
            controller.seekTo(1_000)
            controller.pause()
            events(PlayerEngineEvent.Ready(5_000))
            assertEquals(1, pauses)
            assertEquals(PlayerStatus.PAUSED, controller.state.value.status)
            assertEquals(1_000L, controller.state.value.positionMs)
        } finally { file.delete() }
    }
}
