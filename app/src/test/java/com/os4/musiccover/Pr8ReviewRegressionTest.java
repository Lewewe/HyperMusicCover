package com.os4.musiccover;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

public class Pr8ReviewRegressionTest {
    private boolean master, japanese, korean, chinese, cyrillic, greek;
    @Before public void saveSettings() {
        master = LockLyrics.sOnDeviceTransliteration;
        japanese = LockLyrics.sLocalJapanese;
        korean = LockLyrics.sLocalKorean;
        chinese = LockLyrics.sLocalChinese;
        cyrillic = LockLyrics.sLocalCyrillic;
        greek = LockLyrics.sLocalGreek;
    }
    @After public void restoreSettings() {
        LockLyrics.sOnDeviceTransliteration = master;
        LockLyrics.sLocalJapanese = japanese;
        LockLyrics.sLocalKorean = korean;
        LockLyrics.sLocalChinese = chinese;
        LockLyrics.sLocalCyrillic = cyrillic;
        LockLyrics.sLocalGreek = greek;
    }

    @Test public void disablingLocalEnginePreservesJapaneseProviderRomaji() {
        LockLyrics.sOnDeviceTransliteration = false;
        LyricLine line = new LyricLine("祈り", null, "i no ri", 0, 1000,
                false, null, null, null);
        assertEquals("i no ri", line.renderedRoma());
    }

    @Test public void turningOffKoreanEngineHidesPreviouslyGeneratedKoreanReading() {
        LockLyrics.sOnDeviceTransliteration = true;
        LockLyrics.sLocalKorean = false;
        LyricLine line = new LyricLine("안녕", null, "provider", 0, 1000,
                false, null, null, null);
        line.localRoma = "annyeong";
        line.localRomaRevision = LocalRomanizer.REVISION;
        line.localRomaSettings = LocalRomanizer.settingsMask();
        assertEquals("provider", line.renderedRoma());
    }

    @Test public void lineTimedDuetCanHaveMatchingStartTimestamps() throws Exception {
        Class<?> type = Class.forName("com.os4.musiccover.LyricSource$Rows");
        Constructor<?> ctor = type.getDeclaredConstructor(); ctor.setAccessible(true);
        Object rows = ctor.newInstance();
        Field lines = type.getDeclaredField("lines"); lines.setAccessible(true);
        lines.set(rows, Arrays.asList(
                new LyricLine("Lead", null, 1000, 3000, false, null, null, null),
                new LyricLine("Answer", null, 1000, 3000, true, null, null, null)));
        Method filter = LyricSource.class.getDeclaredMethod("dropUntimedResult", type);
        filter.setAccessible(true);
        assertFalse("A valid duet must not be discarded", (Boolean) filter.invoke(null, rows));
    }
    @Test public void missingOrObsoleteLocalReadingUsesProviderFallback() {
        LockLyrics.sOnDeviceTransliteration = true;
        LyricLine line = new LyricLine("祈り", null, "provider", 0, 1000,
                false, null, null, null);
        assertEquals("provider", line.renderedRoma());
        line.localRoma = "old";
        line.localRomaRevision = LocalRomanizer.REVISION - 1;
        assertEquals("provider", line.renderedRoma());
        line.localRoma = "";
        line.localRomaRevision = LocalRomanizer.REVISION;
        line.localRomaSettings = LocalRomanizer.settingsMask();
        assertEquals("provider", line.renderedRoma());
    }

    @Test public void enabledCachedReadingsRemainUsableAndMasterOffRestoresProvider() {
        LockLyrics.sOnDeviceTransliteration = true;
        LockLyrics.sLocalKorean = true;
        LyricLine line = new LyricLine("안녕", null, "provider", 0, 1000,
                false, null, null, null);
        line.localRoma = "annyeong";
        line.localRomaRevision = LocalRomanizer.REVISION;
        line.localRomaSettings = LocalRomanizer.settingsMask();
        assertEquals("annyeong", line.renderedRoma());
        assertEquals("annyeong", line.under(LockLyrics.BELOW_ROMA));
        LockLyrics.sOnDeviceTransliteration = false;
        assertEquals("provider", line.renderedRoma());
        assertEquals("provider", line.under(LockLyrics.BELOW_ROMA));
    }

