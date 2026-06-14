package com.synclisten.app.host

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

interface HostRuntimeLauncher {
    val state: StateFlow<HostServerState>
    suspend fun start(advertisedUrl: String): HostServerState
    suspend fun stop()
}

class DefaultHostServerController(
    private val addressProvider: () -> InetAddress?,
    private val runtime: HostRuntimeLauncher,
) : HostServerController {
    private val mutex = Mutex()
    override val state: StateFlow<HostServerState> = runtime.state

    override suspend fun start(): HostServerState = mutex.withLock {
        val current = state.value
        if (current is HostServerState.Running || current is HostServerState.Starting) return current
        val address = addressProvider()
            ?: return HostServerState.Error("没有可供其他手机访问的 IPv4 地址，请连接 Wi-Fi 或开启手机热点")
        runtime.start(HostAddressResolver().advertisedUrl(address))
    }

    override suspend fun stop() = mutex.withLock { runtime.stop() }
}

@Singleton
class AndroidHostRuntimeLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) : HostRuntimeLauncher {
    override val state: StateFlow<HostServerState> = HostServerRuntime.state

    override suspend fun start(advertisedUrl: String): HostServerState {
        HostServerRuntime.mutableState.value = HostServerState.Starting
        ContextCompat.startForegroundService(
            context,
            Intent(context, HostServerService::class.java)
                .setAction(HostServerService.ACTION_START)
                .putExtra(HostServerService.EXTRA_ADVERTISED_URL, advertisedUrl),
        )
        return runCatching {
            withTimeout(15_000) {
                state.filter { it is HostServerState.Running || it is HostServerState.Error }.first()
            }
        }.getOrElse {
            HostServerState.Error("手机托管服务启动超时").also { error -> HostServerRuntime.mutableState.value = error }
        }
    }

    override suspend fun stop() {
        context.startService(Intent(context, HostServerService::class.java).setAction(HostServerService.ACTION_STOP))
        runCatching { withTimeout(5_000) { state.filter { it is HostServerState.Stopped }.first() } }
    }
}

internal object HostServerRuntime {
    val mutableState = MutableStateFlow<HostServerState>(HostServerState.Stopped)
    val state: StateFlow<HostServerState> = mutableState
}
