package com.os4.musiccover

import org.junit.Assert.*
import org.junit.Test

class LyricParseTest {
    private fun words(vararg chunks: String, translation: String? = null): LyricLine {
        var chars = 0
        return LyricLine(chunks.joinToString(""), translation, 1000, 6000, true,
            IntArray(chunks.size) { 1000 + it * 500 },
            IntArray(chunks.size) { 1400 + it * 500 },
            IntArray(chunks.size) { chars += chunks[it].length; chars })
    }

    private fun timing(line: LyricLine, starts: IntArray, ends: IntArray, chars: IntArray) {
        assertArrayEquals(starts, line.sylStart)
        assertArrayEquals(ends, line.sylEnd)
        assertArrayEquals(chars, line.charEnd)
        assertEquals(line.text.length, line.charEnd.last())
    }

    @Test
    fun enhancedLrcEchoKeepsWordTimesAndSeparateTranslationAndRoma() {
        val body = "[00:01.00]<00:01.00>Stay <00:02.00>(with <00:03.00>me)<00:04.00>"
        val line = LyricParse.parse(body, "[00:01.00]留下（陪我）",
            "[00:01.00]sutey (wizu mi)").single()
        assertEquals("Stay", line.text)
        assertEquals("with me", line.bg.text)
        assertEquals("sutey (wizu mi)\n留下（陪我）", line.translation)
        timing(line, intArrayOf(1000), intArrayOf(2000), intArrayOf(4))
        timing(line.bg, intArrayOf(2000, 3000), intArrayOf(3000, 4000), intArrayOf(5, 7))
        assertEquals(1000, line.start)
        assertEquals(2000, line.bg.start)
        assertEquals(4000, line.end)
        assertEquals(4000, line.bg.end)
        assertEquals(0f, line.bg.sungChars(1900), 0f)
        assertEquals(2.5f, line.bg.sungChars(2500), 0f)
    }

    @Test
    fun ttmlInlineFullwidthEchoKeepsNativeTranslationAndRomanization() {
        val body = ttml("""
            <span begin="00:01.000" end="00:02.000">留下</span><span begin="00:02.000" end="00:03.000">（陪</span><span begin="00:03.000" end="00:04.000">我）</span>
            <span ttm:role="x-translation">Stay (with me)</span>
            <span ttm:role="x-roman">liu xia (pei wo)</span>
        """.trimIndent())
        val line = LyricParse.parse(body, "[00:01.00]Separate translation").single()
        assertEquals("留下", line.text)
        assertEquals("陪我", line.bg.text)
        assertEquals("liu xia (pei wo)\nStay (with me)\nSeparate translation", line.translation)
        timing(line, intArrayOf(1000), intArrayOf(2000), intArrayOf(2))
        timing(line.bg, intArrayOf(2000, 3000), intArrayOf(3000, 4000), intArrayOf(1, 2))
    }

    @Test
    fun nativeRomanizationAndSeparateTranslationBothSurvive() {
        val body = ttml("""
            <span begin="00:01.000" end="00:02.000">留下 </span><span begin="00:02.000" end="00:03.000">（陪我）</span>
            <span ttm:role="x-roman">liu xia (pei wo)</span>
        """.trimIndent())
        val line = LyricParse.parse(body, "[00:01.00]Stay (with me)").single()
        assertEquals("留下", line.text)
        assertEquals("陪我", line.bg.text)
        assertEquals("liu xia (pei wo)\nStay (with me)", line.translation)
    }

    @Test
    fun separateTranslationDoesNotDuplicateNativeTranslation() {
        val body = """
            [00:01.00]<00:01.00>Stay <00:02.00>(oh)<00:03.00>
            [00:01.00]留下
        """.trimIndent()
        val line = LyricParse.parse(body, "[00:01.00]留下").single()
        assertEquals("留下", line.translation)
        assertEquals("oh", line.bg.text)
    }

