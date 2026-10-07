package com.os4.musiccover;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Dependency-free JVM runner: tools/run-pcm-tests.py. */
public final class PlaybackAudioStateTest {
    private static int checks;

    public static void main(String[] args) {
        int p8 = PlaybackAudioState.PCM_8, p16 = PlaybackAudioState.PCM_16;
        int p24 = PlaybackAudioState.PCM_24, p32 = PlaybackAudioState.PCM_32;
        int pf = PlaybackAudioState.PCM_FLOAT;
        envelope(new byte[]{0, (byte) 128}, 0, 2, p8, Math.sqrt(.5), 1);
        envelope(new byte[]{99, 0, (byte) 128, 0, 0, 77}, 1, 4, p16, Math.sqrt(.5), 1);
        envelope(new short[]{123, Short.MIN_VALUE, 0, Short.MAX_VALUE}, 1, 2, p16, Math.sqrt(.5), 1);
        envelope(new float[]{9, -.5f, .5f, 8}, 1, 2, pf, .5, .5);
        envelope(new byte[]{0, 0, (byte) 128, 0, 0, 0}, 0, 6, p24, Math.sqrt(.5), 1);
        envelope(new byte[]{0, 0, 0, (byte) 128, 0, 0, 0, 0}, 0, 8, p32, Math.sqrt(.5), 1);
        envelope(new byte[]{0, 0, (byte) 128, 63}, 0, 4, pf, 1, 1);
        envelope(new float[]{Float.NaN, Float.POSITIVE_INFINITY, -2, .5f}, 0, 4, pf,
                Math.sqrt(1.25 / 4), 1);
        // Unsigned PCM8 is centered on 128, signed widths are centered on zero.
        envelope(new byte[]{(byte) 128}, 0, 1, p8, 0, 0);
        envelope(new byte[]{0, 0, 64}, 0, 3, p24, .5, .5);
        envelope(new byte[]{0, 0, 0, 64}, 0, 4, p32, .5, .5);
        // Partial trailing samples never read past accepted data.
        envelope(new byte[]{0, 64, 99}, 0, 3, p16, .5, .5);
        envelope(new byte[]{99}, 0, 1, p16, 0, 0);
        Object[] invalid = {null, new int[]{1}, new short[]{1}, new float[]{1}, new byte[]{1}};
        for (Object input : invalid) envelope(input, 0, 1, 999, 0, 0);
        envelope(new byte[]{0}, -1, 1, p8, 0, 0);
        envelope(new byte[]{0}, 0, 2, p8, 0, 0);
        envelope(new byte[]{0}, Integer.MAX_VALUE, Integer.MAX_VALUE, p8, 0, 0);
        envelope(new short[]{1}, 0, 1, pf, 0, 0);
        envelope(new float[]{1}, 0, 1, p16, 0, 0);
        envelope(new byte[]{0}, 0, 0, p8, 0, 0);
        envelope(new byte[]{0}, 0, -1, p8, 0, 0);

        for (boolean direct : new boolean[]{false, true}) {
            ByteBuffer original = direct ? ByteBuffer.allocateDirect(12) : ByteBuffer.allocate(12);
            original.put(2, (byte) 0).put(3, (byte) 64);
            original.position(2).limit(8).mark();
            ByteBuffer readOnly = original.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
            readOnly.mark();
            envelope(readOnly, 2, 2, p16, .5, .5);
            check(readOnly.position() == 2 && readOnly.limit() == 8, "buffer cursor preserved");
            readOnly.reset();
            check(original.position() == 2 && original.limit() == 8, "original cursor preserved");
            original.reset();
            envelope(readOnly, 7, 2, p16, 0, 0);
            ByteBuffer slice = original.slice().asReadOnlyBuffer();
            envelope(slice, 0, 2, p16, .5, .5);
        }
        byte[] large = new byte[1_000_000];
        java.util.Arrays.fill(large, (byte) 128);
        for (int i = 0; i < PlaybackAudioState.MAX_SAMPLES; i++)
            large[(int) ((long) i * large.length / PlaybackAudioState.MAX_SAMPLES)] = 0;
        envelope(large, 0, large.length, p8, 1, 1);

        PlaybackAudioState state = new PlaybackAudioState();
        long e = PlaybackAudioState.pack(.25f, .5f);
        check(state.sample(1000) == 0, "inactive default");
        state.activate(1, 2500, 1000);
        check(state.accept(1, 1100, e, 1100), "accept active session");
        near(.25, state.currentEnergy(1100));
        check(!state.accept(2, 1101, e, 1101), "wrong session");
        check(!state.accept(1, 1099, e, 1101), "out of order");
        check(!state.accept(1, 1102, e, 1101), "future sample");
        check(!state.accept(1, 1, e, 1101), "stale sample");
        check(!state.accept(1, 1101, PlaybackAudioState.pack(Float.NaN, .5f), 1101), "NaN");
        check(!state.accept(1, 1101, PlaybackAudioState.pack(.6f, .5f), 1101), "invalid envelope");
        check(!state.accept(1, 1101, PlaybackAudioState.pack(-.1f, .5f), 1101), "negative");
        check(!state.accept(1, 1101, PlaybackAudioState.pack(.1f, 2), 1101), "out of range");
        near(.25, state.currentEnergy(1450));
        near(0, state.currentEnergy(1451));
        near(0, state.currentEnergy(1099));
        state.activate(1, 2600, 1100);
        near(.25, state.currentEnergy(1100));
        state.activate(2, 2600, 1100);
        near(0, state.currentEnergy(1100));
        check(state.accept(2, 1200, e, 1200), "new session");
        state.clear();
        near(0, state.currentEnergy(1200));
        check(!state.accept(2, 1200, e, 1200), "release rejects queued samples");
        state.activate(3, 2701, 1200);
        check(!state.active(1200), "oversized lease");
        state.activate(3, 1200, 1200);
        check(!state.active(1200), "expired lease");
        state.activate(3, 2700, 1200);
        check(!state.active(2700), "expiry boundary");
        check(!state.accept(3, 2700, e, 2700), "expired state rejects data");
        state.activate(0, 2700, 1200);
        check(!state.active(1200), "invalid token");
        System.out.println("PlaybackAudioState: " + checks + " checks passed");
    }

    private static void envelope(Object input, int offset, int count, int encoding, double rms, double peak) {
        long energy = PlaybackAudioState.measure(input, offset, count, encoding);
        near(rms, PlaybackAudioState.rms(energy));
        near(peak, PlaybackAudioState.peak(energy));
    }

    private static void near(double expected, double actual) {
        check(Math.abs(expected - actual) < .000001, "expected " + expected + ", got " + actual);
    }

    private static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }
}
