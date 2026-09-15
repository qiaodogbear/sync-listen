package com.synclisten.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val networks = java.util.concurrent.ConcurrentHashMap.newKeySet<Network>()
    private val mutableAvailable = MutableStateFlow(true)
    val isAvailable: StateFlow<Boolean> = mutableAvailable

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networks.add(network)
            mutableAvailable.value = true
        }

        override fun onLost(network: Network) {
            networks.remove(network)
            mutableAvailable.value = networks.isNotEmpty()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            networks.add(network)
            mutableAvailable.value = networks.isNotEmpty()
        }
    }

    init {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.registerNetworkCallback(
            NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            callback,
        )
    }
}
