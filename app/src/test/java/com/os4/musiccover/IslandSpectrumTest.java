package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class IslandSpectrumTest {
    @Test public void silenceAndResetClearPreviousAudio() {
        IslandSpectrum spectrum = new IslandSpectrum();
        byte[] fft = new byte[1024];
        fft[4] = 100;
        spectrum.sample(fft, 48000000);
        float[] levels = new float[5];
        spectrum.copyTo(levels);
        assertTrue(levels[0] > 0);
        spectrum.sample(new byte[1024], 48000000);
        spectrum.copyTo(levels);
        assertArrayEquals(new float[5], levels, 0f);
        spectrum.sample(fft, 48000000);
        spectrum.reset();
        spectrum.copyTo(levels);
        assertArrayEquals(new float[5], levels, 0f);
    }

    @Test public void frequenciesFollowSamplingRateAndDoNotLeakIntoOtherBands() {
        for (int rate : new int[] {44100000, 48000000}) {
            IslandSpectrum spectrum = new IslandSpectrum();
            byte[] fft = new byte[1024];
            int bin = Math.round(1000f * fft.length * 1000f / rate);
            fft[bin * 2] = 100;
            spectrum.sample(fft, rate);
            float[] levels = new float[5];
            spectrum.copyTo(levels);
            assertTrue(levels[2] > 0);
            for (int i = 0; i < 5; i++) if (i != 2) assertEquals(0f, levels[i], 0f);
        }
    }

    @Test public void invalidCaptureAndDcDoNotProduceFakeMovement() {
        IslandSpectrum spectrum = new IslandSpectrum();
        byte[] fft = new byte[1024];
        fft[0] = 127;
        fft[1] = 127;
        spectrum.sample(fft, 48000000);
        float[] levels = new float[5];
        spectrum.copyTo(levels);
        assertArrayEquals(new float[5], levels, 0f);
        spectrum.sample(null, 0);
        spectrum.copyTo(levels);
        assertArrayEquals(new float[5], levels, 0f);
    }
    @Test public void equalPowerReceivesOnlyGentleFixedSpectralTilt() {
        IslandSpectrum spectrum = new IslandSpectrum();
        byte[] fft = new byte[1024];
        for (int hz : new int[] {100, 300, 1000, 3000, 10000}) {
            int bin = Math.round(hz * fft.length / 48000f);
            fft[bin * 2] = 80;
        }
        spectrum.sample(fft, 48000000);
        float[] levels = new float[5];
        spectrum.copyTo(levels);
        assertTrue(levels[0] > 0f);
        for (int band = 1; band < 5; band++) assertTrue(levels[band] > levels[band - 1]);
        assertTrue(levels[4] / levels[0] < 1.5f);
    }

    @Test public void quietPassageStaysQuietAfterLoudPassage() {
        IslandSpectrum spectrum = new IslandSpectrum();
        byte[] fft = new byte[1024];
        fft[42] = 100;
        spectrum.sample(fft, 48000000, 0L);
        float[] levels = new float[5];
        spectrum.copyTo(levels);
        float loud = levels[2];
        fft[42] = 20;
        for (int i = 1; i <= 20; i++) spectrum.sample(fft, 48000000, i * 50_000_000L);
        spectrum.copyTo(levels);
        assertTrue(levels[2] > 0f);
        assertTrue(levels[2] < loud * .3f);
    }

    @Test public void gainResponseDoesNotDependOnCaptureRate() {
        IslandSpectrum fast = new IslandSpectrum();
        IslandSpectrum slow = new IslandSpectrum();
        byte[] fft = new byte[1024];
        fft[42] = 100;
        fast.sample(fft, 48000000, 0L);
        slow.sample(fft, 48000000, 0L);
        fft[42] = 20;
        for (int i = 1; i <= 100; i++) fast.sample(fft, 48000000, i * 10_000_000L);
        for (int i = 1; i <= 5; i++) slow.sample(fft, 48000000, i * 200_000_000L);
        float[] fastLevels = new float[5], slowLevels = new float[5];
        fast.copyTo(fastLevels);
        slow.copyTo(slowLevels);
        assertArrayEquals(fastLevels, slowLevels, .0001f);
    }

    @Test public void weakTrebleIsNotRaisedToMatchStrongBass() {
        IslandSpectrum spectrum = new IslandSpectrum();
        byte[] fft = new byte[1024];
        fft[4] = 100;
        fft[426] = 10;
        spectrum.sample(fft, 48000000, 0L);
        float[] levels = new float[5];
        spectrum.copyTo(levels);
        assertTrue(levels[4] > 0f);
        assertTrue(levels[4] < levels[0] * .2f);
    }

    @Test public void trebleAboveFourteenKilohertzIsNotDiscarded() {
        IslandSpectrum spectrum = new IslandSpectrum();
        byte[] fft = new byte[1024];
        int bin = Math.round(18000f * fft.length / 48000f);
        fft[bin * 2] = 80;
        spectrum.sample(fft, 48000000);
        float[] levels = new float[5];
        spectrum.copyTo(levels);
        assertTrue(levels[4] > 0f);
        for (int band = 0; band < 4; band++) assertEquals(0f, levels[band], 0f);
    }

}
