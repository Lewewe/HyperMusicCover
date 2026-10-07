package com.os4.musiccover;

/** Wake at the renderer's actual next transition, including rapid groups and interludes. */
final class LyricTickPolicy {
    private LyricTickPolicy() {}

    static long delay(int position, long nextMove) {
        if (nextMove < 0) return 500L;
        return Math.max(1L, Math.min(500L, nextMove - position));
    }
}
