package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class AodClockOverlapTest {
    @Test public void separatedClockKeepsBigArtwork() {
        assertFalse(AodArtworkPolicy.clockOverlapsArtwork(100, 100, 500, 400, 100, 450, 500, 850));
    }
    @Test public void overlappingClockCollapsesArtwork() {
        assertTrue(AodArtworkPolicy.clockOverlapsArtwork(100, 100, 500, 600, 100, 450, 500, 850));
    }
    @Test public void longClockBesideArtworkDoesNotCollapseIt() {
        assertFalse(AodArtworkPolicy.clockOverlapsArtwork(0, 100, 80, 800, 100, 450, 500, 850));
    }
    @Test public void touchingOrSubpixelEdgesDoNotCollapseArtwork() {
        assertFalse(AodArtworkPolicy.clockOverlapsArtwork(100, 100, 500, 450, 100, 450, 500, 850));
        assertFalse(AodArtworkPolicy.clockOverlapsArtwork(100, 100, 500, 450.5f, 100, 450, 500, 850));
    }
    @Test public void invalidClockMeasurementDoesNotCollapseArtwork() {
        assertFalse(AodArtworkPolicy.clockOverlapsArtwork(100, Float.NaN, 500, 600, 100, 450, 500, 850));
        assertFalse(AodArtworkPolicy.clockOverlapsArtwork(500, 100, 100, 600, 100, 450, 500, 850));
    }
}
