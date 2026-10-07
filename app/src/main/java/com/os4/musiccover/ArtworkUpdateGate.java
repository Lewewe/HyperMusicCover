package com.os4.musiccover;

/** Keep one detector slot occupied through native queries, including their reentrant hooks. */
final class ArtworkUpdateGate {
    private boolean occupied;

    boolean request() {
        if (occupied) return false;
        occupied = true;
        return true;
    }

    void complete(boolean continued) {
        occupied = continued;
    }
}
