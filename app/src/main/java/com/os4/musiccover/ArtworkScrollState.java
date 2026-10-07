package com.os4.musiccover;

/** Repeated layout setters must not wake notification artwork sampling. */
final class ArtworkScrollState {
    private final float[] values = {Float.NaN, Float.NaN, Float.NaN};

    boolean changed(int channel, float value) {
        if (!Float.isFinite(value)) return false;
        float previous = values[channel];
        values[channel] = value;
        return !Float.isFinite(previous) || previous != value;
    }
}
