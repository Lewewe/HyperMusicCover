package com.os4.musiccover;

/** Device eligibility and presentation rules for the optional compact notification pill. */
final class NotificationPillPolicy {
    private NotificationPillPolicy() {}

    // A high sensor sits entirely above the unmodified pill row; no resolution-specific cutoff.
    static boolean highSensor(float sensorBottom, float rowTop) {
        return Float.isFinite(sensorBottom) && Float.isFinite(rowTop)
                && sensorBottom > 0f && rowTop > 0f && sensorBottom < rowTop;
    }

    /** Keep the compact overlay inside the physical screen without reserving the entire nav band. */
    static float loweredCenter(float naturalCenter, float height, float screenHeight,
                               float edgeMargin, float offset, float progress) {
        float lowest = screenHeight - edgeMargin - height / 2f;
        return Math.min(naturalCenter + Math.max(0f, offset) * Math.max(0f, Math.min(1f, progress)), lowest);
    }

    static boolean active(boolean enabled, boolean eligible, boolean mediaExpanded,
                          boolean notificationSelected, boolean inactive) {
        return enabled && eligible && mediaExpanded && notificationSelected && !inactive;
    }
}
