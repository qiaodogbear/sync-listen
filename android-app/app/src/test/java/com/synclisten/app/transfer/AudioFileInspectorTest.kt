package com.synclisten.app.transfer

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFileInspectorTest {
    @Test
    fun computesSha256WithoutOwningWholeFile() {
        val hash = sha256(ByteArrayInputStream("hello".toByteArray()))

        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
    }

    @Test
    fun acceptsSupportedAudioFormats() {
        assertTrue(isSupportedAudio("song.mp3", "audio/mpeg"))
        assertTrue(isSupportedAudio("song.flac", "audio/flac"))
        assertTrue(isSupportedAudio("song.wav", "audio/wav"))
        assertTrue(isSupportedAudio("song.ogg", "audio/ogg"))
        assertTrue(isSupportedAudio("song.aac", "audio/aac"))
        assertFalse(isSupportedAudio("notes.txt", null))
        assertFalse(isSupportedAudio("video.mp4", "video/mp4"))
    }
}