    @Test public void invalidResultsRemainEligibleForAnotherSource() throws Exception {
        Object rows = rows(Arrays.asList(
                new LyricLine("Later", null, 2000, 4000, false, null, null, null),
                new LyricLine("Earlier", null, 1000, 3000, false, null, null, null)));
        assertFalse(sanitize(rows));
        assertTrue(linesOf(rows).isEmpty());
    }

    @Test public void confirmedInstrumentalsStillStopFallback() throws Exception {
        Object rows = rows(Collections.singletonList(
                new LyricLine("instrumental", null, 0, 1000, false, null, null, null)));
        assertTrue(sanitize(rows));
        assertTrue(linesOf(rows).isEmpty());
    }

    @Test public void sequentialAndWordTimedLyricsAreRetained() throws Exception {
        Object sequential = rows(Arrays.asList(
                new LyricLine("First", null, 1000, 2000, false, null, null, null),
                new LyricLine("Second", null, 2000, 3000, false, null, null, null)));
        assertFalse(sanitize(sequential));
        assertEquals(2, linesOf(sequential).size());
        Object wordTimed = rows(Collections.singletonList(new LyricLine("Hi", null,
                0, 1000, false, new int[]{0}, new int[]{1000}, new int[]{2})));
        assertFalse(sanitize(wordTimed));
        assertEquals(1, linesOf(wordTimed).size());
    }

    @Test public void everyLocalScriptSwitchScopesPrefetchResults() {
        String key = NextLyrics.configurationKey();
        LockLyrics.sOnDeviceTransliteration = !master;
        assertNotEquals(key, NextLyrics.configurationKey());
        LockLyrics.sOnDeviceTransliteration = master;
        LockLyrics.sLocalKorean = !korean;
        assertNotEquals(key, NextLyrics.configurationKey());
        LockLyrics.sLocalKorean = korean;
        LockLyrics.sLocalChinese = !chinese;
        assertNotEquals(key, NextLyrics.configurationKey());
        LockLyrics.sLocalChinese = chinese;
        LockLyrics.sLocalCyrillic = !cyrillic;
        assertNotEquals(key, NextLyrics.configurationKey());
        LockLyrics.sLocalCyrillic = cyrillic;
        LockLyrics.sLocalGreek = !greek;
        assertNotEquals(key, NextLyrics.configurationKey());
        LockLyrics.sLocalGreek = greek;
        assertEquals(key, NextLyrics.configurationKey());
    }

    @Test public void disablingChineseAndOtherScriptsIgnoresTheirCachedReading() {
        LockLyrics.sOnDeviceTransliteration = true;
        LockLyrics.sLocalChinese = false;
        LockLyrics.sLocalCyrillic = false;
        LockLyrics.sLocalGreek = false;
        for (String text : Arrays.asList("世界", "Привет", "κόσμος")) {
            LyricLine line = new LyricLine(text, null, "provider", 0, 1000,
                    false, null, null, null);
            line.localRoma = "cached";
            line.localRomaRevision = LocalRomanizer.REVISION;
        line.localRomaSettings = LocalRomanizer.settingsMask();
            assertEquals("provider", line.renderedRoma());
        }
    }

    @Test public void changingOneScriptRegeneratesMixedLanguageReading() {
        LockLyrics.sOnDeviceTransliteration = true;
        LockLyrics.sLocalKorean = true;
        LockLyrics.sLocalCyrillic = true;
        LyricLine line = new LyricLine("안녕 Привет", null, "provider", 0, 1000,
                false, null, null, null);
        List<LyricLine> lines = Collections.singletonList(line);
        SimpleScriptRomanizer.apply(lines);
        assertEquals("annyeong Privet", line.renderedRoma());
        LockLyrics.sLocalKorean = false;
        assertEquals("provider", line.renderedRoma());
        assertTrue(SimpleScriptRomanizer.needsApply(lines));
        SimpleScriptRomanizer.apply(lines);
        assertEquals("안녕 Privet", line.renderedRoma());
    }

