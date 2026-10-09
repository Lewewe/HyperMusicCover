package com.os4.musiccover;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class LyricTranslationLogicTest {

    private static LyricLine line(String text, int start) {
        return new LyricLine(text, null, start, start + 1000, false,
                new int[]{start, start + 200}, new int[]{start + 180, start + 380}, new int[]{1, 2});
    }

    @Test
    public void batchesCarryStableIdsAndRespectLimits() {
        List<LyricLine> lines = Arrays.asList(
                line("第一行", 1000),
                line("第二行", 2000),
                line("第三行", 3000)
        );
        List<LyricTranslationLogic.Entry> entries = LyricTranslationLogic.entriesOf(lines);
        assertEquals(3, entries.size());
        assertEquals("3e8-0", entries.get(0).id);
        List<LyricTranslationLogic.Batch> batches = LyricTranslationLogic.batches(entries, 32, 2);
        assertEquals(2, batches.size());
        assertTrue(batches.get(0).payload.contains("[[MCID:3e8-0]]"));
        assertTrue(batches.get(0).payload.contains("[[MCID:7d0-1]]"));
        assertTrue(batches.get(1).payload.contains("[[MCID:bb8-2]]"));
    }

    @Test
    public void skipsLyricsAlreadyInTargetLanguageConservatively() {
        assertTrue(LyricTranslationLogic.entriesForTranslation(
                Arrays.asList(line("bonjour", 0)), "en-US", "en-GB").isEmpty());
        assertTrue(LyricTranslationLogic.entriesForTranslation(
                Arrays.asList(line("I love you and I need you", 0)), "auto", "en").isEmpty());
        assertTrue(LyricTranslationLogic.entriesForTranslation(
                Arrays.asList(line("こんにちは世界", 0)), "auto", "ja").isEmpty());
        assertEquals(1, LyricTranslationLogic.entriesForTranslation(
                Arrays.asList(line("Je t'aime, mon amour", 0)), "auto", "en").size());
        assertEquals(1, LyricTranslationLogic.entriesForTranslation(
                Arrays.asList(line("hello", 0)), "auto", "de").size());
    }

    @Test
    public void translatesProviderRomajiAsJapanese() {
        List<LyricLine> lines = Arrays.asList(
                line("Owarasenai de Toroimerai", 0),
                line("Tsuki no hikari o tsukandara", 1000),
                line("Byakuya no machi kakedashite", 2000),
                line("Tsunawatari tamaranai", 3000),
                line("[english] kimatte nasumai", 4000),
                line("Don't be lazy", 5000)
        );
        List<LyricTranslationLogic.Entry> entries =
                LyricTranslationLogic.entriesForTranslation(lines, "auto", "en");
        assertEquals(6, entries.size());
        assertEquals("Owarasenai de Toroimerai", entries.get(0).text);
        assertEquals("Tsuki no hikari o tsukandara", entries.get(1).text);
        assertEquals("Byakuya no machi kakedashite", entries.get(2).text);
        assertEquals("Don't be lazy", entries.get(5).text);
    }

    @Test
    public void doesNotClassifyCommonEnglishAsRomaji() {
        List<LyricLine> lines = Arrays.asList(
                line("You and me", 0),
                line("I want to know", 1000),
                line("You know what I mean", 2000)
        );
        assertEquals(0, LyricTranslationLogic.entriesForTranslation(lines, "auto", "en").size());
    }

    @Test
    public void responseMappingUsesIdsNotInputOrder() {
        String translated = "[[MCID:bb8-2]] third\n[[MCID:3e8-0]] first";
        Map<String, String> mapped = LyricTranslationLogic.parseMarkedTranslation(translated);
        assertEquals("third", mapped.get("bb8-2"));
        assertEquals("first", mapped.get("3e8-0"));
        assertNull(mapped.get("7d0-1"));
    }

    @Test
    public void mergeKeepsPrimaryTimingArraysUntouched() {
        LyricLine original = line("你好", 1000);
        List<LyricLine> merged = LyricTranslationLogic.merge(
                Arrays.asList(original),
                java.util.Collections.singletonMap("3e8-0", "Hello")
        );
        assertEquals(1, merged.size());
        LyricLine out = merged.get(0);
        assertEquals("你好", out.text);
        assertNull(out.translation);
        assertEquals("Hello", out.onlineTranslation);
        assertSame(original.sylStart, out.sylStart);
        assertSame(original.sylEnd, out.sylEnd);
        assertSame(original.charEnd, out.charEnd);
        assertEquals(original.start, out.start);
        assertEquals(original.end, out.end);
    }

    @Test
    public void onlineTranslationUsesOriginalTextAndPreservesNativeSecondaryText() {
        LyricLine nativeLine = new LyricLine("你好", "ni hao\n你好", 0, 1000,
                true, null, null, null);
        LyricLine untranslated = line("bonjour", 1000);
        untranslated.bg = line("background", 1100);
        List<LyricLine> base = Arrays.asList(nativeLine, untranslated);
        List<LyricTranslationLogic.Entry> entries = LyricTranslationLogic.entriesOf(base);
        assertEquals(2, entries.size());
        assertEquals(0, entries.get(0).index);
        assertEquals("你好", entries.get(0).text);
        assertFalse(entries.get(0).text.contains("ni hao"));
        assertEquals(1, entries.get(1).index);
        Map<String, String> mapped = new java.util.HashMap<>();
        mapped.put("0-0", "Hello");
        mapped.put("3e8-1", "bonjour translated");
        List<LyricLine> merged = LyricTranslationLogic.merge(base, mapped);
        assertEquals("ni hao\n你好", merged.get(0).translation);
        assertEquals("Hello", merged.get(0).onlineTranslation);
        assertNotSame(nativeLine, merged.get(0));
        assertEquals("bonjour translated", merged.get(1).onlineTranslation);
        assertNull(merged.get(1).translation);
        assertSame(untranslated.bg, merged.get(1).bg);
        assertSame(untranslated.sylStart, merged.get(1).sylStart);
    }

    @Test
    public void onlineDisplayHidesProviderTranslationUntilOnlineResultArrives() {
        int previous = LockLyrics.sOnlineTranslateMode;
        try {
            LockLyrics.setTranslateMode(LockLyrics.TR_MODE_ORIGINAL_AND_TRANSLATION);
            LyricLine providerLine = new LyricLine("original", "provider Chinese", "romaji",
                    0, 1000, false, null, null, null);
            providerLine.onlineTranslation = "English";
            List<LyricLine> displayed = LockLyrics.displayLines(Arrays.asList(providerLine));
            assertEquals(1, displayed.size());
            assertEquals("provider Chinese", displayed.get(0).translation);
            assertEquals("romaji", displayed.get(0).roma);
            assertEquals("English", displayed.get(0).onlineTranslation);
            assertEquals("English", LyricTranslationLogic.translationFor(displayed.get(0),
                    LockLyrics.TR_MODE_ORIGINAL_AND_TRANSLATION));

            providerLine.onlineTranslation = null;
            assertNull(LyricTranslationLogic.translationFor(providerLine,
                    LockLyrics.TR_MODE_ORIGINAL_AND_TRANSLATION));

            LockLyrics.setTranslateMode(LockLyrics.TR_MODE_OFF);
            assertSame(providerLine, LockLyrics.displayLines(Arrays.asList(providerLine)).get(0));
        } finally {
            LockLyrics.setTranslateMode(previous);
        }
    }

    @Test
    public void excludesEnglishLinesInsideForeignLyrics() {
        List<LyricTranslationLogic.Entry> entries = LyricTranslationLogic.entriesForTranslation(
                Arrays.asList(line("ã“ã‚“ã«ã¡ã¯", 0), line("The world was only just a dream", 1000),
                        line("ã•ã‚ˆã†ãªã‚‰", 2000)), "auto", "en");
        assertEquals(2, entries.size());
        assertEquals("ã“ã‚“ã«ã¡ã¯", entries.get(0).text);
        assertEquals("ã•ã‚ˆã†ãªã‚‰", entries.get(1).text);
    }

    @Test
    public void excludesUppercaseEnglishAndHidesNoOpTranslation() {
        assertTrue(LyricTranslationLogic.entriesForTranslation(
                Arrays.asList(line("SHOW TIME, SO FLY", 0)), "auto", "en").isEmpty());
        LyricLine line = line("SHOW TIME, SO FLY", 0);
        line.onlineTranslation = "Show time, so fly";
        assertNull(LyricTranslationLogic.translationFor(line,
                LockLyrics.TR_MODE_ORIGINAL_AND_TRANSLATION));
    }

    @Test
    public void officialResponsesMapSeparateStringsIncludingRepeatedLines() {
        List<LyricTranslationLogic.Entry> entries = LyricTranslationLogic.entriesOf(
                Arrays.asList(line("bonjour", 0), line("bonjour", 1000)));
        Map<String, String> google = LyricTranslationLogic.parseCloudResponse(
                "{\"data\":{\"translations\":[{\"translatedText\":\"hello\"},{\"translatedText\":\"hi\"}]}}",
                entries, true);
        assertEquals("hello", google.get("0-0"));
        assertEquals("hi", google.get("3e8-1"));
        Map<String, String> deepL = LyricTranslationLogic.parseCloudResponse(
                "{\"translations\":[{\"text\":\"hello\"},{\"text\":\"hi\"}]}", entries, false);
        assertEquals(google, deepL);
        assertTrue(LyricTranslationLogic.parseCloudResponse(
                "{\"translations\":[{\"text\":\"only one\"}]}", entries, false).isEmpty());
        assertTrue(LyricTranslationLogic.parseCloudResponse("not JSON", entries, true).isEmpty());
        assertTrue(LyricTranslationLogic.parseCloudResponse(
                "{\"translations\":[{\"text\":\"ok\"},{}]}", entries, false).isEmpty());
    }

    @Test
    public void cacheKeyDependsOnEndpointAndLanguages() {
        List<LyricLine> lines = Arrays.asList(line("你好", 1000));
        String a = LyricTranslationLogic.cacheKey("pkg|a|b|1", "https://x/y/", "auto", "en", lines);
        String b = LyricTranslationLogic.cacheKey("pkg|a|b|1", "https://x/y", "auto", "en", lines);
        String c = LyricTranslationLogic.cacheKey("pkg|a|b|1", "https://x/y", "zh", "en", lines);
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    public void malformedAndPartialResponsesAreSafe() {
        assertTrue(LyricTranslationLogic.parseLibreResponse("not-json").isEmpty());
        Map<String, String> partial = LyricTranslationLogic.parseLibreResponse(
                "{\"translatedText\":\"[[MCID:3e8-0]] hello\"}"
        );
        assertEquals(1, partial.size());
        assertEquals("hello", partial.get("3e8-0"));
    }
}
