package com.os4.musiccover;

/** Restore only notification-constrained big artwork, never a user-selected compact page. */
final class AodArtworkPolicy {
    /** Edge contact and invalid measurements must not collapse artwork. */
    static boolean clockOverlapsArtwork(float left, float top, float right, float bottom,
                                         float artLeft, float artTop, float artRight, float artBottom) {
        if (!Float.isFinite(left) || !Float.isFinite(top) || !Float.isFinite(right)
                || !Float.isFinite(bottom) || !Float.isFinite(artLeft) || !Float.isFinite(artTop)
                || !Float.isFinite(artRight) || !Float.isFinite(artBottom)
                || right <= left || bottom <= top || artRight <= artLeft || artBottom <= artTop) return false;
        return Math.min(right, artRight) - Math.max(left, artLeft) > 1f
                && Math.min(bottom, artBottom) - Math.max(top, artTop) > 1f;
    }

    private boolean notificationsBeforeUnlock;
    private boolean wakePending;

    void beginWake(boolean expanded, boolean fromCompact, boolean notificationCompact) {
        wakePending = expanded && fromCompact && notificationCompact;
    }

    boolean wakePending() {
        return wakePending;
    }

    /** Consume once, after native wake layout settles, while the AOD's full artwork stays visible. */
    boolean finishWake(boolean settled) {
        if (!wakePending || !settled) return false;
        wakePending = false;
        return true;
    }

    void clear() {
        notificationsBeforeUnlock = false;
        wakePending = false;
    }

    void observe(boolean locked, boolean goingAway, boolean coverActive,
                 boolean notificationsExpanded, boolean userCompact) {
        if (!coverActive || userCompact) {
            clear();
        } else if (locked && !goingAway) {
            notificationsBeforeUnlock = notificationsExpanded;
        }
        // Unlocked filter runs contain no lock-screen rows; they cannot revoke this snapshot.
    }

    boolean notificationsExpanded(boolean live) {
        return live || notificationsBeforeUnlock;
    }

    static boolean canRememberPose(boolean locked, boolean goingAway, boolean screenOn,
                                   float top, float size, float date, float screenHeight) {
        return locked && !goingAway && screenOn && Float.isFinite(top) && Float.isFinite(size)
                && top >= 0f && size > 0f && top + size <= screenHeight
                && (Float.isNaN(date) || Float.isFinite(date) && date >= 0f);
    }

    static boolean shouldExpand(boolean coverActive, boolean cardMode, boolean heldAod,
                                boolean notificationsExpanded, boolean userCompact) {
        return coverActive && cardMode && heldAod && notificationsExpanded && !userCompact;
    }
}