    @Test public void obsoleteAsyncResultDoesNotMutateVisibleLyrics() {
        LockLyrics.sOnDeviceTransliteration = true;
        LockLyrics.sLocalKorean = true;
        LyricLine line = new LyricLine("안녕", null, "provider", 0, 1000,
                false, null, null, null);
        line.onlineTranslation = "Hello";
        line.bg = new LyricLine("안녕", null, 0, 1000, false, null, null, null);
        List<LyricLine> visible = Collections.singletonList(line);
        List<LyricLine> privateLines = LocalRomanizer.copyLines(visible);
        NextLyrics.Context context = NextLyrics.context();
        SimpleScriptRomanizer.apply(privateLines);
        assertNull(line.localRoma);
        assertNull(line.bg.localRoma);
        LockLyrics.sLocalKorean = false;
        assertFalse(NextLyrics.publishRomanization(visible, privateLines, context));
        assertNull(line.localRoma);
        assertNull(line.bg.localRoma);
        assertEquals("Hello", line.onlineTranslation);
    }

    @Test public void currentAsyncResultPublishesOnlyReadingsIncludingBackgroundVoice() {
        LockLyrics.sOnDeviceTransliteration = true;
        LockLyrics.sLocalKorean = true;
        LyricLine line = new LyricLine("안녕", null, "provider", 0, 1000,
                false, null, null, null);
        line.bg = new LyricLine("안녕", null, 0, 1000, false, null, null, null);
        List<LyricLine> visible = Collections.singletonList(line);
        List<LyricLine> privateLines = LocalRomanizer.copyLines(visible);
        NextLyrics.Context context = NextLyrics.context();
        SimpleScriptRomanizer.apply(privateLines);
        line.onlineTranslation = "New translation";
        assertTrue(NextLyrics.publishRomanization(visible, privateLines, context));
        assertEquals("annyeong", line.renderedRoma());
        assertEquals("annyeong", line.bg.renderedRoma());
        assertEquals("New translation", line.onlineTranslation);
    }

    @Test public void koreanFinalConsonantsUseTheCorrectSyllableIndex() {
        LockLyrics.sOnDeviceTransliteration = true;
        LockLyrics.sLocalKorean = true;
        String[] expected = {"", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "p",
                "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t"};
        for (int tail = 0; tail < expected.length; tail++) {
            LyricLine line = new LyricLine(String.valueOf((char) (0xac00 + tail)), null,
                    0, 1000, false, null, null, null);
            SimpleScriptRomanizer.apply(Collections.singletonList(line));
            assertEquals("Final consonant " + tail, "ga" + expected[tail], line.localRoma);
        }
    }

    @Test public void removingDictionaryReadingsRestoresProviderForBothVoices() {
        LockLyrics.sOnDeviceTransliteration = true;
        LyricLine line = new LyricLine("祈り", null, "provider", 0, 1000,
                false, null, null, null);
        line.localRoma = "inori";
        line.localRomaRevision = LocalRomanizer.REVISION;
        line.localRomaSettings = LocalRomanizer.settingsMask();
        line.bg = new LyricLine("祈り", null, "background", 0, 1000,
                false, null, null, null);
        line.bg.localRoma = "inori";
        line.bg.localRomaRevision = LocalRomanizer.REVISION;
        line.bg.localRomaSettings = LocalRomanizer.settingsMask();
        LocalRomanizer.invalidateReadings(Collections.singletonList(line));
        assertEquals("provider", line.renderedRoma());
        assertEquals("background", line.bg.renderedRoma());
    }

