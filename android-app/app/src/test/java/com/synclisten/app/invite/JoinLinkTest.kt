package com.synclisten.app.invite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoinLinkTest {
    @Test
    fun encodesAndParsesValidJoinLink() {
        val link = JoinLink("room 1", "token/+?", "http://10.0.2.2:3000")

        val encoded = JoinLinkCodec.encode(link)

        assertEquals(link, JoinLinkCodec.parse(encoded))
    }

    @Test
    fun rejectsMissingFieldsWrongSchemeAndUnsafeServer() {
        assertNull(JoinLinkCodec.parse("synclisten://join?roomId=room&token=token"))
        assertNull(JoinLinkCodec.parse("https://join?roomId=room&token=token&server=http%3A%2F%2Flocalhost"))
        assertNull(JoinLinkCodec.parse("synclisten://join?roomId=room&token=token&server=file%3A%2F%2Ftmp"))
        assertNull(JoinLinkCodec.parse("synclisten://join?roomId=room&token=token&server=http%3A%2F%2Fuser%3Apass%40host"))
    }
}
