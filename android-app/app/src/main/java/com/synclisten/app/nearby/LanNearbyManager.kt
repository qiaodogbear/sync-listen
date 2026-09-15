package com.synclisten.app.nearby

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import com.synclisten.app.data.SettingsStore
import com.synclisten.app.ui.HomeController
import com.synclisten.app.ui.HomeState
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class NearbyState(
    val enabled: Boolean = false,
    val active: Boolean = false,
    val peers: List<NearbyPeer> = emptyList(),
    val message: String? = null,
)

@Singleton
class LanNearbyManager @Inject constructor(
    @ApplicationContext context: Context,
    private val home: HomeController,
    settings: SettingsStore,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycle = Mutex()
    private val networkSlots = Semaphore(4)
    // Deliberately separate from authenticated room clients: never send device credentials to peers.
    private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableState = MutableStateFlow(NearbyState())
    val state: StateFlow<NearbyState> = mutableState
    val gate = NearbyInvitationGate(SystemClock::elapsedRealtime) {
        !foreground || !state.value.enabled || home.state.value is HomeState.InRoom || home.state.value is HomeState.Loading
    }
    val pending = gate.pending
    @Volatile private var foreground = false
    private var generation = 0
    @Volatile private var id = UUID.randomUUID().toString()
    @Volatile private var nickname = "听友"
    private var engine: EmbeddedServer<*, *>? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val services = mutableSetOf<String>()
    private val resolving = ArrayDeque<NsdServiceInfo>()
    private var resolverBusy = false

    init {
        scope.launch { settings.settings.collect { nickname = it.displayName.ifBlank { it.recentNickname }.filterNot(Char::isISOControl).ifBlank { "听友" }.take(40) } }
        scope.launch {
            while (isActive) {
                delay(10_000)
                gate.expire()
                val gen = generation
                if (state.value.active) {
                    mutableState.value = state.value.copy(peers = state.value.peers.filter {
                        SystemClock.elapsedRealtime() - it.lastSeenAtMs < 30_000
                    })
                    services.toList().forEach { name ->
                        if (resolving.none { it.serviceName == name }) resolving.addLast(NsdServiceInfo().apply {
                            serviceName = name
                            serviceType = SERVICE_TYPE
                        })
                    }
                    resolveNext(gen)
                }
            }
        }
    }

    fun setForeground(value: Boolean) { foreground = value; reconcile() }
    fun setEnabled(value: Boolean) {
        mutableState.value = state.value.copy(enabled = value, message = null)
        reconcile()
    }
    fun dismissInvitation() = gate.clear()
    fun clearMessage() { mutableState.value = state.value.copy(message = null) }
    fun message(text: String) { mutableState.value = state.value.copy(message = text) }

    fun currentProfile(): NearbyProfile {
        val room = home.state.value as? HomeState.InRoom
        val advertised = room?.let { NearbyRoom(it.room.roomCode, it.inviteServerUrl, it.room.name.take(40)) }
            ?.takeIf(NearbyValidation::room)
        return NearbyProfile(id, nickname, advertised)
    }

    fun invite(peer: NearbyPeer) {
        val room = currentProfile().room ?: run { message("请先创建或加入一个局域网房间"); return }
        val invite = NearbyInvitation(UUID.randomUUID().toString(), nickname, room)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    client.newCall(Request.Builder().url(peer.endpoint + "/nearby/invite")
                        .header(NEARBY_HEADER, peer.nonce)
                        .post(json.encodeToString(invite).toRequestBody("application/json".toMediaType())).build())
                        .execute().use { it.code }
                }.getOrNull()
            }
            message(when (result) {
                202 -> "邀请已发送，等待对方确认"
                409 -> "对方正在房间中、已有邀请或邀请已处理"
                429 -> "邀请过于频繁，请稍后再试"
                else -> "无法送达，对方可能已退出附近页面或网络隔离"
            })
        }
    }

    private fun reconcile() {
        scope.launch {
            lifecycle.withLock {
                if (state.value.enabled && foreground) {
                    if (engine == null) start()
                } else stop()
            }
        }
    }

    private suspend fun start() {
        val gen = ++generation
        id = UUID.randomUUID().toString()
        val nonce = UUID.randomUUID().toString()
        try {
            val server = embeddedServer(CIO, host = "0.0.0.0", port = 0) {
                nearbyModule(nonce, ::currentProfile, gate)
            }
            engine = server
            withContext(Dispatchers.IO) { server.start(wait = false) }
            val port = server.engine.resolvedConnectors().first().port
            mutableState.value = state.value.copy(active = true, message = null)
            val reg = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    val listener = this
                    scope.launch { if (gen != generation) runCatching { nsd.unregisterService(listener) } }
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                    scope.launch { lifecycle.withLock { if (gen == generation) { message("附近广播失败 ($code)，可关闭后重试"); stop() } } }
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
            }
            registration = reg
            nsd.registerService(NsdServiceInfo().apply {
                serviceName = "SyncListen-" + id.take(8)
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("v", "1")
                setAttribute("id", id)
                setAttribute("nonce", nonce)
            }, NsdManager.PROTOCOL_DNS_SD, reg)
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {
                    val listener = this
                    scope.launch { if (gen != generation) runCatching { nsd.stopServiceDiscovery(listener) } }
                }
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onStartDiscoveryFailed(type: String, code: Int) {
                    scope.launch { lifecycle.withLock { if (gen == generation) { message("附近搜索失败 ($code)，可关闭后重试"); stop() } } }
                }
                override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
                override fun onServiceFound(info: NsdServiceInfo) {
                    scope.launch {
                        if (gen != generation || !info.serviceName.startsWith("SyncListen-") || services.size >= 64) return@launch
                        if (services.add(info.serviceName)) { resolving.addLast(info); resolveNext(gen) }
                    }
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    scope.launch {
                        if (gen != generation) return@launch
                        services.remove(info.serviceName)
                        mutableState.value = state.value.copy(peers = state.value.peers.filterNot { it.serviceName == info.serviceName })
                    }
                }
            }
            discovery = listener
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            message("附近功能不可用，请确认 Wi-Fi 已连接")
            stop()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(gen: Int) {
        if (gen != generation || resolverBusy || resolving.isEmpty()) return
        val next = resolving.removeFirst()
        resolverBusy = true
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                scope.launch { if (gen == generation) { resolverBusy = false; resolveNext(gen) } }
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                scope.launch {
                    if (gen != generation) return@launch
                    resolverBusy = false
                    resolveNext(gen)
                    val addresses = if (android.os.Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
                    val host = addresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress.orEmpty()
                    val peerId = info.attributes["id"]?.decodeToString().orEmpty()
                    val nonce = info.attributes["nonce"]?.decodeToString().orEmpty()
                    if (peerId != id && NearbyValidation.id(peerId) && NearbyValidation.id(nonce) &&
                        NearbyValidation.isLanIpv4(host) && info.port in 1..65535 &&
                        services.contains(info.serviceName)) {
                        refreshPeer(NearbyPeer(info.serviceName, "http://$host:${info.port}", nonce,
                            NearbyProfile(peerId, "听友")), gen)
                    }
                    resolveNext(gen)
                }
            }
        }
        runCatching { nsd.resolveService(next, listener) }.onFailure {
            resolverBusy = false
            services.remove(next.serviceName)
            resolveNext(gen)
        }
    }

    private suspend fun refreshPeer(peer: NearbyPeer, gen: Int) {
        val profile = networkSlots.withPermit { withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(peer.endpoint + "/nearby/profile").header(NEARBY_HEADER, peer.nonce).build())
                    .execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val source = response.body?.source() ?: return@use null
                        source.request(4097)
                        if (source.buffer.size > 4096) return@use null
                        json.decodeFromString<NearbyProfile>(source.readUtf8()).takeIf {
                            NearbyValidation.profile(it) && it.id == peer.profile.id
                        }
                    }
            }.getOrNull()
        }
        }
        if (gen != generation || !services.contains(peer.serviceName)) return
        val others = state.value.peers.filterNot { it.serviceName == peer.serviceName }
        mutableState.value = state.value.copy(peers = if (profile == null) others else
            (others + peer.copy(profile = profile, lastSeenAtMs = SystemClock.elapsedRealtime())).sortedBy { it.profile.name })
    }

    private suspend fun stop() {
        generation++
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        val server = engine
        engine = null
        if (server != null) withContext(Dispatchers.IO) { server.stop(0, 500) }
        gate.clear()
        services.clear()
        resolving.clear()
        resolverBusy = false
        mutableState.value = state.value.copy(active = false, peers = emptyList())
    }

    companion object { const val SERVICE_TYPE = "_synclisten._tcp." }
}
