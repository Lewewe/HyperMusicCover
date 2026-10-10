package com.os4.musiccover;

import java.util.ArrayDeque;

/** A bounded queue of owned FFT snapshots, ordered by monotonic delivery time. */
final class SpectrumDelayBuffer<T> {
    static final class Frame<T> {
        final Object source;
        final byte[] data;
        final int rate;
        final long due;
        final T targets;
        Frame(Object source, byte[] data, int rate, long due, T targets) {
            this.source = source; this.data = data.clone(); this.rate = rate;
            this.due = due; this.targets = targets;
        }
    }
    private final ArrayDeque<Frame<T>> frames = new ArrayDeque<>();
    private final int capacity;
    SpectrumDelayBuffer(int capacity) { this.capacity = capacity; }
    void offer(Object source, byte[] data, int rate, long due, T targets) {
        if (frames.size() == capacity) frames.removeFirst();
        frames.addLast(new Frame<>(source, data, rate, due, targets));
    }
    Frame<T> poll(long now) { return !frames.isEmpty() && frames.peekFirst().due <= now ? frames.removeFirst() : null; }
    long nextAt() { return frames.isEmpty() ? -1 : frames.peekFirst().due; }
    int size() { return frames.size(); }
    void clear() { frames.clear(); }
}
