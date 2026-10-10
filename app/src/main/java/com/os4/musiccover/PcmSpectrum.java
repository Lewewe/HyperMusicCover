package com.os4.musiccover;

/** Reusable windowed FFT for the system's mono playback capture; no samples are retained. */
final class PcmSpectrum {
    static final int SIZE = 512;
    private final double[] real = new double[SIZE], imaginary = new double[SIZE];
    private final double[] window = new double[SIZE];
    private final double[] cosine = new double[SIZE / 2], sine = new double[SIZE / 2];
    private final byte[] fft = new byte[SIZE];

    PcmSpectrum() {
        for (int i = 0; i < SIZE; i++) window[i] = .5 - .5 * Math.cos(2 * Math.PI * i / (SIZE - 1));
        for (int i = 0; i < SIZE / 2; i++) {
            cosine[i] = Math.cos(-2 * Math.PI * i / SIZE);
            sine[i] = Math.sin(-2 * Math.PI * i / SIZE);
        }
    }

    byte[] transform(short[] pcm) {
        double mean = 0;
        for (short value : pcm) mean += value;
        mean /= SIZE;
        for (int i = 0; i < SIZE; i++) {
            int reversed = Integer.reverse(i) >>> (32 - 9);
            real[reversed] = (pcm[i] - mean) * window[i];
            imaginary[reversed] = 0;
        }
        for (int length = 2; length <= SIZE; length *= 2) {
            int half = length / 2;
            for (int start = 0; start < SIZE; start += length) {
                for (int i = 0; i < half; i++) {
                    int twiddle = i * SIZE / length, a = start + i, b = a + half;
                    double r = real[b] * cosine[twiddle] - imaginary[b] * sine[twiddle];
                    double im = real[b] * sine[twiddle] + imaginary[b] * cosine[twiddle];
                    real[b] = real[a] - r; imaginary[b] = imaginary[a] - im;
                    real[a] += r; imaginary[a] += im;
                }
            }
        }
        // Match Visualizer's packed signed-byte format and compensate the Hann window.
        // The fixed scale preserves quiet/loud differences; device volume is not involved.
        double scale = 4d / (SIZE * 256d);
        fft[0] = quantize(real[0] * scale);
        fft[1] = quantize(real[SIZE / 2] * scale);
        for (int bin = 1; bin < SIZE / 2; bin++) {
            fft[2 * bin] = quantize(real[bin] * scale);
            fft[2 * bin + 1] = quantize(imaginary[bin] * scale);
        }
        return fft;
    }

    private static byte quantize(double value) {
        return (byte) Math.max(-127, Math.min(127, Math.round(value)));
    }
}
