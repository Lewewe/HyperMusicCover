package com.os4.musiccover;

final class AliveLyricsEffects {
    private AliveLyricsEffects() {
    }

    static final int OFF = 0;
    static final int SUBTLE = 1;
    static final int DRAMATIC = 2;
    static final int EYE_CANDY = 3;

    private static final int TRAIL_MS_SUBTLE = 320;
    private static final int TRAIL_MS_DRAMATIC = 420;
    private static final int TRANS_MS_SUBTLE = 260;
    private static final int TRANS_MS_DRAMATIC = 340;
    private static final int TRANS_MS_EYE_CANDY = 520;

    static int clampMode(int mode) {
        if (mode <= OFF) return OFF;
        return Math.min(mode, EYE_CANDY);
    }

    static boolean enabled(int mode) {
        return clampMode(mode) != OFF;
    }

    static boolean eyeCandy(int mode) {
        return clampMode(mode) == EYE_CANDY;
    }

    static int wordEntranceMs(int mode, boolean backing) {
        int m = clampMode(mode);
        if (m == EYE_CANDY) return backing ? 300 : 420;
        return m >= DRAMATIC ? 250 : 210;
    }

    static float wordEntranceOffsetDp(int mode, boolean backing) {
        int m = clampMode(mode);
        if (m == EYE_CANDY) return backing ? 8f : 14f;
        return m >= DRAMATIC ? 3f : 1.5f;
    }

    static float wordEntranceRotation(int mode, boolean backing) {
        int m = clampMode(mode);
        if (m == EYE_CANDY) return backing ? 1.2f : 2.2f;
        return 0f;
    }

    static float wordEntranceScale(int mode, boolean backing) {
        int m = clampMode(mode);
        if (m == EYE_CANDY) return backing ? 0.02f : 0.035f;
        return m >= DRAMATIC ? 0.01f : 0f;
    }

    static float wordEntranceBlurDp(int mode, boolean backing) {
        int m = clampMode(mode);
        if (m == EYE_CANDY) return backing ? 2.5f : 4.5f;
        return m >= DRAMATIC ? 1.2f : 0f;
    }

    static float glowMultiplier(int mode) {
        int m = clampMode(mode);
        if (m == EYE_CANDY) return 1.35f;
        if (m == DRAMATIC) return 1.15f;
        return 1f;
    }

    static float breathingScale(int mode, boolean active, boolean playing, boolean still,
                                int ms, int lineStart, int lineEnd, float sungChars) {
        if (!enabled(mode) || !active || !playing || still) return 1f;
        float span = Math.max(1f, lineEnd - lineStart);
        float progress = clamp01((ms - lineStart) / span);
        float cycles = mode >= DRAMATIC ? 1.9f : 1.35f;
        float phase = (float) (Math.PI * 2f * (progress * cycles + sungChars * 0.015f));
        float pulse = 0.5f + 0.5f * (float) Math.sin(phase);
        float depth = mode >= DRAMATIC ? 0.018f : 0.012f;
        return 1f + depth * pulse;
    }

    static int trailDurationMs(int mode) {
        int m = clampMode(mode);
        if (m <= OFF) return 0;
        return m >= DRAMATIC ? TRAIL_MS_DRAMATIC : TRAIL_MS_SUBTLE;
    }

    static float wordTrailIntensity(int mode, int nowMs, int sylEndMs, int sylDurMs,
                                    int glowMinMs) {
        int tail = trailDurationMs(mode);
        if (tail <= 0 || nowMs <= sylEndMs || nowMs >= sylEndMs + tail) return 0f;
        float u = clamp01((nowMs - sylEndMs) / (float) tail);
        float decay = (1f - u) * (1f - u);
        float base = mode == EYE_CANDY ? 0.58f : mode >= DRAMATIC ? 0.42f : 0.28f;
        float held = sylDurMs <= glowMinMs ? 0f
                : clamp01((sylDurMs - glowMinMs) / (float) Math.max(1, glowMinMs));
        float heldBoost = mode == EYE_CANDY ? 0.48f : mode >= DRAMATIC ? 0.35f : 0.22f;
        return (base + heldBoost * held) * decay;
    }

    static float glowWidthScale(int mode, int sylDurMs, int glowMinMs, float glow) {
        if (!enabled(mode) || glow <= 0f) return 1f;
        if (sylDurMs <= glowMinMs) return 1f;
        float held = clamp01((sylDurMs - glowMinMs) / (float) Math.max(1, glowMinMs));
        float max = mode == EYE_CANDY ? 1.7f : mode >= DRAMATIC ? 1.45f : 1.28f;
        return 1f + (max - 1f) * held * clamp01(glow);
    }

    static float translationFade(int mode, boolean playing, boolean still, long elapsedMs) {
        if (!enabled(mode) || still || !playing) return 1f;
        if (elapsedMs <= 0L) return 0f;
        int span = mode == EYE_CANDY ? TRANS_MS_EYE_CANDY
                : mode >= DRAMATIC ? TRANS_MS_DRAMATIC : TRANS_MS_SUBTLE;
        return clamp01(elapsedMs / (float) span);
    }

    static float translationLiftDp(int mode, float fade) {
        if (!enabled(mode)) return 0f;
        float max = mode >= DRAMATIC ? 5f : 3f;
        float t = 1f - clamp01(fade);
        return max * t * t;
    }

    static boolean translationNeedsFrame(int mode, boolean playing, boolean still,
                                         long startedAt, long now) {
        if (!enabled(mode) || still || !playing || startedAt <= 0L || now <= startedAt) return false;
        int span = mode == EYE_CANDY ? TRANS_MS_EYE_CANDY
                : mode >= DRAMATIC ? TRANS_MS_DRAMATIC : TRANS_MS_SUBTLE;
        return now - startedAt < span;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
