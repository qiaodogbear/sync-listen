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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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

    init {
        scope.launch {
            home.collectLatest { state ->
                val session = state as? HomeState.InRoom ?: return@collectLatest
                coroutineScope {
                    playbackSyncManager.reset()
                    serverClock.reset()
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
                        launch {
                            socketClient.connection.collect {
                                playbackSyncManager.setConnected(it is RoomConnectionState.Connected)
                                if (it is RoomConnectionState.Connected) downloadQueueManager.resume()
                            }
                        }
                        launch {
                            socketClient.lastEvent.filterNotNull().collectLatest { event ->
                                when (event) {
                                    is RoomEvent.Snapshot -> playbackSyncManager.apply(event.value.playbackState)
                                    is RoomEvent.Playback -> playbackSyncManager.apply(event.value)
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
        repository.play(
            session.room.roomId,
            TrackPlaybackCommand(
                session.member.userId,
                trackId,
                if (player.value.trackId == trackId &&
                    player.value.status != com.synclisten.app.playback.PlayerStatus.ENDED &&
                    (player.value.durationMs <= 0 || player.value.positionMs < player.value.durationMs)
                ) player.value.positionMs else 0,
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
        repository.seek(
            session.room.roomId,
            TrackPlaybackCommand(session.member.userId, trackId,
                if (player.value.durationMs > 0) positionMs.coerceIn(0, player.value.durationMs)
                else positionMs.coerceAtLeast(0)),
        )
    }

    fun hostNext() = control { session ->
        repository.next(session.room.roomId, NextPlaybackCommand(session.member.userId))
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