    @Test
    fun lyricifySyllablesUseTheirDurationsWithoutRedistributingThem() {
        val line = LyricParse.parse("[2]Stay (1000,400)(with (1500,300)me)(2000,600)").single()
        assertEquals("Stay", line.text)
        assertEquals("with me", line.bg.text)
        assertTrue(line.opposite)
        assertTrue(line.bg.opposite)
        timing(line, intArrayOf(1000), intArrayOf(1400), intArrayOf(4))
        timing(line.bg, intArrayOf(1500, 2000), intArrayOf(1800, 2600), intArrayOf(5, 7))
        assertEquals(2600, line.end)
    }

    @Test
    fun middleEchoRebasesLaterMainOffsetsAndPreservesPunctuation() {
        val original = words("Hello ", "(oh, ", "yeah)", ", ", "world!",
            translation = "roma\ntranslation (unchanged)")
        val line = LyricParse.parenthesizedBacking(original)
        assertEquals("Hello, world!", line.text)
        assertEquals("oh, yeah", line.bg.text)
        assertEquals(original.translation, line.translation)
        assertEquals(original.start, line.start)
        assertEquals(original.end, line.end)
        assertEquals(original.opposite, line.opposite)
        timing(line, intArrayOf(1000, 2500, 3000), intArrayOf(1400, 2900, 3400),
            intArrayOf(5, 7, 13))
        timing(line.bg, intArrayOf(1500, 2000), intArrayOf(1900, 2400), intArrayOf(4, 8))
        assertEquals(5f, line.sungChars(2200), 0f)
        assertEquals(10f, line.sungChars(3200), 0f)
        assertEquals("Hello (oh, yeah), world!", original.text)
        assertNull(original.bg)
        assertSame(line, LyricParse.parenthesizedBacking(line))
    }

    @Test
    fun cutRetainsOneSourceSpaceBetweenMainWords() {
        val line = LyricParse.parenthesizedBacking(words("Hello ", "(oh) ", "world"))
        assertEquals("Hello world", line.text)
        timing(line, intArrayOf(1000, 2000), intArrayOf(1400, 2400), intArrayOf(6, 11))
        assertEquals("oh", line.bg.text)
    }

    @Test
    fun openingQuoteKeepsSeparatorAndSupplementaryCharactersKeepUtf16Offsets() {
        val line = LyricParse.parenthesizedBacking(words("Hello ", "(oh 🎵) ", "“world”"))
        assertEquals("Hello “world”", line.text)
        assertEquals("oh 🎵", line.bg.text)
        timing(line.bg, intArrayOf(1500), intArrayOf(1900), intArrayOf(5))
        timing(line, intArrayOf(1000, 2000), intArrayOf(1400, 2400), intArrayOf(6, 13))
    }

    @Test
    fun parenthesesAndWhitespaceOnlySyllablesDoNotInventTimings() {
        val line = LyricParse.parenthesizedBacking(words("Stay ", "(", " ", "oh", ")"))
        assertEquals("Stay", line.text)
        assertEquals("oh", line.bg.text)
        timing(line.bg, intArrayOf(2500), intArrayOf(2900), intArrayOf(2))
        assertEquals(2500, line.bg.start)
        assertEquals(2900, line.bg.end)
    }

    @Test
    fun leadingEchoDoesNotShiftMainLineStartOrLoseTranslation() {
        val line = LyricParse.parenthesizedBacking(words("(oh) ", "Stay!", translation = "留下"))
        assertEquals("Stay!", line.text)
        assertEquals(1000, line.start)
        assertEquals("留下", line.translation)
        timing(line, intArrayOf(1500), intArrayOf(1900), intArrayOf(5))
        timing(line.bg, intArrayOf(1000), intArrayOf(1400), intArrayOf(2))
    }

