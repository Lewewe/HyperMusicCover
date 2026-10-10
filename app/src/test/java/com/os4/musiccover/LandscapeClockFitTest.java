package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class LandscapeClockFitTest {
    @Test public void shortClockKeepsNativeSizeForLandscapeArtwork() {
        assertTrue(CoverCardStyle.nativeClockFits(16f / 9f, 400, 1, 190, 640, false));
    }
    @Test public void tallClockMustCollapseToLeaveRoomForTheCover() {
        assertFalse(CoverCardStyle.nativeClockFits(16f / 9f, 400, 1, 450, 640, true));
    }
    @Test public void nativeSizeReturnsOnlyWithExtraRoom() {
        float required = (400f - 32f) / (16f / 9f) + 32f;
        assertTrue(CoverCardStyle.nativeClockFits(16f / 9f, 400, 1, 200, 200 + required + 8, true));
        assertFalse(CoverCardStyle.nativeClockFits(16f / 9f, 400, 1, 200, 200 + required + 8, false));
    }
    @Test public void squarePortraitAndMissingGeometryDoNotRequestNativeSize() {
        assertFalse(CoverCardStyle.nativeClockFits(1, 400, 1, 100, 800, true));
        assertFalse(CoverCardStyle.nativeClockFits(0.75f, 400, 1, 100, 800, true));
        assertFalse(CoverCardStyle.nativeClockFits(1.5f, 400, 1, 100, Float.NaN, true));
    }
    @Test public void collapsingLongClockRestoresAMorphEndpoint() {
        CoverCardStyle style = new CoverCardStyle(CoverCardStyle.CARD, 1, 0.5f, 0.12f);
        assertNull(style.place(400, 850, 1, 620, 640, 16f / 9f));
        assertNotNull(style.place(400, 850, 1, 190, 640, 16f / 9f));
    }
}
