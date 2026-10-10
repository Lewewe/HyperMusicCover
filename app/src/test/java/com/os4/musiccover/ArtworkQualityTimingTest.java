package com.os4.musiccover;

import org.junit.Test;

import static org.junit.Assert.*;

public class ArtworkQualityTimingTest {
    @Test public void pollingUsesOriginalHalfSecondCadence() {
        for (int remaining = CoverPush.QUALITY_CHECKS; remaining > 0; remaining--) {
            assertEquals(500L, CoverPush.artworkQualityDelayMs(remaining));
        }
    }

    @Test public void pollingRetainsBoundedFiveSecondWindow() {
        long elapsed = 0L;
        for (int remaining = CoverPush.QUALITY_CHECKS; remaining > 0; remaining--) {
            elapsed += CoverPush.artworkQualityDelayMs(remaining);
        }
        assertEquals(5000L, elapsed);
        assertEquals(10, CoverPush.QUALITY_CHECKS);
    }

    @Test public void thumbnailsAreSoftenedButFullSizeArtworkIsNot() {
        assertTrue(CoverPush.shouldSoftenArtwork(144, 144));
        assertTrue(CoverPush.shouldSoftenArtwork(320, 180));
        assertTrue(CoverPush.shouldSoftenArtwork(180, 320));
        assertFalse(CoverPush.shouldSoftenArtwork(512, 512));
        assertFalse(CoverPush.shouldSoftenArtwork(512, 288));
        assertFalse(CoverPush.shouldSoftenArtwork(288, 512));
        assertFalse(CoverPush.shouldSoftenArtwork(640, 360));
        assertFalse(CoverPush.shouldSoftenArtwork(360, 640));
        assertFalse(CoverPush.shouldSoftenArtwork(480, 270));
        assertFalse(CoverPush.shouldSoftenArtwork(270, 480));
        assertTrue(CoverPush.shouldSoftenArtwork(479, 269));
        assertTrue(CoverPush.shouldSoftenArtwork(479, 479));
        assertFalse(CoverPush.shouldSoftenArtwork(480, 480));
        assertFalse(CoverPush.shouldSoftenArtwork(500, 500));
        assertFalse(CoverPush.shouldSoftenArtwork(1024, 1024));
    }

    @Test public void neteaseFinalSquareArtworkDoesNotWaitForAnUpgrade() {
        assertFalse(CoverPush.shouldSoftenArtwork("com.netease.cloudmusic", 363, 363));
        assertFalse(CoverPush.shouldSoftenArtwork("com.netease.cloudmusic", 360, 360));
        assertFalse(CoverPush.shouldSoftenArtwork("com.netease.cloudmusic", 512, 512));
    }

    @Test public void neteaseSmallAndRectangularThumbnailsStillWait() {
        assertTrue(CoverPush.shouldSoftenArtwork("com.netease.cloudmusic", 144, 144));
        assertTrue(CoverPush.shouldSoftenArtwork("com.netease.cloudmusic", 359, 359));
        assertTrue(CoverPush.shouldSoftenArtwork("com.netease.cloudmusic", 363, 204));
        assertFalse(CoverPush.shouldSoftenArtwork("com.netease.cloudmusic", 0, 0));
    }

    @Test public void otherPlayersRetainTheirExistingReadinessThreshold() {
        assertTrue(CoverPush.shouldSoftenArtwork("com.spotify.music", 363, 363));
        assertTrue(CoverPush.shouldSoftenArtwork("com.google.android.apps.youtube.music", 363, 363));
        assertTrue(CoverPush.shouldSoftenArtwork(null, 363, 363));
        assertFalse(CoverPush.shouldSoftenArtwork("com.spotify.music", 500, 500));
    }

    @Test public void invalidArtworkIsNotSoftened() {
        assertFalse(CoverPush.shouldSoftenArtwork(0, 144));
        assertFalse(CoverPush.shouldSoftenArtwork(144, 0));
        assertFalse(CoverPush.shouldSoftenArtwork(-1, 144));
    }
}
