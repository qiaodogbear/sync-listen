package com.synclisten.app.host

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostAddressResolverTest {
    @Test
    fun selectsReachableIpv4AndFormatsAdvertisedUrl() {
        val resolver = HostAddressResolver()

        val address = resolver.select(
            listOf(
                NetworkAddress("lo", InetAddress.getByName("127.0.0.1")),
                NetworkAddress("wlan0", InetAddress.getByName("192.168.43.1")),
            ),
        )

        assertEquals("192.168.43.1", address?.hostAddress)
        assertEquals("http://192.168.43.1:38571", resolver.advertisedUrl(address!!, 38571))
    }

    @Test
    fun rejectsLoopbackLinkLocalAndIpv6() {
        val resolver = HostAddressResolver()

        val address = resolver.select(
            listOf(
                NetworkAddress("lo", InetAddress.getByName("127.0.0.1")),
                NetworkAddress("wlan0", InetAddress.getByName("169.254.2.1")),
                NetworkAddress("wlan0", InetAddress.getByName("fe80::1")),
            ),
        )

        assertNull(address)
    }

    @Test
    fun prefersWifiLikeInterface() {
        val resolver = HostAddressResolver()

        val address = resolver.select(
            listOf(
                NetworkAddress("rmnet0", InetAddress.getByName("10.10.0.2")),
                NetworkAddress("wlan0", InetAddress.getByName("192.168.1.7")),
            ),
        )

        assertEquals("192.168.1.7", address?.hostAddress)
    }
}
