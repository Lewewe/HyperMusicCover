package com.os4.musiccover;

/** Worker-owned onset ring; timestamps always describe the samples still retained. */
final class BpmSampleWindow {
    private final float[] values;
    private final long[] times;
    private final long durationMs;
    private int head, size;

    BpmSampleWindow(int capacity, long durationMs) {
        values = new float[capacity];
        times = new long[capacity];
        this.durationMs = durationMs;
    }

    void clear() { head = size = 0; }

    void add(float value, long time) {
        while (size > 0 && (size == values.length || time - times[head] > durationMs)) {
            head = (head + 1) % values.length;
            size--;
        }
        int next = (head + size) % values.length;
        values[next] = value;
        times[next] = time;
        size++;
    }

    float[] samples() {
        float[] result = new float[size];
        for (int i = 0; i < size; i++) result[i] = values[(head + i) % values.length];
        return result;
    }

    float sampleRate() {
        if (size < 2) return 0f;
        long span = times[(head + size - 1) % values.length] - times[head];
        return span > 0 ? (size - 1) * 1000f / span : 0f;
    }
}
