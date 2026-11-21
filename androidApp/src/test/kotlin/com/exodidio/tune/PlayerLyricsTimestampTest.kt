package com.exodidio.tune

import com.exodidio.tune.ui.navigation.parsePlayerLyrics
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerLyricsTimestampTest {
    @Test
    fun parsesColonFractionTimestamps() {
        val content = """
            [00:00.00] 作词 : A
            [00:03:23]深い海の底から
            [00:05:80]月を目指す
        """.trimIndent()
        val lines = parsePlayerLyrics(content).filter { it.timestampSeconds != null }
        assertEquals(3, lines.size)
        assertEquals(3.23f, lines[1].timestampSeconds!!, 0.001f)
        assertEquals(5.80f, lines[2].timestampSeconds!!, 0.001f)
    }

    @Test
    fun supportsMultipleLeadingTimestamps() {
        val lines = parsePlayerLyrics("[00:01.00][00:10.00]chorus")
        assertEquals(2, lines.size)
        assertEquals(1.0f, lines[0].timestampSeconds!!, 0.001f)
        assertEquals(10.0f, lines[1].timestampSeconds!!, 0.001f)
        assertEquals("chorus", lines[1].primary)
    }
}
