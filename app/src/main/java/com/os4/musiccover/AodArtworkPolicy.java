package com.os4.musiccover;

/** Restore only notification-constrained big artwork, never a user-selected compact page. */
final class AodArtworkPolicy {
    private boolean notificationsBeforeUnlock;

    void clear() {
        notificationsBeforeUnlock = false;
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
