package com.exodidio.tune.lyrics

import com.exodidio.tune.ui.navigation.parsePlayerLyricsDocument
import org.junit.Assert.assertEquals
import org.junit.Test

class EnvelopeTranslationMatchTest {
    @Test
    fun matchesTranslationWhenFractionTruncatesInBinaryFloatingPoint() {
        // 8.133 * 1000 在 Double 里是 8132.999…，按毫秒取整会落到 8132，
        // 与 yrc 行头 8133 对不上，这一行翻译就丢了。
        val envelope = """
            {"tuneLyrics":1,
             "yrc":"[8133,1000](8133,500,0)一(8633,500,0)二\n",
             "tlyric":"[00:08.133]译文\n"}
        """.trimIndent()
        val lines = parsePlayerLyricsDocument(envelope).lines
        assertEquals(1, lines.size)
        assertEquals("一二", lines[0].primary)
        assertEquals("译文", lines[0].secondary)
    }

    @Test
    fun matchesTranslationAtThreeDecimalTimestamps() {
        val envelope = """
            {"tuneLyrics":1,
             "yrc":"[8133,1000](8133,1000,0)一\n[43994,1000](43994,1000,0)二\n",
             "tlyric":"[00:08.133]译文一\n[00:43.994]译文二\n"}
        """.trimIndent()
        val lines = parsePlayerLyricsDocument(envelope).lines
        assertEquals(listOf("译文一", "译文二"), lines.map { it.secondary })
    }
}
