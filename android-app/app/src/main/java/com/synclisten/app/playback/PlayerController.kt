package com.synclisten.app.playback

import com.synclisten.app.cache.CacheDao
import com.synclisten.app.cache.VerifyStatus
import com.synclisten.app.util.AppLogger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class PlayerStatus {
    IDLE,
    WAITING_FOR_CACHE,
    PREPARING,
    BUFFERING,
    READY,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR,
}

data class PlayerControllerState(
    val trackId: String? = null,
    val status: PlayerStatus = PlayerStatus.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
    val localFilePath: String? = null,
    val bufferedMs: Long = 0,
    val audioSessionId: Int = 0,
)

sealed interface PlayerEngineEvent {
    data class Ready(val durationMs: Long) : PlayerEngineEvent
    data class Position(val positionMs: Long, val durationMs: Long) : PlayerEngineEvent
    data object Ended : PlayerEngineEvent
    data object Buffering : PlayerEngineEvent
    data class Error(val message: String) : PlayerEngineEvent
}

interface PlayerEngine {
    fun setEventListener(listener: (PlayerEngineEvent) -> Unit)
    fun load(localPath: String)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun release()
}

interface PlaybackPort {
    val state: StateFlow<PlayerControllerState>
    suspend fun prepare(trackId: String)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
}

@Singleton
class PlayerController @Inject constructor(
    private val cacheDao: CacheDao,
    private val engine: PlayerEngine,
) : PlaybackPort {
    private val mutableState = MutableStateFlow(PlayerControllerState())
    override val state: StateFlow<PlayerControllerState> = mutableState
    private var pendingReady: CompletableDeferred<Unit>? = null

    init {
        engine.setEventListener(::onEngineEvent)
    }

    override suspend fun prepare(trackId: String) {
        val cache = cacheDao.findByTrackId(trackId)
        if (cache?.verifyStatus != VerifyStatus.VERIFIED || !File(cache.localPath).isFile) {
            mutableState.value = PlayerControllerState(trackId, PlayerStatus.WAITING_FOR_CACHE)
            AppLogger.debug("Player", "waiting for verified cache track=$trackId")
            return
        }
        mutableState.value = PlayerControllerState(trackId, PlayerStatus.PREPARING)
        AppLogger.debug("Player", "prepare track=$trackId path=${cache.localPath}")
        val ready = CompletableDeferred<Unit>()
        pendingReady?.cancel()
        pendingReady = ready
        engine.load(cache.localPath)
        ready.await()
    }

    override fun play() {
        if (mutableState.value.status !in playableStatuses) return
        engine.play()
        update(status = PlayerStatus.PLAYING)
    }

    override fun pause() {
        if (mutableState.value.status != PlayerStatus.PLAYING) return
        engine.pause()
        update(status = PlayerStatus.PAUSED)
    }

    override fun seekTo(positionMs: Long) {
        if (mutableState.value.trackId == null) return
        val target = positionMs.coerceIn(0, mutableState.value.durationMs.coerceAtLeast(positionMs))
        engine.seekTo(target)
        update(positionMs = target)
    }

    override fun setPlaybackSpeed(speed: Float) {
        engine.setPlaybackSpeed(speed)
    }

    fun release() {
        pendingReady?.cancel()
        pendingReady = null
        engine.release()
        mutableState.value = PlayerControllerState()
    }

    private fun onEngineEvent(event: PlayerEngineEvent) {
        when (event) {
            is PlayerEngineEvent.Ready -> {
                update(
                    status = if (mutableState.value.status == PlayerStatus.PLAYING) {
                        PlayerStatus.PLAYING
                    } else {
                        PlayerStatus.READY
                    },
                    durationMs = event.durationMs,
                    error = null,
                )
                pendingReady?.complete(Unit)
                pendingReady = null
            }
            is PlayerEngineEvent.Position -> update(
                positionMs = event.positionMs,
                durationMs = event.durationMs,
            )
            PlayerEngineEvent.Buffering -> update(status = PlayerStatus.BUFFERING)
            PlayerEngineEvent.Ended -> update(status = PlayerStatus.ENDED)
            is PlayerEngineEvent.Error -> {
                update(status = PlayerStatus.ERROR, error = event.message)
                pendingReady?.complete(Unit)
                pendingReady = null
            }
        }
    }

    private fun update(
        status: PlayerStatus = mutableState.value.status,
        positionMs: Long = mutableState.value.positionMs,
        durationMs: Long = mutableState.value.durationMs,
        error: String? = mutableState.value.error,
    ) {
        mutableState.value = mutableState.value.copy(
            status = status,
            positionMs = positionMs,
            durationMs = durationMs,
            error = error,
        )
        AppLogger.debug("Player", "track=${mutableState.value.trackId} status=$status position=$positionMs")
    }

    private companion object {
        val playableStatuses = setOf(
            PlayerStatus.PREPARING,
            PlayerStatus.READY,
            PlayerStatus.PAUSED,
            PlayerStatus.ENDED,
        )
    }
}
