package com.synclisten.app.host

import kotlinx.coroutines.flow.StateFlow

const val HOST_SERVER_PORT = 38571
const val HOST_LOCAL_URL = "http://127.0.0.1:$HOST_SERVER_PORT"

sealed interface HostServerState {
    data object Stopped : HostServerState
    data object Starting : HostServerState
    data class Running(
        val localUrl: String,
        val advertisedUrl: String,
        val port: Int,
    ) : HostServerState
    data class Error(val message: String) : HostServerState
}

interface HostServerController {
    val state: StateFlow<HostServerState>
    suspend fun start(): HostServerState
    suspend fun stop()
}
