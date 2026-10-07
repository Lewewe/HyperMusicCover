package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArtworkPredictionPolicyTest {
    @Test public void predictionCanFinishBeforeAndAfterMatchingConfirmation() {
        assertTrue(ArtworkPredictionPolicy.canPublish(4, 4, false, true, true));
        assertTrue(ArtworkPredictionPolicy.canPublish(4, 4, true, false, false));
    }

    @Test public void unexpectedSongRejectsPredictionBeforeNextPushStarts() {
        assertFalse(ArtworkPredictionPolicy.canPublish(4, 4, false, true, false));
    }

    @Test public void rapidSkipRejectsOlderPredictionEvenIfTitlesMatch() {
        assertFalse(ArtworkPredictionPolicy.canPublish(4, 5, true, true, true));
    }

    @Test public void unrelatedArtworkCannotUseTheOutgoingSongAsPermission() {
        assertFalse(ArtworkPredictionPolicy.canPublish(4, 4, false, false, true));
    }
}
