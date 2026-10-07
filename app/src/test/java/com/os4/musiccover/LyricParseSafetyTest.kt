package com.os4.musiccover

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricParseSafetyTest {
    @Test fun reversedLineTimesAreRecoveredWithoutCrashingTheParserWorker() {
        // Synthetic reproduction of EnhancedLrcParser's invalid line interval.
        val body = "[00:20.02]Later line\n[00:14.44]Earlier line\n[00:26.91]Final line"
        val lines = LyricParse.parse(body)
        assertEquals(listOf("Earlier line", "Later line", "Final line"), lines.map { it.text })
        assertEquals(listOf(14440, 20020, 26910), lines.map { it.start })
    }

    @Test fun validLyricsStillParse() {
        assertTrue(LyricParse.parse("[00:01.00]First line\n[00:02.00]Second line").isNotEmpty())
    }
}
