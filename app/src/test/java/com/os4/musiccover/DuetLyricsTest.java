package com.os4.musiccover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class DuetLyricsTest {
    private static LyricLine line(String text, int start, int end) {
        return new LyricLine(text, null, start, end, false, null, null, null);
    }

    @Test public void backingVocalKeepsItsParentInTheDuet() {
        LyricLine lead = line("lead", 1_000, 1_500);
        lead.bg = line("backing", 1_300, 2_000);
        LyricLine answer = line("answer", 1_600, 2_100);
        DuetLyrics duet = new DuetLyrics();
        duet.setLines(Arrays.asList(lead, answer));

        duet.update(1_700, 0, 1);

        assertTrue(duet.active(0, 1_700));
        assertTrue(duet.visible(0));
        assertTrue(duet.visible(1));
        assertEquals(2, duet.count());
    }

    @Test public void thirdVoiceHidesOldestUntilItsPhraseEnds() {
        DuetLyrics duet = new DuetLyrics();
        duet.setLines(Arrays.asList(line("one", 0, 1_000), line("two", 100, 500),
                line("three", 200, 900)));

        duet.update(250, 0, 2);
        assertFalse(duet.visible(0));
        assertTrue(duet.suppressed(0, 250));
        assertTrue(duet.visible(1));
        assertTrue(duet.visible(2));

        duet.update(550, 0, 2);
        assertFalse(duet.visible(0));
        assertTrue(duet.visible(2));
        assertEquals(1, duet.count());
    }
}
