package com.os4.musiccover;

import java.nio.ByteBuffer;

/** Android-free, bounded PCM decoding and expiring scalar state. Never retains input audio. */
public final class PlaybackAudioState {
    public static final long LEASE_MS = 1500;
    public static final long STALE_MS = 350;
    public static final int MAX_SAMPLES = 512;
    // Android AudioFormat values, kept here so this helper can be tested on the JVM.
    public static final int PCM_16 = 2, PCM_8 = 3, PCM_FLOAT = 4, PCM_24 = 21, PCM_32 = 22;

    private volatile long until;
    private volatile long session;
    private volatile long sampledAt;
    private volatile long envelope;

    public synchronized void activate(long token, long deadline, long now) {
        if (token <= 0 || deadline <= now || deadline - now > LEASE_MS) {
            clear();
            return;
        }
        if (session != token) {
            envelope = 0;
            sampledAt = 0;
        }
        session = token;
        until = deadline;
    }

    public synchronized void clear() {
        until = 0;
        session = 0;
        sampledAt = 0;
        envelope = 0;
    }

    public boolean active(long now) {
        return until > now;
    }

    public synchronized boolean accept(long token, long at, long packed, long now) {
        float rms = rms(packed), peak = peak(packed);
        if (!active(now) || token != session || at <= 0 || at > now
                || now - at > STALE_MS || at < sampledAt
                || !Float.isFinite(rms) || !Float.isFinite(peak)
                || rms < 0 || peak < rms || peak > 1) return false;
        envelope = packed;
        sampledAt = at;
        return true;
    }

    /** RMS in [0,1]; zero immediately on release, expiry or stale data. */
    public float currentEnergy(long now) {
        return rms(sample(now));
    }

    /** Fresh accepted data can be true silence (packed zero), not missing PCM. */
    public synchronized boolean hasFreshSample(long now) {
        return active(now) && sampledAt > 0 && now >= sampledAt
                && now - sampledAt <= STALE_MS;
    }

    /** Packed RMS/peak, avoiding allocation even when queried every animation frame. */
    public synchronized long sample(long now) {
        return hasFreshSample(now) ? envelope : 0;
    }

    public static float rms(long packed) {
        return Float.intBitsToFloat((int) (packed >>> 32));
    }

    public static float peak(long packed) {
        return Float.intBitsToFloat((int) packed);
    }

    public static long pack(float rms, float peak) {
        return ((long) Float.floatToRawIntBits(rms) << 32)
                | (Float.floatToRawIntBits(peak) & 0xffffffffL);
    }

    public static int bytesPerSample(int encoding) {
        switch (encoding) {
            case PCM_8: return 1;
            case PCM_16: return 2;
            case PCM_24: return 3;
            case PCM_FLOAT:
            case PCM_32: return 4;
            default: return 0;
        }
    }

    static boolean canMeasure(Object input, int offset, int accepted, int encoding) {
        return sampleCount(input, offset, accepted, encoding) > 0;
    }

    private static int sampleCount(Object input, int offset, int accepted, int encoding) {
        int width = bytesPerSample(encoding);
        if (input == null || offset < 0 || accepted <= 0 || width == 0) return 0;
        int length;
        boolean bytes = input instanceof byte[] || input instanceof ByteBuffer;
        if (input instanceof byte[]) length = ((byte[]) input).length;
        else if (input instanceof ByteBuffer) length = ((ByteBuffer) input).limit();
        else if (input instanceof short[] && encoding == PCM_16) length = ((short[]) input).length;
        else if (input instanceof float[] && encoding == PCM_FLOAT) length = ((float[]) input).length;
        else return 0;
        if (offset > length || accepted > length - offset) return 0;
        return bytes ? accepted / width : accepted;
    }

    /** Invalid regions/encodings are ignored, not clipped into a different region. */
    public static long measure(Object input, int offset, int accepted, int encoding) {
        int count = sampleCount(input, offset, accepted, encoding);
        if (count == 0) return 0;
        int width = bytesPerSample(encoding);
        boolean bytes = input instanceof byte[] || input instanceof ByteBuffer;
        int visits = Math.min(count, MAX_SAMPLES);
        double sum = 0;
        float peak = 0;
        for (int i = 0; i < visits; i++) {
            // Spread a bounded number of probes across only the accepted prefix.
            int index = (int) ((long) i * count / visits);
            float value;
            if (bytes) value = decode(input, offset + index * width, encoding);
            else if (input instanceof short[]) value = ((short[]) input)[offset + index] / 32768f;
            else value = ((float[]) input)[offset + index];
            if (!Float.isFinite(value)) value = 0;
            value = Math.min(1, Math.abs(value));
            sum += (double) value * value;
            peak = Math.max(peak, value);
        }
        float rms = Math.min(peak, (float) Math.sqrt(sum / visits));
        return pack(rms, peak);
    }

    private static int octet(Object input, int at) {
        return (input instanceof byte[] ? ((byte[]) input)[at] : ((ByteBuffer) input).get(at)) & 255;
    }

    private static float decode(Object input, int at, int encoding) {
        int low = octet(input, at);
        if (encoding == PCM_8) return (low - 128) / 128f;
        int bits = low | (octet(input, at + 1) << 8);
        if (encoding == PCM_16) return (short) bits / 32768f;
        bits |= octet(input, at + 2) << 16;
        if (encoding == PCM_24) return (bits << 8 >> 8) / 8388608f;
        bits |= octet(input, at + 3) << 24;
        return encoding == PCM_FLOAT ? Float.intBitsToFloat(bits) : (float) (bits / 2147483648.0);
    }
}