    @Test public void serializedReadingRetainsSettingsAndRejectsLegacyCache() throws Exception {
        LockLyrics.sOnDeviceTransliteration = true;
        LockLyrics.sLocalKorean = true;
        LyricLine line = new LyricLine("안녕", null, "provider", 0, 1000,
                false, null, null, null);
        SimpleScriptRomanizer.apply(Collections.singletonList(line));
        Method encode = QueuedLyricCache.class.getDeclaredMethod("json", LyricLine.class);
        encode.setAccessible(true);
        Method decode = QueuedLyricCache.class.getDeclaredMethod("line", org.json.JSONObject.class);
        decode.setAccessible(true);
        org.json.JSONObject json = (org.json.JSONObject) encode.invoke(null, line);
        LyricLine restored = (LyricLine) decode.invoke(null, json);
        assertEquals("annyeong", restored.renderedRoma());
        assertEquals(LocalRomanizer.settingsMask(), restored.localRomaSettings);
        LyricLine nativeCopy = QueuedLyricCache.withoutOnline(Collections.singletonList(restored)).get(0);
        assertEquals("annyeong", nativeCopy.renderedRoma());
        json.remove("localRomaSettings");
        LyricLine legacy = (LyricLine) decode.invoke(null, json);
        assertEquals("provider", legacy.renderedRoma());
        assertTrue(SimpleScriptRomanizer.needsApply(Collections.singletonList(legacy)));
    }

    @Test public void sameSettingsWithExpiredDictionaryRevisionCannotPublish() {
        LyricLine line = new LyricLine("祈り", null, "provider", 0, 1000,
                false, null, null, null);
        List<LyricLine> visible = Collections.singletonList(line);
        List<LyricLine> privateLines = LocalRomanizer.copyLines(visible);
        privateLines.get(0).localRoma = "expired";
        NextLyrics.Context now = NextLyrics.context();
        NextLyrics.Context expired = new NextLyrics.Context(now.configuration, now.revision - 1);
        assertFalse(NextLyrics.publishRomanization(visible, privateLines, expired));
        assertNull(line.localRoma);
    }

    @Test public void downloadedJapaneseDictionaryCanBeDisabledWithoutRemovingReading() {
        LockLyrics.sOnDeviceTransliteration = true;
        LockLyrics.sLocalJapanese = true;
        LockLyrics.sLocalChinese = false;
        LyricLine line = new LyricLine("祈り", null, "provider", 0, 1000,
                false, null, null, null);
        line.localRoma = "inori";
        line.localRomaRevision = LocalRomanizer.REVISION;
        line.localRomaSettings = LocalRomanizer.settingsMask();
        String key = NextLyrics.configurationKey();
        assertEquals("inori", line.renderedRoma());
        LockLyrics.sLocalJapanese = false;
        assertEquals("provider", line.renderedRoma());
        assertEquals("inori", line.localRoma);
        assertFalse(JapaneseRomanizer.needsApply(Collections.singletonList(line)));
        assertNotEquals(key, NextLyrics.configurationKey());
        LockLyrics.sLocalJapanese = true;
        assertEquals("inori", line.renderedRoma());
        assertEquals(key, NextLyrics.configurationKey());
    }

    private static Object rows(List<LyricLine> lines) throws Exception {
        Class<?> type = Class.forName("com.os4.musiccover.LyricSource$Rows");
        Constructor<?> ctor = type.getDeclaredConstructor(); ctor.setAccessible(true);
        Object rows = ctor.newInstance();
        Field field = type.getDeclaredField("lines"); field.setAccessible(true);
        field.set(rows, lines);
        return rows;
    }
    private static boolean sanitize(Object rows) throws Exception {
        Method method = LyricSource.class.getDeclaredMethod("sanitizeResult", rows.getClass());
        method.setAccessible(true);
        return (Boolean) method.invoke(null, rows);
    }
    private static List<?> linesOf(Object rows) throws Exception {
        Field field = rows.getClass().getDeclaredField("lines"); field.setAccessible(true);
        return (List<?>) field.get(rows);
    }

}
