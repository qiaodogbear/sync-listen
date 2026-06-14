package com.synclisten.app.host

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

data class NetworkAddress(val interfaceName: String, val address: InetAddress)

@Singleton
class HostAddressResolver @Inject constructor() {
    fun resolve(): InetAddress? = select(
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { network ->
            network.inetAddresses.toList().map { NetworkAddress(network.name, it) }
        },
    )

    fun select(addresses: List<NetworkAddress>): InetAddress? =
        addresses
            .filter { it.address is Inet4Address && it.address.isReachableLanAddress() }
            .sortedByDescending { it.interfaceName.isWifiLike() }
            .firstOrNull()
            ?.address

    fun advertisedUrl(address: InetAddress, port: Int = HOST_SERVER_PORT): String =
        "http://${address.hostAddress}:$port"

    private fun InetAddress.isReachableLanAddress(): Boolean =
        !isLoopbackAddress && !isLinkLocalAddress && !isMulticastAddress && !isAnyLocalAddress

    private fun String.isWifiLike(): Boolean {
        val normalized = lowercase()
        return normalized.startsWith("wlan") ||
            normalized.startsWith("wifi") ||
            normalized.startsWith("ap") ||
            normalized.startsWith("swlan")
    }
}
