package com.os4.musiccover;

/** The thumbnail-to-cover path and its frame-rate-independent, lightly sprung progress. */
final class CoverMorphMotion {
    static final class Box {
        final float x, y, w, h;
        Box(float x, float y, float w, float h) {
            this.x = x; this.y = y; this.w = w; this.h = h;
        }
        float cx() { return x + w * 0.5f; }
        float cy() { return y + h * 0.5f; }
    }

    /** Match the circular thumbnail continuously while preserving the cover's own corners. */
    static float artworkRadius(float thumbnailRadius, float coverRadius, float drawnSide,
                               float coverSide, float progress, boolean circularThumbnail) {
        float p = Math.max(0f, Math.min(1f, progress));
        if (!circularThumbnail) return thumbnailRadius + (coverRadius - thumbnailRadius) * p;
        float coverShare = coverRadius / Math.max(1f, coverSide);
        return Math.max(0f, drawnSide) * Math.min(0.5f, 0.5f + (coverShare - 0.5f) * p);
    }

    float value;
    float velocity;
    float target;

    void aim(boolean cover) { target = cover ? 1f : 0f; }

    /** Artwork-only toggles should not inherit a slow whole-scene clock animation. */
    static float responseFor(boolean compactToggle, float sceneResponse) {
        return compactToggle ? 0.38f : sceneResponse;
    }

    void step(float dt, float response) {
        if (dt <= 0f) return;
        double frequency = 2.0 * Math.PI / Math.max(0.18f, response);
        double damping = 0.80;
        double damped = frequency * Math.sqrt(1.0 - damping * damping);
        double x = value - target;
        double v = velocity;
        double decay = Math.exp(-damping * frequency * dt);
        double a = x;
        double b = (v + damping * frequency * x) / damped;
        double phase = damped * dt;
        double wave = a * Math.cos(phase) + b * Math.sin(phase);
        value = (float) (target + decay * wave);
        velocity = (float) (decay * (-damping * frequency * wave
                + damped * (-a * Math.sin(phase) + b * Math.cos(phase))));
        if (atRest()) { value = target; velocity = 0f; }
    }

    boolean atRest() {
        return Math.abs(value - target) < 0.001f && Math.abs(velocity) < 0.012f;
    }

    /** End the barely visible spring tail before a notification drag can carry its copy away. */
    void settleNotificationThumbnail() {
        if (target == 0f && Math.abs(value) < 0.008f && Math.abs(velocity) < 0.20f) {
            value = velocity = 0f;
        }
    }

    /** A landed compact thumbnail belongs to the scrolling media player, not the copy. */
    boolean canRelease(boolean compactToggle, boolean handoffDone, boolean endsSettled,
                       boolean clockFlying, long elapsedMs) {
        if (!atRest() || !handoffDone) return false;
        if (compactToggle && target == 0f) return true;
        return endsSettled && (!clockFlying || elapsedMs > 2200L);
    }

    /** The card's reserved placement is centred around its live playback scale. */
    static Box cardSquare(float x, float y, float side, float scale) {
        float drawn = side * scale;
        float inset = (side - drawn) * 0.5f;
        return new Box(x + inset, y + inset, drawn, drawn);
    }

    /**
     * The card as drawn: the reserved square at its playback scale, with artwork of this aspect
     * (width over height) fitted inside it - the long side on the square's side, centred.
     */
    static Box cardBox(float x, float y, float side, float scale, float aspect) {
        Box s = cardSquare(x, y, side, scale);
        float w = aspect >= 1f ? s.w : s.w * aspect;
        float h = aspect >= 1f ? s.h / aspect : s.h;
        return new Box(s.cx() - w * 0.5f, s.cy() - h * 0.5f, w, h);
    }

    /** Decoration grows with the travelling cover and is fully present at the handoff. */
    static float cardDecoration(float progress) {
        float p = Math.max(0f, Math.min(1f, progress));
        return p * p * (3f - 2f * p);
    }

    /** The same bowed path is used in both directions; only progress reverses. */
    static Box frame(Box thumb, Box cover, float progress, float density) {
        float p = Math.max(0f, Math.min(1f, progress));
        float dx = cover.cx() - thumb.cx(), dy = cover.cy() - thumb.cy();
        float distance = (float) Math.hypot(dx, dy);
        float bow = Math.min(distance * 0.12f, 84f * density);
        // A thumbnail at the left of the player bows toward the centre of the display.
        float perpendicular = dx >= 0f ? 1f : -1f;
        float controlX = (thumb.cx() + cover.cx()) * 0.5f
                + perpendicular * (-dy / Math.max(1f, distance)) * bow;
        float controlY = (thumb.cy() + cover.cy()) * 0.5f
                + perpendicular * (dx / Math.max(1f, distance)) * bow;
        float one = 1f - p;
        float cx = one * one * thumb.cx() + 2f * one * p * controlX + p * p * cover.cx();
        float cy = one * one * thumb.cy() + 2f * one * p * controlY + p * p * cover.cy();
        // The centre stops at the target; only the size has a small landing overshoot.
        float sizeP = Math.max(-0.025f, Math.min(1.025f, progress));
        float w = thumb.w + (cover.w - thumb.w) * sizeP;
        float h = thumb.h + (cover.h - thumb.h) * sizeP;
        return new Box(cx - w * 0.5f, cy - h * 0.5f, w, h);
    }
}
