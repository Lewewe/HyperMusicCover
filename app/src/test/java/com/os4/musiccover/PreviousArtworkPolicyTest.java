package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreviousArtworkPolicyTest {
    @Test public void restartAfterFiveSecondsNeverStartsTheEffect() {
        assertTrue(PreviousArtworkPolicy.shouldAnimate(0L, 100L, 1f, false, 100L));
        assertTrue(PreviousArtworkPolicy.shouldAnimate(5000L, 100L, 1f, false, 100L));
        assertFalse(PreviousArtworkPolicy.shouldAnimate(5001L, 100L, 1f, false, 100L));
        assertFalse(PreviousArtworkPolicy.shouldAnimate(60000L, 100L, 1f, false, 100L));
    }

    @Test public void playingPositionAdvancesBeforeTheButtonPress() {
        assertFalse(PreviousArtworkPolicy.shouldAnimate(4800L, 10000L, 1f, true, 10500L));
        assertTrue(PreviousArtworkPolicy.shouldAnimate(4800L, 10000L, 1f, false, 10500L));
        assertFalse(PreviousArtworkPolicy.shouldAnimate(4500L, 10000L, 2f, true, 10300L));
    }

    @Test public void UnknownPositionDoesNotFlashTheCover() {
        assertFalse(PreviousArtworkPolicy.shouldAnimate(-1L, 100L, 1f, true, 1000L));
        assertTrue(PreviousArtworkPolicy.shouldAnimate(4000L, 0L, 1f, true, 100000L));
    }
}
