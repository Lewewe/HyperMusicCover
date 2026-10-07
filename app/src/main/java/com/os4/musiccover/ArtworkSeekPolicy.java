package com.os4.musiccover;

/** Ignore temporary buffering from a seek only while the same song remains active. */
final class ArtworkSeekPolicy {
    private ArtworkSeekPolicy() {}

    static boolean suppressTransient(boolean transientState, long now, long until,
                                     boolean sameSong) {
        return transientState && sameSong && now < until;
    }
}
