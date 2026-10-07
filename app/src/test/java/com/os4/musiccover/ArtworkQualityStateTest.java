package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArtworkQualityStateTest {
    @Test public void reopeningPillKeepsConfirmedSmallArtworkReady() {
        ArtworkQualityState state = new ArtworkQualityState();
        assertFalse(state.accepted("com.salt.music|Song|Artist|Album", 123, 320, 320));
        state.accept("com.salt.music|Song|Artist|Album", 123, 320, 320);
        assertTrue(state.accepted("com.salt.music|Song|Artist|Album", 123, 320, 320));
        assertTrue(state.accepted("com.salt.music|Song|Artist", 123, 320, 320));
    }

    @Test public void trackAndPlayerChangesCannotReuseReadiness() {
        ArtworkQualityState state = new ArtworkQualityState();
        state.accept("com.salt.music|Song|Artist", 123, 320, 320);
        assertFalse(state.accepted("com.salt.music|Next song|Artist", 123, 320, 320));
        assertFalse(state.accepted("another.player|Song|Artist", 123, 320, 320));
        assertFalse(state.accepted("com.salt.music|Song|Another artist", 123, 320, 320));
    }

    @Test public void videoReplacementAndSizeChangesNeedTheirOwnReadiness() {
        ArtworkQualityState state = new ArtworkQualityState();
        state.accept("player|Song|Artist", 123, 320, 320);
        assertFalse(state.accepted("player|Song|Artist", 456, 320, 320));
        assertFalse(state.accepted("player|Song|Artist", 123, 144, 144));
        assertFalse(state.accepted("player|Song|Artist", 123, 320, 180));
    }

    @Test public void missingTrackIdentityCannotSuppressTheTreatment() {
        ArtworkQualityState state = new ArtworkQualityState();
        state.accept(null, 123, 320, 320);
        assertFalse(state.accepted(null, 123, 320, 320));
        state.accept("player", 123, 320, 320);
        assertFalse(state.accepted("player", 123, 320, 320));
    }
}
