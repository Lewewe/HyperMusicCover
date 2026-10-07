package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArtworkSeekPolicyTest {
    @Test public void bufferingDuringSeekKeepsCoverSize() {
        assertTrue(ArtworkSeekPolicy.suppressTransient(true, 100, 4100, true));
    }
    @Test public void actualTrackChangeStillAnimates() {
        assertFalse(ArtworkSeekPolicy.suppressTransient(true, 100, 4100, false));
    }
    @Test public void pauseAndExpiredSeekAreNotSuppressed() {
        assertFalse(ArtworkSeekPolicy.suppressTransient(false, 100, 4100, true));
        assertFalse(ArtworkSeekPolicy.suppressTransient(true, 4100, 4100, true));
    }
}
