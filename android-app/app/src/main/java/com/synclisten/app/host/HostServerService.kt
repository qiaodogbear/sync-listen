package com.synclisten.app.host

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class HostServerService : Service() {

    @Inject lateinit var persistenceDatabase: HostPersistenceDatabase

    private var engine: EmbeddedServer<*, *>? = null
    private var store: HostRoomStore? = null
    private var hub: HostRoomHub? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopHosting()
            ACTION_START -> startHosting(intent.getStringExtra(EXTRA_ADVERTISED_URL))
            ACTION_RECOVER -> recoverHosting(intent.getStringExtra(EXTRA_ADVERTISED_URL))
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        runBlocking { store?.emergencyShutdown() }
        shutdown()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (store != null) {
            runBlocking { store?.emergencyShutdown() }
        }
        shutdown()
        super.onDestroy()
    }

    private fun startHosting(advertisedUrl: String?) {
        if (engine != null) return
        if (advertisedUrl.isNullOrBlank()) {
            fail("手机托管地址无效")
            return
        }
        startForeground(NOTIFICATION_ID, notification(advertisedUrl))
        acquireWifiLock()
        runCatching {
            val roomStore = HostRoomStore(dao = persistenceDatabase.hostDao())
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

    private fun recoverHosting(advertisedUrl: String?) {
        if (engine != null) return
        if (advertisedUrl.isNullOrBlank()) {
            fail("恢复地址无效")
            return
        }
        startForeground(NOTIFICATION_ID, notification(advertisedUrl))
        acquireWifiLock()
        runCatching {
            val roomStore = HostRoomStore(dao = persistenceDatabase.hostDao())
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
        }.onFailure { fail("手机托管恢复失败：${it.message ?: "未知错误"}") }
    }

    private fun stopHosting() {
        runBlocking { store?.closeAndCleanup() }
        hub?.let { runBlocking { it.closeAll() } }
        shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdown() {
        engine?.stop(500, 2_000)
        engine = null
        hub = null
        store = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        HostServerRuntime.mutableState.value = HostServerState.Stopped
    }

    private fun fail(message: String) {
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
