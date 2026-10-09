package com.os4.musiccover;

import java.util.Collections;
import java.util.List;

/** Owns concurrent-lyric timing and its persistent, per-line stage poses. */
final class DuetLyrics {
    private static final float POSITION_TAU = .13f, ALPHA_TAU = .10f;
    private List<LyricLine> lines = Collections.emptyList();
    private int suppressed = -1, first = -1, second = -1;
    private boolean[] owned = new boolean[0];
    private float[] y = new float[0], targetY = new float[0], alpha = new float[0];
    private float scale = 1f;

    void setLines(List<LyricLine> value) {
        lines = value == null ? Collections.<LyricLine>emptyList() : value;
        owned = new boolean[lines.size()];
        y = new float[lines.size()];
        targetY = new float[lines.size()];
        alpha = new float[lines.size()];
        reset();
    }

    void reset() {
        suppressed = first = second = -1;
        java.util.Arrays.fill(owned, false);
        java.util.Arrays.fill(alpha, 0f);
        scale = 1f;
    }

    void update(int positionMs, int focus, boolean dots) {
        if (dots || focus < 0) { first = second = -1; return; }
        update(positionMs, Math.max(0, focus - 12), Math.min(lines.size() - 1, focus + 12));
    }

    /** Compatibility entry point while LyricView's visual half is migrated to animate(). */
    void update(int positionMs, int from, int to) {
        int lo = Math.max(0, from), hi = Math.min(lines.size() - 1, to);
        int active = 0, oldest = -1;
        for (int i = lo; i <= hi; i++) if (active(i, positionMs)) {
            if (oldest < 0) oldest = i;
            active++;
        }
        if (active == 0) suppressed = -1;
        else if (active >= 3 && suppressed < 0) suppressed = oldest;
        else if (suppressed >= 0 && !active(suppressed, positionMs)) suppressed = -1;
        first = second = -1;
        for (int i = lo; i <= hi; i++) if (active(i, positionMs) && i != suppressed) {
            first = second;
            second = i;
        }
    }

    /** Advances positions without assigning one lyric line to another lyric's visual slot. */
    boolean animate(float dt, boolean still, float anchor, float[] base, float[] scroll,
                    float[] height, float bandTop, float bandBottom, float gap) {
        boolean pair = count() == 2;
        boolean hadStage = hasStage();
        if (pair) {
            float total = height[first] + height[second] + gap;
            float usable = Math.max(1f, bandBottom - bandTop - 8f * gap / 22f);
            scale = total <= usable ? 1f : Math.max(.64f, usable / total);
            float top = bandTop + (bandBottom - bandTop - total * scale) * .5f;
            enter(first, top, hadStage, anchor, base, scroll);
            enter(second, top + (height[first] + gap) * scale, hadStage, anchor, base, scroll);
        } else scale = 1f;

        boolean moving = false;
        for (int i = 0; i < owned.length; i++) {
            if (!owned[i]) continue;
            boolean selected = pair && (i == first || i == second);
            float normal = anchor + base[i] - scroll[i];
            if (!selected) targetY[i] = normal;
            float targetA = selected ? 1f : pair ? 0f : 1f;
            if (still) { y[i] = targetY[i]; alpha[i] = targetA; }
            else {
                y[i] = approach(y[i], targetY[i], dt, POSITION_TAU);
                alpha[i] = approach(alpha[i], targetA, dt, ALPHA_TAU);
            }
            if (!selected && pair && alpha[i] < .01f) { owned[i] = false; continue; }
            if (!selected && !pair && Math.abs(y[i] - normal) < .5f) {
                owned[i] = false;
                alpha[i] = 0f;
                continue;
            }
            moving |= Math.abs(y[i] - targetY[i]) > .5f || Math.abs(alpha[i] - targetA) > .01f;
        }
        return moving;
    }

    private void enter(int index, float target, boolean hadStage, float anchor, float[] base,
                       float[] scroll) {
        if (!owned[index]) {
            owned[index] = true;
            y[index] = anchor + base[index] - scroll[index];
            alpha[index] = hadStage ? 0f : 1f;
        }
        targetY[index] = target;
    }

    boolean active(int index, int positionMs) {
        return index >= 0 && index < lines.size() && alive(lines.get(index), positionMs);
    }
    boolean visible(int index) { return index == first || index == second; }
    boolean suppressed(int index, int positionMs) { return index == suppressed && active(index, positionMs); }
    int count() { return second < 0 ? 0 : first < 0 ? 1 : 2; }
    boolean owns(int index) { return index >= 0 && index < owned.length && owned[index]; }
    boolean hasStage() { for (boolean v : owned) if (v) return true; return false; }
    float y(int index) { return owns(index) ? y[index] : Float.NaN; }
    float alpha(int index) { return owns(index) ? alpha[index] : 1f; }
    float scale(int index) { return visible(index) ? scale : 1f; }

    private static boolean alive(LyricLine line, int ms) {
        if (ms < line.start) return false;
        return ms < line.end || wordLive(line, ms)
                || line.bg != null && (ms < line.bg.end || wordLive(line.bg, ms));
    }
    private static boolean wordLive(LyricLine line, int ms) {
        return line.singingWordAt(ms) || line.hasWords() && line.sungChars(ms) < line.text.length();
    }
    private static float approach(float from, float to, float dt, float tau) {
        return dt <= 0f ? from : from + (to - from) * (1f - (float) Math.exp(-dt / tau));
    }
}
