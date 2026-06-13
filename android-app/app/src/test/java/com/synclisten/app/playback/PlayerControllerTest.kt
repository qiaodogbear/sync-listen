package com.synclisten.app.playback

import com.synclisten.app.cache.CacheDao
import com.synclisten.app.cache.CacheEntity
import com.synclisten.app.cache.VerifyStatus
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerControllerTest {
    @Test
    fun preparesOnlyVerifiedLocalCache() = runBlocking {
        val file = Files.createTempFile("player", ".mp3").toFile()
        val dao = PlayerFakeCacheDao(
            mutableListOf(
                cache("verified", file.path, VerifyStatus.VERIFIED),
                cache("pending", file.path, VerifyStatus.PENDING),
            ),
        )
        val engine = FakePlayerEngine()
        val controller = PlayerController(dao, engine)

        controller.prepare("pending")
        assertEquals(PlayerStatus.WAITING_FOR_CACHE, controller.state.value.status)
        assertNull(engine.loadedPath)

        controller.prepare("verified")
        assertEquals(file.path, engine.loadedPath)
        assertEquals("verified", controller.state.value.trackId)
    }

    @Test
    fun exposesPlaybackCommandsAndEngineEvents() = runBlocking {
        val file = Files.createTempFile("player", ".flac").toFile()
        val engine = FakePlayerEngine()
        val controller = PlayerController(
            PlayerFakeCacheDao(mutableListOf(cache("track", file.path, VerifyStatus.VERIFIED))),
            engine,
        )

        controller.prepare("track")
        engine.emit(PlayerEngineEvent.Ready(durationMs = 5_000))
        controller.play()
        controller.seekTo(1_250)
        controller.pause()

        assertEquals(listOf("play", "seek:1250", "pause"), engine.commands)
        assertEquals(PlayerStatus.PAUSED, controller.state.value.status)
        assertEquals(1_250, controller.state.value.positionMs)
        assertEquals(5_000, controller.state.value.durationMs)

        engine.emit(PlayerEngineEvent.Ended)
        assertEquals(PlayerStatus.ENDED, controller.state.value.status)
        engine.emit(PlayerEngineEvent.Error("decode failed"))
        assertEquals(PlayerStatus.ERROR, controller.state.value.status)
        assertEquals("decode failed", controller.state.value.error)
    }

    @Test
    fun queuesPlayWhileMediaIsPreparing() = runBlocking {
        val file = Files.createTempFile("player", ".mp3").toFile()
        val engine = FakePlayerEngine()
        val controller = PlayerController(
            PlayerFakeCacheDao(mutableListOf(cache("track", file.path, VerifyStatus.VERIFIED))),
            engine,
        )

        controller.prepare("track")
        controller.play()

        assertEquals(listOf("play"), engine.commands)
        assertEquals(PlayerStatus.PLAYING, controller.state.value.status)
    }

    @Test
    fun exposesPlaybackSpeedHookForLaterDriftCorrection() {
        val engine = FakePlayerEngine()
        val controller = PlayerController(PlayerFakeCacheDao(mutableListOf()), engine)

        controller.setPlaybackSpeed(1.02f)

        assertEquals(listOf("speed:1.02"), engine.commands)
    }

    private fun cache(trackId: String, path: String, status: VerifyStatus) = CacheEntity(
        trackId, "hash-$trackId", path, "$trackId.mp3", 1, 5_000, 1, status, "room",
    )
}

private class FakePlayerEngine : PlayerEngine {
    var loadedPath: String? = null
    val commands = mutableListOf<String>()
    private var listener: (PlayerEngineEvent) -> Unit = {}

    override fun setEventListener(listener: (PlayerEngineEvent) -> Unit) {
        this.listener = listener
    }

    override fun load(localPath: String) {
        loadedPath = localPath
    }

    override fun play() {
        commands += "play"
    }

    override fun pause() {
        commands += "pause"
    }

    override fun seekTo(positionMs: Long) {
        commands += "seek:$positionMs"
    }

    override fun setPlaybackSpeed(speed: Float) {
        commands += "speed:$speed"
    }

    override fun release() = Unit

    fun emit(event: PlayerEngineEvent) = listener(event)
}

private class PlayerFakeCacheDao(private val entries: MutableList<CacheEntity>) : CacheDao {
    override suspend fun upsert(entity: CacheEntity) {
        entries.removeIf { it.trackId == entity.trackId }
        entries.add(entity)
    }
    override suspend fun findByTrackId(trackId: String) = entries.firstOrNull { it.trackId == trackId }
    override suspend fun findByHash(fileHash: String) = entries.filter { it.fileHash == fileHash }
    override suspend fun findByRoom(roomId: String) = entries.filter { it.roomId == roomId }
    override suspend fun all() = entries.toList()
    override suspend fun updateVerifyStatus(trackId: String, status: VerifyStatus) = Unit
    override suspend fun deleteByTrackId(trackId: String) { entries.removeIf { it.trackId == trackId } }
    override suspend fun deleteByHash(fileHash: String) { entries.removeIf { it.fileHash == fileHash } }
    override suspend fun totalPhysicalBytes() = entries.distinctBy { it.fileHash }.sumOf { it.fileSize }
}
