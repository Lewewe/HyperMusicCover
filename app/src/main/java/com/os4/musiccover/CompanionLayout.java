package com.os4.musiccover;

/** Fit the complete companion block, including its reserved beat pulse, inside the live gap. */
final class CompanionLayout {
    private CompanionLayout() {}

    /** Leave a small readable block plus both cover-sized gaps below an oversized clock. */
    static float clockRoom(float currentRoom, float mediaTop, float density) {
        if (!Float.isFinite(mediaTop) || !Float.isFinite(density) || density <= 0f) return currentRoom;
        float limit = mediaTop - (48f + 2f * CoverCardStyle.GAP_DP) * density;
        return Float.isFinite(currentRoom) ? Math.min(currentRoom, limit) : limit;
    }

    static float fitScale(float width, float height, float contentWidth, float contentHeight) {
        if (!Float.isFinite(width) || !Float.isFinite(height) || !Float.isFinite(contentWidth)
                || !Float.isFinite(contentHeight) || width <= 0f || height <= 0f
                || contentWidth <= 0f || contentHeight <= 0f) return 0f;
        // Preserve the PR's original size; only shrink when the available band is smaller.
        return Math.min(1f, Math.min(width / contentWidth, height / contentHeight));
    }
}
