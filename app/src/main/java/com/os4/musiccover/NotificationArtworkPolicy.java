package com.os4.musiccover;

/** A temporary thumbnail override, independent of the user's cover/lyrics preference. */
final class NotificationArtworkPolicy {
    /** Preserve the 32 ms motion cadence; callbacks must not turn it into per-setter sampling. */
    static long wakeDelay(boolean pending, long due, long now) {
        if (!pending) return 0L;
        return due - now > 32L ? 32L : -1L;
    }

    static boolean shouldPoll(boolean listOpen, boolean moving, boolean requestPending) {
        return listOpen || moving || requestPending;
    }

    static boolean needsMotionGeometry(Integer position, Integer previous) {
        return position != null && previous != null
                && Math.abs((long) position - previous) > 2L;
    }

    private boolean fullList;
    private String settledState = "NUMBER";
    private boolean openingRequested;
    private boolean openingMoved;

    void reset() {
        fullList = false;
        settledState = "NUMBER";
        openingRequested = openingMoved = false;
    }

    void requestList() {
        fullList = openingRequested = true;
        openingMoved = false;
    }

    void expireRequest() {
        openingRequested = openingMoved = false;
    }

    boolean listOpen(boolean expanded, String state, boolean moving) {
        return listOpen(expanded, state, moving, false);
    }

    boolean listOpen(boolean expanded, String state, boolean moving, boolean towardList) {
        if (openingRequested) {
            openingMoved |= moving;
            if ("LIST".equals(state) || openingMoved && !moving || !expanded) {
                openingRequested = false;
            } else {
                return expanded;
            }
        }
        // Start the morph as the list opens, before it hides the clock's drawing root.
        // A downward fold keeps its settled cover even if native flags briefly say LIST.
        if (moving && towardList && ("STACK".equals(settledState) || "LIST".equals(state))) {
            fullList = true;
        }
        if (!moving) {
            fullList = state == null ? expanded : "LIST".equals(state);
            if (state != null) settledState = state;
        }
        return expanded && fullList;
    }

    static boolean compact(boolean held, boolean listOpen, int count,
                           boolean coverActive, boolean bigArtwork) {
        return coverActive && listOpen && count > 1 && (held || bigArtwork);
    }
}
