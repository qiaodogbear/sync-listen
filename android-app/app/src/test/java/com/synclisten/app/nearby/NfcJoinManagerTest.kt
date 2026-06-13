package com.synclisten.app.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NfcJoinManagerTest {
    @Test
    fun selectsOnlyValidJoinLinkFromNfcCandidates() {
        val valid = "synclisten://join?roomId=room&token=token&server=http%3A%2F%2Fhost"

        assertEquals(valid, NfcJoinCodec.findJoinLink(listOf("hello", valid)))
        assertNull(NfcJoinCodec.findJoinLink(listOf("https://example.com", "synclisten://other")))
    }
}
