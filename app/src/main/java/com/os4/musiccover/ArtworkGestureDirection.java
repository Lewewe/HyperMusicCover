package com.os4.musiccover;

/** Cache only the direction sign within a gesture; native positions are never reused. */
final class ArtworkGestureDirection {
    private int direction;
    private int members = -1;

    int forMembers(int count) {
        return members == count ? direction : 0;
    }

    void remember(Integer list, Integer number, int count) {
        direction = list == null || number == null ? 0 : list >= number ? 1 : -1;
        members = count;
    }

    void reset() {
        direction = 0;
        members = -1;
    }
}
