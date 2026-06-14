package com.synclisten.app.nearby

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleRoomDiscoveryTest {
    @Test
    fun invitePayloadContainsVersionedIpv4PortAndRoomCode() {
        val payload = BleInviteCodec.encode(BleInvite("e0670b", "http://192.168.43.1:38571"))

        assertEquals(13, payload.size)
        assertEquals(BleInvite("E0670B", "http://192.168.43.1:38571"), BleInviteCodec.decode(payload))
    }

    @Test
    fun decodesLegacyRoomCodeWithoutServerAddress() {
        assertEquals(BleInvite("E0670B", null), BleInviteCodec.decode("E0670B".encodeToByteArray()))
        assertNull(BleInviteCodec.decode("SyncListen:E0670B:secret".encodeToByteArray()))
        assertNull(BleInviteCodec.decode("Other:E0670B".encodeToByteArray()))
    }

    @Test
    fun permissionsFollowAndroidVersionAndOperation() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            blePermissions(sdkInt = 30, advertise = false),
        )
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            blePermissions(sdkInt = 35, advertise = false),
        )
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT),
            blePermissions(sdkInt = 35, advertise = true),
        )
    }
}
