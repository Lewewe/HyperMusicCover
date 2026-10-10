package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class PcmSpectrumTest {
    private static short[] tone(int bin, int amplitude) {
        short[] pcm = new short[PcmSpectrum.SIZE];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) Math.round(amplitude * Math.sin(2 * Math.PI * bin * i / pcm.length));
        return pcm;
    }

    @Test public void tonePeakIsInCorrectFrequencyBin() {
        PcmSpectrum spectrum = new PcmSpectrum();
        for (int expected : new int[] {3, 32, 192}) {
            byte[] fft = spectrum.transform(tone(expected, 16000));
            int strongest = 0, peak = 0;
            for (int bin = 1; bin < fft.length / 2; bin++) {
                int power = fft[2 * bin] * fft[2 * bin] + fft[2 * bin + 1] * fft[2 * bin + 1];
                if (power > peak) { peak = power; strongest = bin; }
            }
            assertEquals(expected, strongest);
        }
    }

    @Test public void quietInputIsNotAutomaticallyBoostedToFullHeight() {
        PcmSpectrum spectrum = new PcmSpectrum();
        byte[] loud = spectrum.transform(tone(32, 24000)).clone();
        byte[] quiet = spectrum.transform(tone(32, 6000));
        float ratio = Math.abs((float) quiet[65] / loud[65]);
        assertEquals(.25f, ratio, .03f);
    }

    @Test public void silenceClearsPreviouslyReusedBuffers() {
        PcmSpectrum spectrum = new PcmSpectrum();
        spectrum.transform(tone(32, 24000));
        assertArrayEquals(new byte[PcmSpectrum.SIZE], spectrum.transform(new short[PcmSpectrum.SIZE]));
    }

    @Test public void dcOffsetDoesNotBecomeFalseBass() {
        PcmSpectrum spectrum = new PcmSpectrum();
        short[] pcm = new short[PcmSpectrum.SIZE];
        java.util.Arrays.fill(pcm, (short) 1000);
        assertArrayEquals(new byte[PcmSpectrum.SIZE], spectrum.transform(pcm));
    }
}
