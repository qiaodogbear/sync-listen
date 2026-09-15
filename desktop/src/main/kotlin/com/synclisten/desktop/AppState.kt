package com.synclisten.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.synclisten.desktop.audio.DesktopCacheResolver
import com.synclisten.desktop.audio.DesktopPlayerEngine
import com.synclisten.shared.data.*
import com.synclisten.shared.playback.*
import com.synclisten.shared.util.AppLogger
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

enum class Screen {
    Home,
    Create,
    Join,
    Room,
    Settings,
}

class DesktopAppState {
    var busy: Boolean by mutableStateOf(false)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main +
        CoroutineExceptionHandler { _, error -> roomError = error.message ?: "操作失败"; busy = false })
    private var sessionScope: CoroutineScope? = null
    private fun report(result: RepositoryResult<*>) {
        roomError = (result as? RepositoryResult.Failure)?.message
    }

    fun close() {
        disconnectWebSocket()
        playerController.release()
        scope.cancel()
        identityScope.cancel()
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
    }

    // --- Navigation ---
    var currentScreen: Screen by mutableStateOf(Screen.Home)
    private var previousScreen: Screen? = null

    // --- User Identity ---
    val settings: FileSettingsStore = FileSettingsStore(
        File(System.getProperty("user.home") + File.separator + "AppData" + File.separator +
             "Roaming" + File.separator + "SyncListen" + File.separator + "settings.json")
    )
    val identityManager: IdentityManager = IdentityManager(settings)
    private var _cachedIdentity: AppSettings? = null
    private val identityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        identityScope.launch {
            _cachedIdentity = identityManager.ensureIdentity()
        }
    }

    fun getIdentity(): AppSettings {
        return _cachedIdentity ?: kotlinx.coroutines.runBlocking { identityManager.ensureIdentity() }
    }

    // --- HTTP ---
    val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(DeviceAuthInterceptor(settings))
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .readTimeout(java.time.Duration.ofSeconds(30))
        .build()

    private val apiFactory = RetrofitApiFactory(okHttpClient, kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
    })

    // --- Room Session ---
    var serverUrl: String by mutableStateOf("http://localhost:3000")
    var roomRepo: RoomRepository? by mutableStateOf(null)
        private set
    var webSocket: RoomWebSocketClient? by mutableStateOf(null)
        private set
    var roomSnapshot: RoomSnapshot? by mutableStateOf(null)
    var joinToken: String by mutableStateOf("")
        private set
    var member: com.synclisten.shared.domain.model.Member? by mutableStateOf(null)
        private set
    var roomError: String? by mutableStateOf(null)

    // --- Audio ---
    val playerEngine: DesktopPlayerEngine = DesktopPlayerEngine()
    val cacheResolver: DesktopCacheResolver = DesktopCacheResolver(
        File(System.getProperty("user.home"), "AppData/Roaming/SyncListen/cache")
    )
    val playerController: PlayerController = PlayerController(playerEngine, cacheResolver)

    // --- Clock & Sync ---
    // These require a connected RoomRepository, so they're initialized lazily
    private var _serverClock: ServerClock? = null
    private var _syncManager: PlaybackSyncManager? = null

    fun getServerClock(): ServerClock {
        val repo = roomRepo ?: error("Not connected to a room")
        return _serverClock ?: ServerClock(repo, LocalClock { System.currentTimeMillis() }).also {
            _serverClock = it
        }
    }

    fun getSyncManager(): PlaybackSyncManager {
        return _syncManager ?: PlaybackSyncManager(playerController, getServerClock(), speedCorrectionEnabled = false).also {
            _syncManager = it
        }
    }

    private fun resetRoomState() {
        _serverClock = null
        _syncManager = null
    }

    // --- Room Operations ---
    fun createRoom(name: String, displayName: String) {
        if (busy || roomSnapshot != null) return
        busy = true
        roomError = null
        val identity = getIdentity()
        // Read serverUrl from settings to ensure consistency
        val storedUrl = kotlinx.coroutines.runBlocking { settings.settings.first() }.serverUrl
        val url = storedUrl.ifBlank { this.serverUrl }
        this.serverUrl = url
        val repo = RoomRepository(
            RetrofitRoomRemoteDataSource(MemorySettingsStore(AppSettings(serverUrl = url)), apiFactory)
        )
        roomRepo = repo
        resetRoomState()

        scope.launch {
            settings.update(displayName = displayName.trim(), recentNickname = displayName.trim())
            when (val result = repo.createRoom(name, identity.userId, displayName)) {
                is RepositoryResult.Success -> {
                    val resp = result.value
                    serverUrl = url
                    roomSnapshot = RoomSnapshot(
                        room = resp.room,
                        members = listOf(resp.member),
                        playlist = emptyList(),
                        playbackState = com.synclisten.shared.domain.model.PlaybackState(
                            trackId = null, positionMs = 0, isPlaying = false, serverTimeMs = 0
                        ),
                    )
                    member = resp.member
                    joinToken = resp.joinToken
                    connectWebSocket(url, resp.room.roomId, identity.userId, resp.joinToken)
                    // Don't auto-navigate — CreateScreen shows room code first
                }
                is RepositoryResult.Failure -> {
                    roomError = result.message
                }
            }
            busy = false
        }
    }

    fun joinRoom(serverUrlInput: String, roomCode: String, displayName: String) {
        if (busy || roomSnapshot != null) return
        busy = true
        roomError = null
        val identity = getIdentity()
        val url = normalizeServerUrl(serverUrlInput.ifBlank { "http://localhost:3000" })
        serverUrl = url
        resetRoomState()

        // Update settings with the entered server URL
        scope.launch {
            settings.update(serverUrl = url)
        }

        val repo = RoomRepository(
            RetrofitRoomRemoteDataSource(MemorySettingsStore(AppSettings(serverUrl = url)), apiFactory)
        )
        roomRepo = repo

        scope.launch {
            settings.update(displayName = displayName.trim(), recentNickname = displayName.trim())
            val result = repo.joinRoom(roomCode, identity.userId, displayName)
            when (result) {
                is RepositoryResult.Success -> {
                    val resp = result.value
                    serverUrl = url
                    roomSnapshot = RoomSnapshot(
                        room = resp.room,
                        members = listOf(resp.member),
                        playlist = emptyList(),
                        playbackState = com.synclisten.shared.domain.model.PlaybackState(
                            trackId = null, positionMs = 0, isPlaying = false, serverTimeMs = 0
                        ),
                    )
                    member = resp.member
                    joinToken = resp.joinToken
                    connectWebSocket(url, resp.room.roomId, identity.userId, resp.joinToken)
                    currentScreen = Screen.Room
                }
                is RepositoryResult.Failure -> {
                    roomError = result.message
                }
            }
            busy = false
        }
    }

    fun joinRoomByLink(serverUrlInput: String, roomId: String, token: String, displayName: String) {
        if (busy || roomSnapshot != null) return
        busy = true
        roomError = null
        val identity = getIdentity()
        val url = normalizeServerUrl(serverUrlInput.ifBlank { "http://localhost:3000" })
        serverUrl = url
        resetRoomState()

        scope.launch {
            settings.update(serverUrl = url)
        }

        val repo = RoomRepository(
            RetrofitRoomRemoteDataSource(MemorySettingsStore(AppSettings(serverUrl = url)), apiFactory)
        )
        roomRepo = repo

        scope.launch {
            settings.update(displayName = displayName.trim(), recentNickname = displayName.trim())
            when (val result = repo.joinRoomByLink(roomId, token, identity.userId, displayName)) {
                is RepositoryResult.Success -> {
                    val resp = result.value
                    serverUrl = url
                    roomSnapshot = RoomSnapshot(
                        room = resp.room,
                        members = listOf(resp.member),
                        playlist = emptyList(),
                        playbackState = com.synclisten.shared.domain.model.PlaybackState(
                            trackId = null, positionMs = 0, isPlaying = false, serverTimeMs = 0
                        ),
                    )
                    member = resp.member
                    joinToken = resp.joinToken
                    connectWebSocket(url, resp.room.roomId, identity.userId, resp.joinToken)
                    currentScreen = Screen.Room
                }
                is RepositoryResult.Failure -> {
                    roomError = result.message
                }
            }
            busy = false
        }
    }

    fun leaveRoom() {
        val repo = roomRepo ?: return
        val snapshot = roomSnapshot ?: return
        val identity = getIdentity()

        scope.launch {
            repo.leaveRoom(snapshot.room.roomId, identity.userId)
        }
        disconnectWebSocket()
        roomRepo = null
        roomSnapshot = null
        member = null
        joinToken = ""
        roomError = null
        resetRoomState()
        playerController.release()
        currentScreen = Screen.Home
    }

    private fun connectWebSocket(serverUrl: String, roomId: String, userId: String, token: String) {
        disconnectWebSocket()
        val ws = RoomWebSocketClient(
            okHttpClient,
            MutableStateFlow(true), // Desktop always has network
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        )
        webSocket = ws
        ws.connect(serverUrl, roomId, userId, token)

        val activeScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main)
        sessionScope = activeScope
        activeScope.launch {
            while (isActive) {
                getServerClock().refresh()
                delay(30_000)
            }
        }
        activeScope.launch {
            ws.snapshot.collect { snapshot ->
                if (snapshot != null) {
                    roomSnapshot = snapshot
                    member = snapshot.members.firstOrNull { it.userId == userId }
                }
            }
        }
        activeScope.launch {
            ws.lastEvent.filterNotNull().collectLatest { event ->
                when (event) {
                    is RoomEvent.Snapshot -> getSyncManager().apply(event.value.playbackState)
                    is RoomEvent.Playback -> getSyncManager().apply(event.value)
                    else -> Unit
                }
            }
        }
        activeScope.launch {
            ws.snapshot.filterNotNull().map { it.playlist to it.playbackState.trackId }.distinctUntilChanged().collectLatest { (tracks, current) ->
                val ordered = tracks.sortedBy { if (it.trackId == current) -1 else it.orderIndex }
                for (track in ordered) {
                    try {
                        cacheResolver.ensureDownloaded(track, serverUrl, okHttpClient)
                        if (ws.snapshot.value?.playbackState?.trackId == track.trackId) {
                            getSyncManager().apply(ws.snapshot.value!!.playbackState)
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { roomError = "下载失败：${track.title}，${error.message}" }
                }
            }
        }
    }

    private fun disconnectWebSocket() {
        sessionScope?.cancel()
        sessionScope = null
        webSocket?.disconnect()
        webSocket = null
    }

    // --- Playback Control ---
    fun playTrack(trackId: String) {
        val repo = roomRepo ?: return
        val roomId = roomSnapshot?.room?.roomId ?: return
        val userId = getIdentity().userId
        scope.launch {
            val state = playerController.state.value
            val position = if (state.trackId == trackId &&
                state.status != com.synclisten.shared.playback.PlayerStatus.ENDED &&
                (state.durationMs <= 0 || state.positionMs < state.durationMs)
            ) state.positionMs else 0
            report(repo.play(roomId, TrackPlaybackCommand(userId, trackId, position)))
        }
    }

    fun pausePlayback() {
        val repo = roomRepo ?: return
        val roomId = roomSnapshot?.room?.roomId ?: return
        val userId = getIdentity().userId
        val currentTrack = roomSnapshot?.playbackState?.trackId ?: return
        val position = playerController.state.value.positionMs
        scope.launch {
            report(repo.pause(roomId, TrackPlaybackCommand(userId, currentTrack, position)))
        }
    }

    fun seekTo(positionMs: Long) {
        val repo = roomRepo ?: return
        val roomId = roomSnapshot?.room?.roomId ?: return
        val userId = getIdentity().userId
        val currentTrack = roomSnapshot?.playbackState?.trackId ?: return
        scope.launch {
            val duration = playerController.state.value.durationMs
            val target = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
            report(repo.seek(roomId, TrackPlaybackCommand(userId, currentTrack, target)))
        }
    }

    fun previousTrack() {
        val playlist = roomSnapshot?.playlist ?: return
        val currentTrackId = roomSnapshot?.playbackState?.trackId ?: return
        val currentIndex = playlist.indexOfFirst { it.trackId == currentTrackId }
        if (currentIndex > 0) {
            playTrack(playlist[currentIndex - 1].trackId)
        }
    }

    fun nextTrack() {
        val repo = roomRepo ?: return
        val roomId = roomSnapshot?.room?.roomId ?: return
        val userId = getIdentity().userId
        scope.launch {
            report(repo.next(roomId, NextPlaybackCommand(userId)))
        }
    }

    // --- Admin / Playlist ---
    fun promoteToAdmin(memberUserId: String) {
        val repo = roomRepo ?: return
        val roomId = roomSnapshot?.room?.roomId ?: return
        scope.launch {
            report(repo.changeMemberRole(roomId, memberUserId, "ADMIN"))
        }
    }

    fun demoteFromAdmin(memberUserId: String) {
        val repo = roomRepo ?: return
        val roomId = roomSnapshot?.room?.roomId ?: return
        scope.launch {
            report(repo.changeMemberRole(roomId, memberUserId, "MEMBER"))
        }
    }

    fun removeTrack(trackId: String) {
        val repo = roomRepo ?: return
        val roomId = roomSnapshot?.room?.roomId ?: return
        scope.launch {
            report(repo.deleteTrack(roomId, trackId))
        }
    }

    fun reorderPlaylist(orderedTrackIds: List<String>) {
        val repo = roomRepo ?: return
        val roomId = roomSnapshot?.room?.roomId ?: return
        scope.launch {
            report(repo.reorderPlaylist(roomId, orderedTrackIds))
        }
    }

    // --- Navigation ---
    fun navigateTo(screen: Screen) {
        previousScreen = currentScreen
        currentScreen = screen
    }

    fun goBack() {
        if (previousScreen != null) {
            currentScreen = previousScreen!!
            previousScreen = null
        } else {
            currentScreen = Screen.Home
        }
    }

    // --- Utility ---
    fun canControlPlayback(): Boolean {
        val role = member?.role ?: return false
        return role == com.synclisten.shared.domain.model.MemberRole.HOST ||
                role == com.synclisten.shared.domain.model.MemberRole.ADMIN
    }

    fun isHost(): Boolean =
        member?.role == com.synclisten.shared.domain.model.MemberRole.HOST

    /** Get settings as a StateFlow-compatible value for compose collectAsState */
    fun getSettingsFlow(): kotlinx.coroutines.flow.Flow<AppSettings> = settings.settings
}
