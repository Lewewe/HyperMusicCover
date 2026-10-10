package com.os4.musiccover;

/** Five real FFT bands, with a slowly decaying gain reference and an actual silence floor. */
final class IslandSpectrum {
    private static final float[] EDGES = {30f, 150f, 500f, 1600f, 5000f, 20000f};
    private static final float DISPLAY_GAIN = .85f;
    // A gentle fixed spectral tilt prevents bass from dominating the tiny display.
    // One shared gain reference still preserves differences between the bands.
    private static final float[] BAND_GAIN = {.8f, .9f, 1f, 1.1f, 1.15f};
    private static final double PEAK_RELEASE_NS = 5_000_000_000d;
    private final float[] energy = new float[5];
    private final float[] levels = new float[5];
    private float peak = 3f;
    private long lastSampleNs = Long.MIN_VALUE;

    synchronized void sample(byte[] fft, int rateMilliHz) {
        sample(fft, rateMilliHz, System.nanoTime());
    }

    synchronized void sample(byte[] fft, int rateMilliHz, long nowNs) {
        java.util.Arrays.fill(energy, 0f);
        if (fft == null || fft.length < 4 || rateMilliHz <= 0) {
            java.util.Arrays.fill(levels, 0f); return;
        }
        float binWidth = rateMilliHz / (1000f * fft.length);
        for (int bin = 1; bin < fft.length / 2; bin++) {
            float hz = bin * binWidth;
            for (int band = 0; band < 5; band++) if (hz >= EDGES[band] && hz < EDGES[band + 1]) {
                int real = fft[bin * 2], imaginary = fft[bin * 2 + 1];
                energy[band] += real * real + imaginary * imaginary;
                break;
            }
        }
        float highest = 0f;
        for (int band = 0; band < 5; band++) {
            // Integrate each band's power. Averaging across its bins unfairly attenuates
            // the wider mid/treble bands compared with the narrow bass bands.
            energy[band] = (float) Math.sqrt(energy[band]) * BAND_GAIN[band];
            highest = Math.max(highest, energy[band]);
        }
        // Use elapsed time so AOD's lower sample rate does not change the gain response.
        double elapsedNs = lastSampleNs == Long.MIN_VALUE ? 0d : Math.max(0d, (double) nowNs - lastSampleNs);
        lastSampleNs = nowNs;
        peak = Math.max(3f, Math.max(highest, peak * (float) Math.exp(-elapsedNs / PEAK_RELEASE_NS)));
        // Linear amplitude avoids artificially lifting weak signals and quiet passages.
        for (int band = 0; band < 5; band++) levels[band] = highest < .8f ? 0f
                : Math.min(1f, energy[band] / peak * DISPLAY_GAIN);
    }

    synchronized void copyTo(float[] destination) { System.arraycopy(levels, 0, destination, 0, 5); }
    synchronized void reset() {
        java.util.Arrays.fill(levels, 0f);
        peak = 3f;
        lastSampleNs = Long.MIN_VALUE;
    }
}
