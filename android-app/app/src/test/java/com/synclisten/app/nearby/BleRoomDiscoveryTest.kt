package com.synclisten.app.nearby

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleRoomDiscoveryTest {
    @Test
    fun invitePayloadContainsOnlyNormalizedRoomCode() {
        val payload = BleInviteCodec.encode(" e0670b ")

        assertEquals("SyncListen:E0670B", payload.decodeToString())
        assertEquals("E0670B", BleInviteCodec.decode(payload))
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
