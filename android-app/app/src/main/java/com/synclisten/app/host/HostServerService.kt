package com.synclisten.app.host

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.room.withTransaction
import com.synclisten.app.host.persistence.HostPersistenceDatabase
import com.synclisten.app.host.server.HostRoomHub
import com.synclisten.app.host.server.HostRoomStore
import com.synclisten.app.host.server.HostStorage
import com.synclisten.app.host.server.hostServerModule
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@AndroidEntryPoint
class HostServerService : Service() {

    @Inject lateinit var persistenceDatabase: HostPersistenceDatabase

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private var explicitlyStopped = false
    private var engine: EmbeddedServer<*, *>? = null
    private var store: HostRoomStore? = null
    private var hub: HostRoomHub? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val advertisedUrl = intent?.getStringExtra(EXTRA_ADVERTISED_URL)
        if (action == ACTION_START || action == ACTION_RECOVER) {
            startForeground(NOTIFICATION_ID, notification(advertisedUrl.orEmpty()))
        }
        serviceScope.launch {
            lifecycleMutex.withLock {
                when (action) {
                    ACTION_STOP -> stopHosting()
                    ACTION_START -> startHosting(advertisedUrl, recover = false)
                    ACTION_RECOVER -> startHosting(advertisedUrl, recover = true)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.launch {
            lifecycleMutex.withLock {
                try {
                    if (!explicitlyStopped) store?.emergencyShutdown()
                    if (engine != null) shutdown()
                } finally { serviceScope.cancel() }
            }
        }
        super.onDestroy()
    }

    private suspend fun startHosting(advertisedUrl: String?, recover: Boolean) {
        if (engine != null) return
        if (advertisedUrl.isNullOrBlank()) {
            fail("手机托管地址无效")
            return
        }
        explicitlyStopped = false
        acquireWifiLock()
        runCatching {
            val roomStore = HostRoomStore(
                dao = persistenceDatabase.hostDao(),
                transaction = { block -> persistenceDatabase.withTransaction { block() } },
            )
            if (recover) roomStore.recoverRoom()
            val roomHub = HostRoomHub()
            val server = embeddedServer(CIO, host = "0.0.0.0", port = HOST_SERVER_PORT) {
                hostServerModule(
                    store = roomStore,
                    hub = roomHub,
                    storage = HostStorage(filesDir.resolve("host-server")),
                )
            }
            server.start(wait = false)
            store = roomStore
            hub = roomHub
            engine = server
            HostServerRuntime.mutableState.value =
                HostServerState.Running(HOST_LOCAL_URL, advertisedUrl, HOST_SERVER_PORT)
        }.onFailure { fail("手机托管服务启动失败：${it.message ?: "未知错误"}") }
    }

    private suspend fun stopHosting() {
        explicitlyStopped = true
        store?.closeAndCleanup()
        hub?.closeAll()
        shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun shutdown() {
        hub?.closeAll()
        engine?.stop(500, 2_000)
        engine = null
        hub = null
        store = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        HostServerRuntime.mutableState.value = HostServerState.Stopped
    }

    private suspend fun fail(message: String) {
        shutdown()
        HostServerRuntime.mutableState.value = HostServerState.Error(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWifiLock() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SyncListen:HostServer").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun notification(advertisedUrl: String): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sync Listen 手机托管", NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sync Listen 正在托管房间")
            .setContentText(advertisedUrl)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.synclisten.app.host.START"
        const val ACTION_STOP = "com.synclisten.app.host.STOP"
        const val ACTION_RECOVER = "com.synclisten.app.host.RECOVER"
        const val EXTRA_ADVERTISED_URL = "advertisedUrl"
        private const val CHANNEL_ID = "host-server"
        private const val NOTIFICATION_ID = 38571
    }
}
