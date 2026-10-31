package com.synclisten.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTest {
    @Test fun parsesMultipleTimestampsOffsetsAndOrdersLines() {
        val parsed = parseLyrics("[ar:Artist]\n[offset:-200]\n[00:02.50][00:01.005]hello\n[00:03]world\n[00:60.00]invalid")
        assertEquals(listOf(805L, 2300L, 2800L), parsed.lines.map { it.timeMs })
        assertEquals(listOf("hello", "hello", "world"), parsed.lines.map { it.text })
        assertEquals(-1, currentLyricIndex(parsed.lines, 804))
        assertEquals(1, currentLyricIndex(parsed.lines, 2300))
    }

    @Test fun retainsUntimedLyricsAndHandlesMalformedInput() {
        val parsed = parseLyrics("[ti:Song]\nfirst line\n[not a timestamp]second line")
        assertEquals(emptyList<LyricLine>(), parsed.lines)
        assertEquals("first line\n[not a timestamp]second line", parsed.plainText)
    }
}
