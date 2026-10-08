package com.os4.musiccover;

import org.junit.Test;

import static org.junit.Assert.*;

public class AliveLyricsEffectsTest {

    @Test
    public void trailIntensityIsZeroOutsideWindowAndMonotonicInDecay() {
        int end = 1000;
        assertEquals(0f, AliveLyricsEffects.wordTrailIntensity(AliveLyricsEffects.SUBTLE,
                end, end, 300, 1000), 0f);
        assertEquals(0f, AliveLyricsEffects.wordTrailIntensity(AliveLyricsEffects.SUBTLE,
                end + 321, end, 300, 1000), 0f);
        float a = AliveLyricsEffects.wordTrailIntensity(AliveLyricsEffects.SUBTLE,
                end + 30, end, 300, 1000);
        float b = AliveLyricsEffects.wordTrailIntensity(AliveLyricsEffects.SUBTLE,
                end + 120, end, 300, 1000);
        float c = AliveLyricsEffects.wordTrailIntensity(AliveLyricsEffects.SUBTLE,
                end + 240, end, 300, 1000);
        assertTrue(a >= b && b >= c);
    }

    @Test
    public void longNotesGetStrongerGlowWithinLimits() {
        float shortTrail = AliveLyricsEffects.wordTrailIntensity(AliveLyricsEffects.DRAMATIC,
                1100, 1000, 350, 1000);
        float longTrail = AliveLyricsEffects.wordTrailIntensity(AliveLyricsEffects.DRAMATIC,
                1100, 1000, 2200, 1000);
        assertTrue(longTrail > shortTrail);
        float width = AliveLyricsEffects.glowWidthScale(AliveLyricsEffects.DRAMATIC,
                2800, 1000, 1f);
        assertTrue(width <= 1.45f + 1e-4f);
        assertTrue(width > 1f);
    }

    @Test
    public void effectsSettleInStillModeAndAfterFade() {
        assertEquals(1f, AliveLyricsEffects.translationFade(AliveLyricsEffects.SUBTLE,
                true, true, 80), 0f);
        assertFalse(AliveLyricsEffects.translationNeedsFrame(AliveLyricsEffects.SUBTLE,
                true, false, 1000, 1400));
    }

    @Test
    public void translationCalculationsDoNotTouchTimingArrays() {
        int[] starts = {10, 20, 30};
        int[] ends = {15, 25, 35};
        int[] chars = {1, 2, 3};
        LyricLine l = new LyricLine("abc", "en", 10, 40, false, starts, ends, chars);
        AliveLyricsEffects.translationFade(AliveLyricsEffects.SUBTLE, true, false, 90);
        AliveLyricsEffects.translationLiftDp(AliveLyricsEffects.SUBTLE, 0.4f);
        assertArrayEquals(starts, l.sylStart);
        assertArrayEquals(ends, l.sylEnd);
        assertArrayEquals(chars, l.charEnd);
    }
}