    @Test
    fun parentheticalSharingASourceSyllableKeepsItsOriginalTimingOnBothVoices() {
        val original = words("Stay (oh)", translation = "留下")
        val line = LyricParse.parenthesizedBacking(original)
        assertEquals("Stay", line.text)
        assertEquals("oh", line.bg.text)
        assertEquals("留下", line.translation)
        timing(line, intArrayOf(1000), intArrayOf(1400), intArrayOf(4))
        timing(line.bg, intArrayOf(1000), intArrayOf(1400), intArrayOf(2))
        assertEquals(line.sungChars(1200), line.bg.sungChars(1200), 0f)
    }

    @Test
    fun mixedMainAndEchoSyllableKeepsTimingWithoutBlockingLeadFill() {
        val original = words("Stay (", "oh) please")
        val line = LyricParse.parenthesizedBacking(original)
        assertEquals("Stay please", line.text)
        assertEquals("oh", line.bg.text)
        timing(line, intArrayOf(1000, 1500), intArrayOf(1400, 1900), intArrayOf(4, 11))
        timing(line.bg, intArrayOf(1500), intArrayOf(1900), intArrayOf(2))
    }

    @Test
    fun ambiguousOrUnsungNotationIsUntouched() {
        val cases = listOf(
            words("Stay ", "(oh (yeah))"),
            words("Stay ", "（oh)"),
            words("Stay ", "(oh"),
            words("Stay ", "oh)"),
            words("Stay ", "(oh) ", "(yeah)"),


            words("sing", "(ing)"),
            words("(oh)"),
            words("... ", "(oh)"),
            words("Stay ", "()"),
            words("Stay ", "(!!!)"),
            words("Stay ", "(Instrumental)"),
            words("Stay ", "（间奏）"),
            words("Stay ", "(guitar solo)"),
            words("Stay ", "(whispering)")
        )
        for (line in cases) assertSame(line.text, line, LyricParse.parenthesizedBacking(line))
    }

    @Test
    fun lineTimedLyricsAreNotGuessedAsBackingVocals() {
        val line = LyricParse.parse("[00:01.00]Stay (with me)").single()
        assertEquals("Stay (with me)", line.text)
        assertNull(line.bg)
        assertNull(line.sylStart)
    }

    @Test
    fun malformedCharacterMappingIsUntouched() {
        val original = words("Stay ", "(oh)")
        for (chars in listOf(intArrayOf(5, 100), intArrayOf(5, 4), intArrayOf(5, 8))) {
            val line = LyricLine(original.text, null, original.start, original.end, false,
                original.sylStart, original.sylEnd, chars)
            assertSame(line, LyricParse.parenthesizedBacking(line))
        }
    }

    @Test
    fun existingBackingObjectAndAllMetadataAreUntouched() {
        val line = words("Stay ", "(oh)", translation = "roma\ntranslation")
        val bg = words("native", translation = "native roma\nnative translation")
        line.bg = bg
        assertSame(line, LyricParse.parenthesizedBacking(line))
        assertSame(bg, line.bg)
        assertEquals("Stay (oh)", line.text)
        assertEquals("native roma\nnative translation", line.bg.translation)
    }

    @Test
    fun enhancedLrcNativeBackingWinsAndKeepsItsOwnTranslationAndTiming() {
        val body = """
            [00:01.00]<00:01.00>Stay <00:02.00>(oh)<00:03.00>
            [00:01.00]Source translation
            [bg: <00:02.00>Native<00:05.00>]
            [bg: <00:02.00>Native translation<00:05.00>]
        """.trimIndent()
        val line = LyricParse.parse(body).single()
        assertEquals("Stay (oh)", line.text)
        assertEquals("Source translation", line.translation)
        assertEquals("Native", line.bg.text)
        assertEquals("Native translation", line.bg.translation)
        timing(line.bg, intArrayOf(2000), intArrayOf(5000), intArrayOf(6))
        assertEquals(5000, line.end)
    }

