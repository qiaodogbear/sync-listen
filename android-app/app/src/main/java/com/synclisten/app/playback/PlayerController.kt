package com.synclisten.app.playback

import com.synclisten.app.cache.CacheDao
import com.synclisten.app.cache.VerifyStatus
import com.synclisten.app.util.AppLogger
import com.synclisten.cloud.CloudMusicException
import com.synclisten.cloud.CloudResolution
import com.synclisten.cloud.CloudTrackReference
import com.synclisten.cloud.selectCandidate
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
    val isCloud: Boolean = false,
)

sealed interface PlayerEngineEvent {
    data class Ready(val durationMs: Long) : PlayerEngineEvent
    data class Position(val positionMs: Long, val durationMs: Long, val bufferedMs: Long = 0) : PlayerEngineEvent
    data object Ended : PlayerEngineEvent
    data object Buffering : PlayerEngineEvent
    data class Error(val message: String) : PlayerEngineEvent
}

interface PlayerEngine {
    fun currentPositionMs(): Long? = null
    fun setEventListener(listener: (PlayerEngineEvent) -> Unit)
    fun load(localPath: String)
    fun loadAt(path: String, positionMs: Long) { load(path); if (positionMs > 0) seekTo(positionMs) }
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun release()
}

interface PlaybackPort {
    fun currentPositionMs(): Long = state.value.positionMs
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
    var cloudTrack: (String) -> CloudTrackReference? = { null }
    var resolveCloud: suspend (CloudTrackReference) -> CloudResolution = { throw CloudMusicException("云音乐服务不可用") }

    init {
        engine.setEventListener(::onEngineEvent)
    }

    override suspend fun prepare(trackId: String) {
        val reference = cloudTrack(trackId)
        if (reference != null) {
            prepareCloud(trackId, reference, 0)
            return
        }
        val cache = cacheDao.findByTrackId(trackId)
        if (cache?.verifyStatus != VerifyStatus.VERIFIED || !File(cache.localPath).isFile) {
            mutableState.value = PlayerControllerState(trackId, PlayerStatus.WAITING_FOR_CACHE)
            AppLogger.debug("Player", "waiting for verified cache track=$trackId")
            return
        }
        mutableState.value = PlayerControllerState(trackId, PlayerStatus.PREPARING, localFilePath = cache.localPath)
        AppLogger.debug("Player", "prepare track=$trackId path=${cache.localPath}")
        val ready = CompletableDeferred<Unit>()
        pendingReady?.cancel()
        pendingReady = ready
        engine.load(cache.localPath)
        try {
            kotlinx.coroutines.withTimeout(15_000) { ready.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            update(status = PlayerStatus.ERROR, error = "音频准备超时，请检查文件格式")
        }
    }

    suspend fun prepareCloudAt(trackId: String, positionMs: Long) {
        val reference = cloudTrack(trackId) ?: run {
            mutableState.value = PlayerControllerState(trackId, PlayerStatus.ERROR, error = "云曲目信息不可用")
            return
        }
        prepareCloud(trackId, reference, positionMs.coerceAtLeast(0))
    }

    private suspend fun prepareCloud(trackId: String, reference: CloudTrackReference, positionMs: Long) {
        pendingReady?.cancel()
        pendingReady = null
        engine.pause()
        repeat(2) { attempt ->
            mutableState.value = PlayerControllerState(trackId, PlayerStatus.PREPARING, isCloud = true)
            val resolution = try {
                resolveCloud(reference)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) {
                if (attempt == 0) return@repeat
                update(status = PlayerStatus.ERROR, error = "云音乐解析失败，请重试"); return
            }
            val candidate = try { reference.selectCandidate(resolution) } catch (error: CloudMusicException) {
                update(status = PlayerStatus.ERROR, error = error.message ?: "云音乐解析失败"); return
            }
            val ready = CompletableDeferred<Unit>()
            pendingReady?.cancel()
            pendingReady = ready
            engine.loadAt(candidate.url, positionMs)
            try {
                val completed = kotlinx.coroutines.withTimeoutOrNull(15_000) { ready.await(); true } ?: false
                if (!completed) update(status = PlayerStatus.ERROR, error = "音频缓冲超时，请重试")
            } finally {
                if (pendingReady === ready) pendingReady = null
            }
            if (state.value.status == PlayerStatus.READY) return
            if (attempt == 1) return
        }
    }

    override fun currentPositionMs(): Long = engine.currentPositionMs() ?: state.value.positionMs

    override fun play() {
        if (pendingReady != null || mutableState.value.status !in playableStatuses) return
        engine.play()
        update(status = PlayerStatus.PLAYING)
    }

    override fun pause() {
        if (mutableState.value.trackId == null || mutableState.value.status in
            setOf(PlayerStatus.IDLE, PlayerStatus.WAITING_FOR_CACHE, PlayerStatus.ERROR)) return
        engine.pause()
        if (mutableState.value.status !in setOf(PlayerStatus.PREPARING, PlayerStatus.BUFFERING)) {
            update(status = PlayerStatus.PAUSED)
        }
    }

    override fun seekTo(positionMs: Long) {
        if (mutableState.value.trackId == null) return
        val duration = mutableState.value.durationMs
        val target = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
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
                    status = if (mutableState.value.status in setOf(PlayerStatus.PLAYING, PlayerStatus.PAUSED)) {
                        mutableState.value.status
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
                bufferedMs = event.bufferedMs,
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
        bufferedMs: Long = mutableState.value.bufferedMs,
    ) {
        mutableState.value = mutableState.value.copy(
            status = status,
            positionMs = positionMs,
            durationMs = durationMs,
            error = error,
            bufferedMs = bufferedMs,
        )
        AppLogger.debug("Player", "track=${mutableState.value.trackId} status=$status position=$positionMs")
    }

    private companion object {
        val playableStatuses = setOf(
            PlayerStatus.READY,
            PlayerStatus.PAUSED,
            PlayerStatus.ENDED,
        )
    }
}
