package com.os4.musiccover;

import org.junit.Test;

import static org.junit.Assert.*;

public class ArtworkPageStateTest {
    @Test public void firstLyriclessTrackStartsWithCoverAndCanCollapse() {
        ArtworkPageState state = new ArtworkPageState();
        state.onTrackChanged(false, false);
        assertFalse(state.compactWithoutLyrics(true, false));
        state.preferCompact();
        assertTrue(state.compactWithoutLyrics(true, false));
    }

    @Test public void leavingLyricsKeepsThumbnailAcrossLyriclessTracks() {
        ArtworkPageState state = new ArtworkPageState();
        state.onTrackChanged(true, false);
        assertTrue(state.compactWithoutLyrics(true, false));
        state.onTrackChanged(false, false);
        assertTrue(state.compactWithoutLyrics(true, false));
    }

    @Test public void explicitExpansionKeepsCoverUntilLyricsAreChosenAgain() {
        ArtworkPageState state = new ArtworkPageState();
        state.preferCompact();
        state.preferCover();
        state.onTrackChanged(false, false);
        assertFalse(state.compactWithoutLyrics(true, false));
        state.onTrackChanged(true, false);
        assertTrue(state.compactWithoutLyrics(true, false));
    }

    @Test public void availableLyricsKeepTheirExistingPageBehavior() {
        ArtworkPageState state = new ArtworkPageState();
        state.preferCompact();
        assertFalse(state.compactWithoutLyrics(true, true));
        assertTrue(state.compactWithoutLyrics(true, false));
    }

    @Test public void disablingLyricsAndEndingSessionDoNotForceCompactArtwork() {
        ArtworkPageState state = new ArtworkPageState();
        state.preferCompact();
        assertFalse(state.compactWithoutLyrics(false, false));
        state.onTrackChanged(false, true);
        assertFalse(state.compactWithoutLyrics(true, false));
    }
}