    @Test
    fun nativeLyricifyBackingRemainsUntouched() {
        val body = "[2]Stay (1000,400)(oh)(1500,300)\n[8](Native)(1600,2000)"
        val line = LyricParse.parse(body).single()
        assertEquals("Stay (oh)", line.text)
        assertEquals("Native", line.bg.text)
        assertTrue(line.bg.opposite)
        timing(line.bg, intArrayOf(1600), intArrayOf(3600), intArrayOf(6))
        assertEquals(3600, line.end)
    }

    @Test
    fun ttmlNativeBackingWinsEvenWhenMainHasParentheses() {
        val body = ttml("""
            <span begin="00:01.000" end="00:02.000">Stay </span><span begin="00:02.000" end="00:03.000">(oh)</span>
            <span ttm:role="x-bg" begin="00:02.000" end="00:05.000"><span begin="00:02.000" end="00:05.000">(Native)</span><span ttm:role="x-translation">Native translation</span></span>
            <span ttm:role="x-translation">Source translation</span>
            <span ttm:role="x-roman">source roma</span>
        """.trimIndent())
        val line = LyricParse.parse(body).single()
        assertEquals("Stay (oh)", line.text)
        assertEquals("source roma\nSource translation", line.translation)
        assertEquals("Native", line.bg.text)
        assertEquals("Native translation", line.bg.translation)
        assertEquals(5000, line.end)
    }

    @Test
    fun missingEndsFollowEachDistinctWordStart() {
        val starts = intArrayOf(1000, 1700, 3000)
        val ends = intArrayOf(1000, 0, 3000)
        LyricParse.closeUntimedTail(starts, ends, 4000, 5000)
        assertArrayEquals(intArrayOf(1000, 1700, 3000), starts)
        assertArrayEquals(intArrayOf(1700, 3000, 4000), ends)
    }

    @Test
    fun onlyMissingMiddleDurationIsRepaired() {
        val starts = intArrayOf(1000, 2000, 3000)
        val ends = intArrayOf(1400, 2000, 3600)
        LyricParse.closeUntimedTail(starts, ends, 4000, 5000)
        assertArrayEquals(intArrayOf(1400, 3000, 3600), ends)
    }

    @Test
    fun tiedMissingStartsShareOnlyTheIntervalBeforeTheNextWord() {
        val starts = intArrayOf(1000, 1000, 2000, 3000)
        val ends = intArrayOf(0, 1000, 2000, 3400)
        LyricParse.closeUntimedTail(starts, ends, 4000, 5000)
        assertArrayEquals(intArrayOf(1000, 1000, 2000, 3000), starts)
        assertArrayEquals(intArrayOf(1500, 2000, 3000, 3400), ends)
    }

    @Test
    fun validDurationsIncludingOverlapsAreNeverOverwritten() {
        val starts = intArrayOf(1000, 2000, 3000)
        val ends = intArrayOf(2500, 2300, 4600)
        LyricParse.closeUntimedTail(starts, ends, 4000, 5000)
        assertArrayEquals(intArrayOf(2500, 2300, 4600), ends)
    }

    @Test
    fun terminalMissingDurationUsesLineEndThenGuardedNextStart() {
        fun repaired(lineEnd: Int, nextStart: Int): Int {
            val ends = intArrayOf(1000)
            LyricParse.closeUntimedTail(intArrayOf(1000), ends, lineEnd, nextStart)
            return ends.single()
        }
        assertEquals(6000, repaired(6000, 10000)) // Explicit holds survive long silence.
        assertEquals(4000, repaired(1000, 4000))
        assertEquals(5299, repaired(1000, 5299))
        assertEquals(1300, repaired(1000, 5300)) // Four seconds after the nominal end.
        assertEquals(1300, repaired(0, 10000))
        assertEquals(1300, repaired(0, 0))
    }

