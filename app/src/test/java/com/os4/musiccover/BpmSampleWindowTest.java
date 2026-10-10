package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class BpmSampleWindowTest {
    @Test public void rateDoesNotDecayAfterTenMinutesOfRollingSamples() {
        BpmSampleWindow window = new BpmSampleWindow(420, 15000);
        for (int i = 0; i < 12000; i++) window.add(i % 10 == 0 ? 1f : 0f, i * 50L);
        assertEquals(20f, window.sampleRate(), .001f);
        assertEquals(301, window.samples().length);
    }
    @Test public void capacityEvictionKeepsTimestampsAlignedWithValues() {
        BpmSampleWindow window = new BpmSampleWindow(4, 15000);
        for (int i = 0; i < 10; i++) window.add(i, i * 100L);
        assertArrayEquals(new float[]{6,7,8,9}, window.samples(), 0);
        assertEquals(10f, window.sampleRate(), .001f);
    }
    @Test public void resetAndLongCaptureGapDiscardOldTempoSamples() {
        BpmSampleWindow window = new BpmSampleWindow(420, 15000);
        window.add(1, 0); window.add(2, 50); window.add(3, 20000);
        assertArrayEquals(new float[]{3}, window.samples(), 0);
        assertEquals(0, window.sampleRate(), 0);
        window.clear();
        assertEquals(0, window.samples().length);
        window.add(1, 30000); window.add(2, 30050);
        assertEquals(20f, window.sampleRate(), .001f);
    }
    @Test public void irregularSamplesUseTheirRetainedTimeSpan() {
        BpmSampleWindow window = new BpmSampleWindow(4, 15000);
        window.add(1, 100); window.add(2, 150); window.add(3, 250);
        assertEquals(2000f/150f, window.sampleRate(), .001f);
    }
}
