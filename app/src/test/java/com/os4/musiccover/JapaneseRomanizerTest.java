package com.os4.musiccover;

import com.atilika.kuromoji.unidic.Tokenizer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class JapaneseRomanizerTest {
    @Before public void useLocalEngine() {
        LockLyrics.sOnDeviceTransliteration = true;
        JapaneseRomanizer.setTokenizerForTesting(new Tokenizer.Builder().build());
    }

    @After public void resetLocalEngine() {
        LockLyrics.sOnDeviceTransliteration = false;
        JapaneseRomanizer.setTokenizerForTesting(null);
    }

    @Test public void readsWholeJapaneseWordsRatherThanProviderSyllables() {
        assertEquals("kyou mo kimi o omou", JapaneseRomanizer.romanize("今日も君を想う"));
        assertEquals("konnichiwa, sekai!", JapaneseRomanizer.romanize("こんにちは、世界！"));
    }

    @Test public void usesLocalJapaneseButLeavesOtherScriptsToTheirProviderFallback() {
        LyricLine japanese = new LyricLine("祈り", null, "i no ri", 0, 1000,
                false, null, null, null);
        LyricLine chinese = new LyricLine("世界", null, "shi jie", 1000, 2000,
                false, null, null, null);
        JapaneseRomanizer.apply(Arrays.asList(japanese));
        JapaneseRomanizer.apply(Arrays.asList(chinese));
        assertEquals("inori", japanese.displayRoma());
        assertNull(chinese.localRoma);
        assertEquals("shi jie", chinese.displayRoma());
    }

    @Test public void anOldDerivedCacheValueFallsBackUntilItIsRebuilt() {
        LyricLine line = new LyricLine("祈り", null, "i no ri", 0, 1000,
                false, null, null, null);
        line.localRoma = "broken";
        line.localRomaRevision = JapaneseRomanizer.REVISION - 1;
        assertEquals("i no ri", line.displayRoma());
        JapaneseRomanizer.apply(Arrays.asList(line));
        assertEquals("inori", line.displayRoma());
    }
}
