package com.os4.musiccover;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class LyricCacheScopeTest {
    private int mode, translationMode;
    private boolean qq, netease, kuwo, kugou, lrcLib, variants, spicy;
    private String provider, endpoint, source, target, apiKey, spicyKey;

    @Before public void rememberSettings() {
        mode = LockLyrics.sSearchMode;
        qq = LockLyrics.sProviderQq; netease = LockLyrics.sProviderNetease;
        kuwo = LockLyrics.sProviderKuwo; kugou = LockLyrics.sProviderKugou;
        lrcLib = LockLyrics.sProviderLrcLib; variants = LockLyrics.sProviderVariants;
        spicy = LockLyrics.sSpicyLyricsEnabled; spicyKey = LockLyrics.sSpicyLyricsApiKey;
        provider = LockLyrics.sTranslateProvider; endpoint = LockLyrics.sTranslateEndpoint;
        source = LockLyrics.sTranslateSourceLang; target = LockLyrics.sTranslateTargetLang;
        apiKey = LockLyrics.sTranslateApiKey; translationMode = LockLyrics.sOnlineTranslateMode;
    }

    @After public void restoreSettings() {
        LockLyrics.sSearchMode = mode;
        LockLyrics.sProviderQq = qq; LockLyrics.sProviderNetease = netease;
        LockLyrics.sProviderKuwo = kuwo; LockLyrics.sProviderKugou = kugou;
        LockLyrics.sProviderLrcLib = lrcLib; LockLyrics.sProviderVariants = variants;
        LockLyrics.sSpicyLyricsEnabled = spicy; LockLyrics.sSpicyLyricsApiKey = spicyKey;
        LockLyrics.sTranslateProvider = provider; LockLyrics.sTranslateEndpoint = endpoint;
        LockLyrics.sTranslateSourceLang = source; LockLyrics.sTranslateTargetLang = target;
        LockLyrics.sTranslateApiKey = apiKey; LockLyrics.sOnlineTranslateMode = translationMode;
        NextLyrics.configurationChanged();
    }

    @Test public void switchingBackReusesTheSameDiskIdentity() {
        LockLyrics.sSearchMode = LockLyrics.SEARCH_ORIGINAL;
        String original = NextLyrics.configurationKey();
        String track = LyricCacheScope.track(original, "player", "media-id");
        LockLyrics.sSearchMode = LockLyrics.SEARCH_EXTENDED;
        assertNotEquals(track, LyricCacheScope.track(NextLyrics.configurationKey(), "player", "media-id"));
        LockLyrics.sSearchMode = LockLyrics.SEARCH_ORIGINAL;
        assertEquals(track, LyricCacheScope.track(NextLyrics.configurationKey(), "player", "media-id"));
    }

    @Test public void allProviderSelectionsInvalidateReadyResults() {
        String initial = NextLyrics.configurationKey();
        LockLyrics.sProviderQq = !qq;
        assertNotEquals(initial, NextLyrics.configurationKey()); LockLyrics.sProviderQq = qq;
        LockLyrics.sProviderNetease = !netease;
        assertNotEquals(initial, NextLyrics.configurationKey()); LockLyrics.sProviderNetease = netease;
        LockLyrics.sProviderKuwo = !kuwo;
        assertNotEquals(initial, NextLyrics.configurationKey()); LockLyrics.sProviderKuwo = kuwo;
        LockLyrics.sProviderKugou = !kugou;
        assertNotEquals(initial, NextLyrics.configurationKey()); LockLyrics.sProviderKugou = kugou;
        LockLyrics.sProviderLrcLib = !lrcLib;
        assertNotEquals(initial, NextLyrics.configurationKey()); LockLyrics.sProviderLrcLib = lrcLib;
        LockLyrics.sProviderVariants = !variants;
        assertNotEquals(initial, NextLyrics.configurationKey()); LockLyrics.sProviderVariants = variants;
        LockLyrics.sSpicyLyricsEnabled = !spicy;
        assertNotEquals(initial, NextLyrics.configurationKey());
    }

    @Test public void sourceTargetServiceEndpointAndModeAreIndependent() {
        LockLyrics.sTranslateSourceLang = "auto"; LockLyrics.sTranslateTargetLang = "en";
        LockLyrics.sTranslateProvider = TranslationProvider.CUSTOM;
        LockLyrics.sTranslateEndpoint = "https://one.example/translate";
        LockLyrics.sOnlineTranslateMode = 1;
        String initial = NextLyrics.configurationKey();
        LockLyrics.sTranslateTargetLang = "vi";
        assertNotEquals(initial, NextLyrics.configurationKey()); LockLyrics.sTranslateTargetLang = "en";
        LockLyrics.sTranslateSourceLang = "ja";
        assertNotEquals(initial, NextLyrics.configurationKey()); LockLyrics.sTranslateSourceLang = "auto";
        LockLyrics.sTranslateProvider = TranslationProvider.GOOGLE;
        assertNotEquals(initial, NextLyrics.configurationKey()); LockLyrics.sTranslateProvider = TranslationProvider.CUSTOM;
        LockLyrics.sTranslateEndpoint = "https://two.example/translate";
        assertNotEquals(initial, NextLyrics.configurationKey()); LockLyrics.sTranslateEndpoint = "https://one.example/translate";
        LockLyrics.sOnlineTranslateMode = 0;
        assertNotEquals(initial, NextLyrics.configurationKey());
    }

    @Test public void secretsNeverAppearInCacheIdentity() {
        LockLyrics.sTranslateApiKey = "test-secret-translation-credential";
        LockLyrics.sSpicyLyricsApiKey = "test-secret-spicy-credential";
        String key = NextLyrics.configurationKey();
        assertTrue(key.matches("[0-9a-f]{64}"));
        assertFalse(key.contains(LockLyrics.sTranslateApiKey));
        assertFalse(key.contains(LockLyrics.sSpicyLyricsApiKey));
        LockLyrics.sTranslateApiKey += "changed";
        assertNotEquals(key, NextLyrics.configurationKey());
    }

    @Test public void oldRequestCannotCompleteAfterSettingsReturnToTheSameValues() {
        NextLyrics.Context first = NextLyrics.context();
        assertTrue(NextLyrics.isCurrent(first));
        LockLyrics.sTranslateTargetLang = target + "-different";
        assertFalse(NextLyrics.isCurrent(first));
        NextLyrics.configurationChanged();
        LockLyrics.sTranslateTargetLang = target;
        NextLyrics.configurationChanged();
        assertEquals(first.configuration, NextLyrics.configurationKey());
        assertFalse(NextLyrics.isCurrent(first));
        assertTrue(NextLyrics.isCurrent(NextLyrics.context()));
    }

    @Test public void changingSettingsDoesNotChangePlayerAndTrackSeparation() {
        String config = NextLyrics.configurationKey();
        assertNotEquals(LyricCacheScope.track(config, "player-a", "same-id"),
                LyricCacheScope.track(config, "player-b", "same-id"));
        assertNotEquals(LyricCacheScope.track(config, "player", "song-a"),
                LyricCacheScope.track(config, "player", "song-b"));
        assertNotEquals(LyricCacheScope.digest("a|b", "c"), LyricCacheScope.digest("a", "b|c"));
    }

    @Test public void nativeSourceSurvivesOnlineCacheHandoffWithoutOldTranslation() {
        int[] starts = {1000}, ends = {1900}, chars = {4};
        LyricLine line = new LyricLine("song", "native", "romaji", 1000, 2000,
                true, starts, ends, chars);
        line.onlineTranslation = "old-language";
        line.bg = new LyricLine("background", null, 1000, 2000, false, null, null, null);
        line.bg.onlineTranslation = "old-background";
        LyricLine copy = QueuedLyricCache.withoutOnline(Collections.singletonList(line)).get(0);
        assertEquals("native", copy.translation); assertEquals("romaji", copy.roma);
        assertNull(copy.onlineTranslation); assertNull(copy.bg.onlineTranslation);
        assertSame(starts, copy.sylStart); assertSame(ends, copy.sylEnd); assertSame(chars, copy.charEnd);
        assertEquals(1000, copy.start); assertEquals(2000, copy.end); assertTrue(copy.opposite);
        assertEquals("old-language", line.onlineTranslation);
        assertEquals("old-background", line.bg.onlineTranslation);
    }
}
