package com.synclisten.app.ui

import com.synclisten.app.data.RoomConnectionState
import com.synclisten.app.cache.CacheCleanup
import com.synclisten.app.cache.CacheSummary
import com.synclisten.app.data.RoomRepository
import com.synclisten.app.data.RoomSnapshot
import com.synclisten.app.data.RoomWebSocketClient
import com.synclisten.app.data.SettingsStore
import com.synclisten.app.transfer.DownloadQueueManager
import com.synclisten.app.playback.PlayerController
import com.synclisten.app.playback.ServerClock
import com.synclisten.app.playback.PlaybackSyncManager
import com.synclisten.app.playback.canControlPlayback
import com.synclisten.app.domain.model.MemberRole
import com.synclisten.app.data.RoomEvent
import com.synclisten.app.data.RepositoryResult
import com.synclisten.app.data.TrackPlaybackCommand
import com.synclisten.app.data.NextPlaybackCommand
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import com.synclisten.cloud.CloudTrackReference
import com.synclisten.cloud.CloudPrepareRequest
import com.synclisten.cloud.CloudReadyRequest
import com.synclisten.cloud.CloudCommitRequest

@Singleton
class RoomSessionController @Inject constructor(
    private val homeController: HomeController,
    private val settingsStore: SettingsStore,
    private val socketClient: RoomWebSocketClient,
    private val repository: RoomRepository,
    private val downloadQueueManager: DownloadQueueManager,
    private val cacheCleanup: CacheCleanup,
    private val playerController: PlayerController,
    private val serverClock: ServerClock,
    private val playbackSyncManager: PlaybackSyncManager,
) {
    suspend fun cloudLyrics(track: com.synclisten.cloud.CloudTrack): String {
        val room = home.value as? HomeState.InRoom ?: error("房间已关闭")
        return when (val result = repository.cloudLyrics(room.room.roomId, track)) {
            is RepositoryResult.Success -> result.value.text
            is RepositoryResult.Failure -> error(result.message)
        }
    }
    suspend fun searchCloud(source: String, keyword: String): List<com.synclisten.cloud.CloudTrack> {
        val room = home.value as? HomeState.InRoom ?: error("房间已关闭")
        return when (val result = repository.searchCloud(room.room.roomId, source, keyword)) {
            is RepositoryResult.Success -> result.value.tracks
            is RepositoryResult.Failure -> error(result.message)
        }
    }

    suspend fun resolveCloud(track: com.synclisten.cloud.CloudTrack): com.synclisten.cloud.CloudResolution {
        val room = home.value as? HomeState.InRoom ?: error("房间已关闭")
        return when (val result = repository.resolveCloud(room.room.roomId, track)) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Failure -> error(result.message)
        }
    }
    suspend fun addCloudTrack(reference: CloudTrackReference) {
        val room = home.value as? HomeState.InRoom ?: error("房间已关闭")
        when (val result = repository.addCloudTrack(room.room.roomId, reference)) {
            is RepositoryResult.Success -> Unit
            is RepositoryResult.Failure -> error(result.message)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val home = homeController.state
    val connection: StateFlow<RoomConnectionState> = socketClient.connection
    val snapshot: StateFlow<RoomSnapshot?> = socketClient.snapshot
    val downloads = downloadQueueManager.state
    val player = playerController.state
    val clock = serverClock.state
    val sync = playbackSyncManager.state
    private val mutableCacheSummary = MutableStateFlow(CacheSummary())
    val cacheSummary: StateFlow<CacheSummary> = mutableCacheSummary
    private val mutableControlError = MutableStateFlow<String?>(null)
    val controlError: StateFlow<String?> = mutableControlError
    private val retryCloud = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        playerController.cloudTrack = { trackId -> snapshot.value?.playlist?.firstOrNull { it.trackId == trackId }?.cloud }
        playerController.resolveCloud = { reference ->
            val session = home.value as? HomeState.InRoom ?: throw com.synclisten.cloud.CloudMusicException("房间已关闭")
            when (val result = repository.resolveCloud(session.room.roomId, reference.track)) {
                is RepositoryResult.Success -> result.value
                is RepositoryResult.Failure -> throw com.synclisten.cloud.CloudMusicException("云音乐解析失败，请检查网络后重试")
            }
        }
        scope.launch {
            home.collectLatest { state ->
                val session = state as? HomeState.InRoom ?: return@collectLatest
                coroutineScope {
                    playbackSyncManager.reset()
                    serverClock.reset()
                    var cloudPreparation: Job? = null
                    var cloudGeneration: Long? = null
                    var recoveryKey: Pair<String, Long?>? = null
                    var recoveryAttempted = false
                    var recoveryJob: Job? = null
                    fun recoverCloud(state: com.synclisten.app.domain.model.PlaybackState, manual: Boolean = false) {
                        val trackId = state.trackId ?: return
                        if (!state.isPlaying || snapshot.value?.playlist?.firstOrNull { it.trackId == trackId }?.cloud == null) return
                        val key = trackId to state.preparation?.generation
                        if (key != recoveryKey) { recoveryKey = key; recoveryAttempted = false }
                        if ((!manual && recoveryAttempted) || recoveryJob?.isActive == true) return
                        recoveryAttempted = true
                        recoveryJob = launch {
                            if (!serverClock.isCalibrated()) serverClock.refresh()
                            if (!serverClock.isCalibrated()) { mutableControlError.value = "时钟校准失败，请重试"; return@launch }
                            val target = state.positionMs + (serverClock.estimatedServerNowMs() - state.serverTimeMs).coerceAtLeast(0)
                            playerController.prepareCloudAt(trackId, target)
                            val current = socketClient.snapshot.value?.playbackState ?: return@launch
                            if (current.trackId == trackId && current.isPlaying && player.value.status == com.synclisten.app.playback.PlayerStatus.READY) {
                                playbackSyncManager.apply(current)
                            }
                        }
                    }
                    fun prepareCloud(state: com.synclisten.app.domain.model.PlaybackState, forceRetry: Boolean = false) {
                        val preparation = state.preparation ?: return
                        val userId = session.member.userId
                        if (preparation.committed || userId !in preparation.waiting) return
                        if (!forceRetry && cloudGeneration == preparation.generation) return
                        cloudPreparation?.cancel()
                        cloudGeneration = preparation.generation
                        cloudPreparation = launch {
                            playerController.prepareCloudAt(preparation.trackId, state.positionMs)
                            if (!serverClock.isCalibrated()) serverClock.refresh()
                            val current = socketClient.snapshot.value?.playbackState ?: return@launch
                            if (current.isPlaying) { playbackSyncManager.apply(current); return@launch }
                            if (current.preparation?.generation != preparation.generation) return@launch
                            val ready = serverClock.isCalibrated() && player.value.status == com.synclisten.app.playback.PlayerStatus.READY &&
                                kotlin.math.abs(playerController.currentPositionMs() - state.positionMs) <= 500
                            when (val result = repository.cloudReady(session.room.roomId, CloudReadyRequest(preparation.generation, preparation.trackId, ready))) {
                                is RepositoryResult.Success -> Unit
                                is RepositoryResult.Failure -> mutableControlError.value = result.message
                            }
                        }
                    }
                    try {
                        val settings = settingsStore.settings.first()
                        // Obtain a calibrated epoch before accepting the first scheduled PLAY.
                        serverClock.refresh()
                        socketClient.connect(settings.serverUrl, session.room.roomId,
                            session.member.userId, session.joinToken ?: return@coroutineScope)
                        launch {
                            socketClient.snapshot.filterNotNull().collect {
                                downloadQueueManager.sync(it, settings.serverUrl)
                                refreshCache()
                            }
                        }
                        launch { while (isActive) { delay(30_000); serverClock.refresh() } }
                        launch { while (isActive) { playbackSyncManager.checkpoint(); delay(500) } }
                        launch { retryCloud.collect { socketClient.snapshot.value?.playbackState?.let {
                            if (it.isPlaying) recoverCloud(it, manual = true) else prepareCloud(it, forceRetry = true)
                        } } }
                        launch { player.collect { current ->
                            if (current.isCloud && current.status == com.synclisten.app.playback.PlayerStatus.ERROR) {
                                socketClient.snapshot.value?.playbackState?.let { recoverCloud(it) }
                            }
                        } }
                        launch {
                            socketClient.connection.collect {
                                playbackSyncManager.setConnected(it is RoomConnectionState.Connected)
                                if (it is RoomConnectionState.Connected) downloadQueueManager.resume()
                                else { cloudPreparation?.cancel(); cloudGeneration = null }
                            }
                        }
                        launch {
                            socketClient.lastEvent.filterNotNull().collectLatest { event ->
                                when (event) {
                                    is RoomEvent.Snapshot -> {
                                        val state = socketClient.snapshot.value?.playbackState ?: event.value.playbackState
                                        if (state.preparation?.committed == false) prepareCloud(state) else playbackSyncManager.apply(state)
                                    }
                                    is RoomEvent.Playback -> playbackSyncManager.apply(event.value)
                                    is RoomEvent.CloudPrepare -> socketClient.snapshot.value?.playbackState?.let { prepareCloud(it) }
                                    is RoomEvent.CloudStatus -> socketClient.snapshot.value?.playbackState?.let { prepareCloud(it) }
                                    else -> Unit
                                }
                            }
                        }
                        awaitCancellation()
                    } finally {
                        socketClient.disconnect()
                        playbackSyncManager.reset()
                        playerController.release()
                    }
                }
            }
        }
    }

    fun canControl(): Boolean {
        val session = home.value as? HomeState.InRoom ?: return false
        return (snapshot.value?.members?.firstOrNull { it.userId == session.member.userId }?.role
            ?: session.member.role).canControlPlayback()
    }

    fun retryCloudPreparation() { retryCloud.tryEmit(Unit) }

    fun forceCloudPlayback() = control { session ->
        val generation = snapshot.value?.playbackState?.preparation?.generation
            ?: return@control RepositoryResult.Failure("当前没有等待中的云曲目")
        repository.commitCloud(session.room.roomId, CloudCommitRequest(generation, force = true))
    }

    fun togglePlayback() {
        if (snapshot.value?.playbackState?.isPlaying == true) hostPause()
        else (player.value.trackId ?: snapshot.value?.playlist?.firstOrNull()?.trackId)?.let(::hostPlay)
    }

    fun refreshCache() {
        scope.launch { mutableCacheSummary.value = cacheCleanup.summary() }
    }

    fun clearCache() {
        scope.launch {
            cacheCleanup.clearAllExcept(player.value.trackId)
            mutableCacheSummary.value = cacheCleanup.summary()
        }
    }

    fun prepareLocal(trackId: String) {
        scope.launch { playerController.prepare(trackId) }
    }

    fun playLocal() = playerController.play()

    fun pauseLocal() = playerController.pause()

    fun seekLocal(positionMs: Long) = playerController.seekTo(positionMs)

    fun hostPlay(trackId: String) = control { session ->
        val track = snapshot.value?.playlist?.firstOrNull { it.trackId == trackId }
        val position = if (player.value.trackId == trackId && player.value.status != com.synclisten.app.playback.PlayerStatus.ENDED &&
            (player.value.durationMs <= 0 || player.value.positionMs < player.value.durationMs)) player.value.positionMs else 0
        if (track?.cloud != null) repository.prepareCloud(session.room.roomId, CloudPrepareRequest(trackId, position))
        else repository.play(
            session.room.roomId,
            TrackPlaybackCommand(
                session.member.userId,
                trackId,
                position,
            ),
        )
    }

    fun hostPause() = controlCurrent { session, trackId ->
        repository.pause(
            session.room.roomId,
            TrackPlaybackCommand(session.member.userId, trackId, playerController.currentPositionMs()),
        )
    }

    fun hostSeek(positionMs: Long) = controlCurrent { session, trackId ->
        val target = if (player.value.durationMs > 0) positionMs.coerceIn(0, player.value.durationMs) else positionMs.coerceAtLeast(0)
        if (snapshot.value?.playlist?.firstOrNull { it.trackId == trackId }?.cloud != null) {
            repository.prepareCloud(session.room.roomId, CloudPrepareRequest(trackId, target, snapshot.value?.playbackState?.isPlaying == true))
        } else repository.seek(
            session.room.roomId,
            TrackPlaybackCommand(session.member.userId, trackId,
                target),
        )
    }

    fun hostNext() = control { session ->
        val current = snapshot.value
        val list = current?.playlist.orEmpty().sortedBy { it.orderIndex }
        val index = list.indexOfFirst { it.trackId == current?.playbackState?.trackId }
        val next = list.drop(index + 1).firstOrNull { it.status == com.synclisten.app.domain.model.TrackStatus.READY }
        if (next?.cloud != null) repository.prepareCloud(session.room.roomId, CloudPrepareRequest(next.trackId))
        else repository.next(session.room.roomId, NextPlaybackCommand(session.member.userId))
    }

    fun promoteToAdmin(memberUserId: String) = hostOnly { session ->
        repository.changeMemberRole(session.room.roomId, memberUserId, "ADMIN")
    }

    fun demoteFromAdmin(memberUserId: String) = hostOnly { session ->
        repository.changeMemberRole(session.room.roomId, memberUserId, "MEMBER")
    }

    fun removeTrack(trackId: String) = control { session ->
        repository.deleteTrack(session.room.roomId, trackId)
    }

    fun reorderPlaylist(orderedTrackIds: List<String>) = control { session ->
        repository.reorderPlaylist(session.room.roomId, orderedTrackIds)
    }

    fun leave(onComplete: () -> Unit) {
        scope.launch {
            val session = homeController.state.value as? HomeState.InRoom
            if (session != null) {
                repository.leaveRoom(session.room.roomId, session.member.userId)
                if (session.hostedLocally) homeController.stopHosting()
            }
            socketClient.disconnect()
            homeController.reset()
            onComplete()
        }
    }

    private fun controlCurrent(
        request: suspend (HomeState.InRoom, String) -> RepositoryResult<*>,
    ) {
        val trackId = player.value.trackId ?: snapshot.value?.playbackState?.trackId ?: return
        control { request(it, trackId) }
    }

    private fun control(request: suspend (HomeState.InRoom) -> RepositoryResult<*>) {
        scope.launch {
            val session = homeController.state.value as? HomeState.InRoom ?: return@launch
            val role = snapshot.value?.members?.firstOrNull { it.userId == session.member.userId }?.role ?: session.member.role
            if (!role.canControlPlayback()) {
                mutableControlError.value = "仅房主或管理员可执行播放控制"
                return@launch
            }
            mutableControlError.value = when (val result = request(session)) {
                is RepositoryResult.Success -> null
                is RepositoryResult.Failure -> result.message
            }
        }
    }

    private fun hostOnly(request: suspend (HomeState.InRoom) -> RepositoryResult<*>) {
        scope.launch {
            val session = homeController.state.value as? HomeState.InRoom ?: return@launch
            if (session.member.role != MemberRole.HOST) {
                mutableControlError.value = "仅房主可执行此操作"
                return@launch
            }
            mutableControlError.value = when (val result = request(session)) {
                is RepositoryResult.Success -> null
                is RepositoryResult.Failure -> result.message
            }
        }
    }
}
