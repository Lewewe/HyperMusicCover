package com.os4.musiccover;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BpmEstimatorTest {
    @Test
    public void tempoCombPrefersBeatOverHalfTime() {
        float rate = 20f;
        float[] onset = new float[360];
        for (int i = 0; i < onset.length; i++) {
            if (i % 7 == 0 || i % 14 == 0) onset[i] = 1f;
        }

        assertEquals(171, BpmEstimator.estimateTempo(onset, rate), 3);
    }

    @Test
    public void quietOrFlatSignalDoesNotInventTempo() {
        assertEquals(0, BpmEstimator.estimateTempo(new float[360], 20f));
    }

    @Test
    public void halfTimeCandidateCanResolveToOneHundredThirtyBpm() {
        float rate = 20f;
        float[] onset = new float[360];
        int beatSpacing = Math.round(rate * 60f / 130f);
        for (int i = 0; i < onset.length; i++) {
            if (i % beatSpacing == 0) onset[i] = 1f;
        }

        assertEquals(130, BpmEstimator.estimateTempo(onset, rate), 4);
    }
}
