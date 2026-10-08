package com.os4.musiccover

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Verifies that Original and Extended keep separate parsing and matching policies. */
class LyricModeTest {
    private var previousMode = 0
    private var previousGroups = false
    private var previousEffects = 0

    @Before
    fun rememberSettings() {
        previousMode = LockLyrics.sSearchMode
        previousGroups = LockLyrics.sRapidGroups
        previousEffects = LockLyrics.sAliveFx
    }

    @After
    fun restoreSettings() {
        LockLyrics.setSearchMode(previousMode)
        LockLyrics.sRapidGroups = previousGroups
        LockLyrics.sAliveFx = previousEffects
    }

    @Test
    fun originalUsesUnmodifiedParserIncludingSecondaryLines() {
        LockLyrics.setSearchMode(LockLyrics.SEARCH_ORIGINAL)
        val body = "[00:01.00]<00:01.00>Stay <00:02.00>(with <00:03.00>me)<00:04.00>"
        val actual = LyricParse.parse(body, "[00:01.00]留下", null).single()
        val expected = OriginalLyricParse.parse(body, "[00:01.00]留下", null).single()
        assertEquals(expected.text, actual.text)
        assertEquals(expected.translation, actual.translation)
        assertEquals(expected.roma, actual.roma)
        assertEquals(expected.start, actual.start)
        assertEquals(expected.end, actual.end)
        assertArrayEquals(expected.sylStart, actual.sylStart)
        assertArrayEquals(expected.sylEnd, actual.sylEnd)
        assertArrayEquals(expected.charEnd, actual.charEnd)
        assertNull(actual.bg)
    }

    @Test
    fun extendedSplitsInlineBackingVocalsAndSwitchingBackRestoresOriginal() {
        val body = "[00:01.00]<00:01.00>Stay <00:02.00>(with <00:03.00>me)<00:04.00>"
        LockLyrics.setSearchMode(LockLyrics.SEARCH_EXTENDED)
        val extended = LyricParse.parse(body).single()
        assertEquals("Stay", extended.text)
        assertEquals("with me", extended.bg.text)
        LockLyrics.setSearchMode(LockLyrics.SEARCH_ORIGINAL)
        val original = LyricParse.parse(body).single()
        assertEquals(OriginalLyricParse.parse(body).single().text, original.text)
        assertNull(original.bg)
    }

    @Test
    fun modesSeparateProviderCaches() {
        val query = NcmLyrics.Query("Song", "Artist", "Album", 200000L)
        LockLyrics.setSearchMode(LockLyrics.SEARCH_ORIGINAL)
        val originalKey = query.key()
        LockLyrics.setSearchMode(LockLyrics.SEARCH_EXTENDED)
        assertNotEquals(originalKey, query.key())
    }

    @Test
    fun selectingModeKeepsGentleFlowAndEffectsIndependent() {
        LockLyrics.sRapidGroups = true
        LockLyrics.sAliveFx = AliveLyricsEffects.EYE_CANDY
        LockLyrics.setSearchMode(LockLyrics.SEARCH_EXTENDED)
        LockLyrics.setSearchMode(LockLyrics.SEARCH_ORIGINAL)
        assertTrue(LockLyrics.sRapidGroups)
        assertEquals(AliveLyricsEffects.EYE_CANDY, LockLyrics.sAliveFx)
        LockLyrics.setSearchMode(123)
        assertEquals(LockLyrics.SEARCH_ORIGINAL, LockLyrics.sSearchMode)
    }
}
