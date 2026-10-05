package com.os4.musiccover;

/** Decide the waiting treatment before a transport command is sent to the player. */
final class PreviousArtworkPolicy {
    private PreviousArtworkPolicy() {}

    static boolean shouldAnimateSkip(int direction, boolean previousChangesTrack) {
        return direction > 0 || (direction < 0 && previousChangesTrack);
    }

    static boolean shouldAnimate(long position, long updatedAt, float speed,
                                 boolean playing, long now) {
        if (position < 0L) return false;
        double current = position;
        if (playing && updatedAt > 0L && now > updatedAt && Float.isFinite(speed)) {
            current += (now - updatedAt) * (double) speed;
        }
        return current >= 0d && current <= 5000d;
    }
}
