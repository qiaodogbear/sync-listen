package com.synclisten.app.invite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeCodecTest {
    @Test
    fun encodesJoinLinkIntoRequestedQrMatrix() {
        val link = JoinLinkCodec.encode(JoinLink("room", "token", "http://10.0.2.2:3000"))

        val matrix = QrCodeCodec.encode(link, 256)

        assertEquals(256, matrix.width)
        assertEquals(256, matrix.height)
        assertTrue((0 until matrix.width).any { x -> (0 until matrix.height).any { y -> matrix[x, y] } })
    }
}