    @Test
    fun startOnlyEnhancedLrcSweepsThreeWordsAndExtendsParserEnd() {
        val line = LyricParse.parse("[00:01.00]<00:01.00>One <00:02.00>two <00:03.00>three").single()
        timing(line, intArrayOf(1000, 2000, 3000), intArrayOf(2000, 3000, 3300),
            intArrayOf(4, 8, 13))
        assertEquals(3300, line.end)
        assertEquals(2f, line.sungChars(1500), 0f)
        assertEquals(6f, line.sungChars(2500), 0f)
        assertEquals(10.5f, line.sungChars(3150), 0f)
        assertEquals(13f, line.sungChars(3300), 0f)
    }

    @Test
    fun startOnlyNativeBackingIsRepairedIndependentlyOfLeadWords() {
        val line = LyricParse.parse("""
            [00:01.00]<00:01.00>One <00:02.00>two <00:03.00>three
            [bg: <00:01.50>Oh <00:02.50>yeah]
            [00:04.00]Next
        """.trimIndent()).first()
        timing(line, intArrayOf(1000, 2000, 3000), intArrayOf(2000, 3000, 4000),
            intArrayOf(4, 8, 13))
        timing(line.bg, intArrayOf(1500, 2500), intArrayOf(2500, 4000), intArrayOf(3, 7))
        assertEquals(4000, line.end)
        assertEquals(4000, line.bg.end)
        assertEquals(1.5f, line.bg.sungChars(2000), 0f)
    }

    @Test
    fun startOnlyTailDoesNotFillAcrossAnInterlude() {
        val line = LyricParse.parse("""
            [00:01.00]<00:01.00>One <00:02.00>two <00:03.00>three
            [00:20.00]Next
        """.trimIndent()).first()
        assertArrayEquals(intArrayOf(2000, 3000, 3300), line.sylEnd)
        assertEquals(3300, line.end)
    }

    @Test
    fun zeroWordDurationsSweepAtTheirOwnStarts() {
        val line = LyricParse.parse("[1]One (1000,0)two (2000,0)three(3000,0)").single()
        timing(line, intArrayOf(1000, 2000, 3000), intArrayOf(2000, 3000, 3300),
            intArrayOf(4, 8, 13))
        assertEquals(3300, line.end)
        assertTrue(line.end >= line.sylEnd.maxOrNull()!!)
        assertEquals(10.5f, line.sungChars(3150), 0f)
    }

    @Test
    fun zeroParserLineEndEncompassesEveryRepairedSyllable() {
        val body = "[1000,0](1000,0,0)One (1000,0,0)two (1000,0,0)three"
        val source = com.mocharealm.accompanist.lyrics.core.parser.AutoParser().parse(body)
            .lines.single()
        assertTrue("Parser end ${source.end} must not exceed start ${source.start}",
            source.end <= source.start)
        val line = LyricParse.parse(body).single()
        timing(line, intArrayOf(1000, 1000, 1000), intArrayOf(1300, 1600, 1900),
            intArrayOf(4, 8, 13))
        assertEquals(1900, line.end)
        assertEquals(7f, line.sungChars(1450), 0f)
    }

    @Test
    fun parserEndAlsoEncompassesAnEarlierValidOverlappingWord() {
        val line = LyricParse.parse("[1000,0](1000,6000,0)One (2000,0,0)two").single()
        timing(line, intArrayOf(1000, 2000), intArrayOf(7000, 2300), intArrayOf(4, 7))
        assertEquals(7000, line.end)
    }

    @Test
    fun validWordAtATiedStartDoesNotBlockLookingForALaterStart() {
        val starts = intArrayOf(1000, 1000, 2000)
        val ends = intArrayOf(0, 1400, 2400)
        LyricParse.closeUntimedTail(starts, ends, 3000, 4000)
        assertArrayEquals(intArrayOf(2000, 1400, 2400), ends)
    }

    private fun ttml(spans: String): String = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div><p begin="00:01.000" end="00:04.000">$spans</p></div></body></tt>
    """.trimIndent()
}
