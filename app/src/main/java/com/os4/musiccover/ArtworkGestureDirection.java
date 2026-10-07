package com.os4.musiccover;

/** Cache only the direction sign within a gesture; native positions are never reused. */
final class ArtworkGestureDirection {
    private int direction;
    private int members = -1;
    private float touchStartY = Float.NaN;
    private boolean pulledUp;

    void beginTouch(float y) {
        reset();
        touchStartY = y;
        pulledUp = false;
    }

    void updateTouch(float y) {
        if (Float.isFinite(touchStartY) && Float.isFinite(y) && touchStartY - y > 2f) {
            pulledUp = true;
        }
    }

    boolean allowsListMotion() {
        return !Float.isFinite(touchStartY) || pulledUp;
    }

    void clearTouch() {
        touchStartY = Float.NaN;
        pulledUp = false;
    }

    int forMembers(int count) {
        return members == count ? direction : 0;
    }

    void remember(Integer list, Integer number, int count) {
        direction = list == null || number == null ? 0 : list >= number ? 1 : -1;
        members = count;
    }

    /** Geometry can be refreshed during a spring rebound without erasing the finger's intent. */
    void reset() {
        direction = 0;
        members = -1;
    }
}
