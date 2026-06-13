package com.synclisten.app.playback

import com.synclisten.app.domain.model.MemberRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPermissionTest {
    @Test
    fun onlyHostCanControlPlayback() {
        assertTrue(MemberRole.HOST.canControlPlayback())
        assertFalse(MemberRole.MEMBER.canControlPlayback())
    }
}
