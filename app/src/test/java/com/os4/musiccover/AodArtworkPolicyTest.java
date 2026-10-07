package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class AodArtworkPolicyTest {
    @Test public void notificationCompactWakeWaitsForNativeLayoutAndReconcilesOnce() {
        AodArtworkPolicy policy = new AodArtworkPolicy();
        policy.beginWake(true, true, true);
        assertTrue(policy.wakePending());
        for (int frame = 0; frame < 20; frame++) assertFalse(policy.finishWake(false));
        assertTrue(policy.wakePending());
        assertTrue(policy.finishWake(true));
        assertFalse(policy.wakePending());
        assertFalse(policy.finishWake(true));
    }

    @Test public void fullArtworkAndManualCompactPagesDoNotAcquireAWakeReconcile() {
        AodArtworkPolicy policy = new AodArtworkPolicy();
        policy.beginWake(false, true, true);
        assertFalse(policy.wakePending());
        policy.beginWake(true, false, true);
        assertFalse(policy.wakePending());
        policy.beginWake(true, true, false);
        assertFalse(policy.wakePending());
    }

    @Test public void leavingCoverCancelsPendingNotificationWake() {
        AodArtworkPolicy policy = new AodArtworkPolicy();
        policy.beginWake(true, true, true);
        policy.clear();
        assertFalse(policy.wakePending());
        assertFalse(policy.finishWake(true));
    }

    @Test public void unlockAnimationCannotReplaceTheRestingClockPose() {
        assertTrue(AodArtworkPolicy.canRememberPose(true, false, true, 200f, 300f, 120f, 2656f));
        assertFalse(AodArtworkPolicy.canRememberPose(true, true, true, -479f, 41f, -588f, 2656f));
        assertFalse(AodArtworkPolicy.canRememberPose(false, false, true, 200f, 300f, 120f, 2656f));
        assertFalse(AodArtworkPolicy.canRememberPose(true, false, false, 200f, 300f, 120f, 2656f));
    }

    @Test public void scrolledOffscreenAndInvalidGeometryCannotBecomeAodTargets() {
        assertFalse(AodArtworkPolicy.canRememberPose(true, false, true, -479f, 41f, -588f, 2656f));
        assertFalse(AodArtworkPolicy.canRememberPose(true, false, true, 2500f, 300f, 120f, 2656f));
        assertFalse(AodArtworkPolicy.canRememberPose(true, false, true, Float.NaN, 300f, 120f, 2656f));
        assertFalse(AodArtworkPolicy.canRememberPose(true, false, true, 200f, 0f, 120f, 2656f));
        assertTrue(AodArtworkPolicy.canRememberPose(true, false, true, 200f, 300f, Float.NaN, 2656f));
    }

    @Test public void scrolledOffscreenPlayerAndUnlockedEmptyRowsKeepTheRestoreIntent() {
        AodArtworkPolicy policy = new AodArtworkPolicy();
        policy.observe(true, false, true, true, false);
        policy.observe(true, true, true, false, false);
        policy.observe(false, false, true, false, false);
        assertTrue(AodArtworkPolicy.shouldExpand(true, true, true,
                policy.notificationsExpanded(false), false));
    }

    @Test public void foldingBeforeUnlockClearsTheRememberedNotificationOverride() {
        AodArtworkPolicy policy = new AodArtworkPolicy();
        policy.observe(true, false, true, true, false);
        policy.observe(true, false, true, false, false);
        policy.observe(false, false, true, false, false);
        assertFalse(policy.notificationsExpanded(false));
    }

    @Test public void manualCompactChoiceOrCoverExitCancelsPendingAodRestore() {
        AodArtworkPolicy policy = new AodArtworkPolicy();
        policy.observe(true, false, true, true, false);
        policy.observe(false, false, true, false, true);
        assertFalse(policy.notificationsExpanded(false));
        policy.observe(true, false, true, true, false);
        policy.observe(false, false, false, false, false);
        assertFalse(policy.notificationsExpanded(false));
    }

    @Test public void notificationListOrPileCanRestoreTheUsersBigCover() {
        ArtworkPageState page = new ArtworkPageState();
        page.preferCover();
        assertTrue(AodArtworkPolicy.shouldExpand(true, true, true, true,
                page.compactWithoutLyrics(true, false)));
        assertFalse(AodArtworkPolicy.shouldExpand(true, true, true, false, false));
    }

    @Test public void manualCompactChoiceIsPreservedEvenWithNotificationsOpen() {
        ArtworkPageState page = new ArtworkPageState();
        page.preferCompact();
        assertFalse(AodArtworkPolicy.shouldExpand(true, true, true, true,
                page.compactWithoutLyrics(true, false)));
        assertTrue(page.compactWithoutLyrics(true, false));
    }

    @Test public void lyricPageDoesNotBecomeBigArtworkInAod() {
        assertFalse(AodArtworkPolicy.shouldExpand(true, true, true, true, true));
        assertFalse(AodArtworkPolicy.shouldExpand(true, true, true, false, true));
    }

    @Test public void inactiveCoverAndOtherAodModesDoNotAcquireAnOverride() {
        assertFalse(AodArtworkPolicy.shouldExpand(false, true, true, true, false));
        assertFalse(AodArtworkPolicy.shouldExpand(true, false, true, true, false));
        assertFalse(AodArtworkPolicy.shouldExpand(true, true, false, true, false));
    }
}
