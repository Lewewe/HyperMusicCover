package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationArtworkPolicyTest {
    @Test public void pilePullDownAndOvershootingReboundKeepTheBigArtwork() {
        NotificationArtworkPolicy navigation = new NotificationArtworkPolicy();
        ArtworkGestureDirection gesture = new ArtworkGestureDirection();
        assertFalse(navigation.listOpen(true, "STACK", false));
        gesture.beginTouch(1000f);
        gesture.updateTouch(1040f);
        int previous = 395;
        for (int position : new int[]{370, 350, 370, 395, 405, 395}) {
            boolean toward = gesture.allowsListMotion() && position - previous > 2;
            assertFalse(navigation.listOpen(true, "STACK", true, toward));
            previous = position;
        }
        assertFalse(navigation.listOpen(true, "STACK", false));
        gesture.beginTouch(1000f);
        gesture.updateTouch(980f);
        assertTrue(navigation.listOpen(true, "STACK", true, gesture.allowsListMotion()));
    }

    @Test public void motionCallbacksKeepTheSampleCadenceAndWakeAnIdleDetectorImmediately() {
        assertEquals(0L, NotificationArtworkPolicy.wakeDelay(false, 0L, 100L));
        assertEquals(32L, NotificationArtworkPolicy.wakeDelay(true, 228L, 100L));
        for (long now = 100L; now <= 132L; now += 8L) {
            assertEquals(-1L, NotificationArtworkPolicy.wakeDelay(true, 132L, now));
        }
    }

    @Test public void slowDragAccumulatesUntilItCanStartTheListMorph() {
        NotificationArtworkPolicy navigation = new NotificationArtworkPolicy();
        assertFalse(navigation.listOpen(true, "STACK", false));
        int previous = 395;
        boolean opening = false;
        for (int position = 396; position <= 400; position++) {
            boolean moved = NotificationArtworkPolicy.needsMotionGeometry(position, previous);
            if (moved) previous = position;
            opening = navigation.listOpen(true, "STACK", true, moved);
        }
        assertTrue(opening);
        assertTrue(navigation.listOpen(true, "LIST", false));
        assertFalse(navigation.listOpen(true, "STACK", false));
    }

    @Test public void settledPileAndNumberStopPollingUntilACallbackWakesThem() {
        assertFalse(NotificationArtworkPolicy.shouldPoll(false, false, false));
        assertTrue(NotificationArtworkPolicy.shouldPoll(false, true, false));
        assertTrue(NotificationArtworkPolicy.shouldPoll(true, false, false));
        assertTrue(NotificationArtworkPolicy.shouldPoll(false, false, true));
        assertFalse(NotificationArtworkPolicy.shouldPoll(false, false, false));
    }

    @Test public void idleSamplesSkipGeometryButFirstDragStillStartsTheMorph() {
        NotificationArtworkPolicy navigation = new NotificationArtworkPolicy();
        assertFalse(navigation.listOpen(true, "STACK", false));
        for (int i = 0; i < 100; i++) {
            assertFalse(NotificationArtworkPolicy.needsMotionGeometry(395, 395));
            assertFalse(navigation.listOpen(true, "STACK", false));
        }
        assertTrue(NotificationArtworkPolicy.needsMotionGeometry(414, 395));
        assertTrue(navigation.listOpen(true, "STACK", true, true));
        assertFalse(NotificationArtworkPolicy.needsMotionGeometry(414, 414));
        assertTrue(navigation.listOpen(true, "LIST", true, false));
        assertFalse(navigation.listOpen(true, "STACK", false));
    }

    @Test public void geometryFilterRetainsBothDirectionsAndRejectsInsignificantMotion() {
        assertFalse(NotificationArtworkPolicy.needsMotionGeometry(null, 395));
        assertFalse(NotificationArtworkPolicy.needsMotionGeometry(395, null));
        assertFalse(NotificationArtworkPolicy.needsMotionGeometry(397, 395));
        assertFalse(NotificationArtworkPolicy.needsMotionGeometry(393, 395));
        assertTrue(NotificationArtworkPolicy.needsMotionGeometry(398, 395));
        assertTrue(NotificationArtworkPolicy.needsMotionGeometry(392, 395));
        assertTrue(NotificationArtworkPolicy.needsMotionGeometry(Integer.MAX_VALUE, Integer.MIN_VALUE));
    }

    @Test public void openingPileTowardListStartsMorphBeforeNativeListSettles() {
        NotificationArtworkPolicy navigation = new NotificationArtworkPolicy();
        assertFalse(navigation.listOpen(true, "STACK", false));
        assertTrue(navigation.listOpen(true, "STACK", true, true));
        assertTrue(navigation.listOpen(true, "LIST", true, true));
        assertTrue(navigation.listOpen(true, "LIST", false));
        assertTrue(navigation.listOpen(true, "STACK", true, false));
        assertFalse(navigation.listOpen(true, "STACK", false));
        assertFalse(navigation.listOpen(true, "NUMBER", true, false));
    }

    @Test public void tappingPillStartsMorphBeforeTheNativeScrollBegins() {
        NotificationArtworkPolicy navigation = new NotificationArtworkPolicy();
        navigation.requestList();
        assertTrue(navigation.listOpen(true, "NUMBER", false));
        assertTrue(navigation.listOpen(true, "STACK", true));
        assertTrue(navigation.listOpen(true, "LIST", false));
        assertFalse(navigation.listOpen(true, "STACK", false));
    }

    @Test public void cancelledOpeningReturnsToBigCoverAndRequestTimeoutDoesNotFlashList() {
        NotificationArtworkPolicy navigation = new NotificationArtworkPolicy();
        navigation.requestList();
        assertTrue(navigation.listOpen(true, "STACK", true));
        assertFalse(navigation.listOpen(true, "STACK", false));
        navigation.requestList();
        assertTrue(navigation.listOpen(true, "LIST", false));
        navigation.expireRequest();
        assertTrue(navigation.listOpen(true, "LIST", true));
    }

    @Test public void nativePileRestoresCoverBeforeTheSecondSwipeToPill() {
        NotificationArtworkPolicy navigation = new NotificationArtworkPolicy();
        boolean held = NotificationArtworkPolicy.compact(false,
                navigation.listOpen(true, "LIST", false), 3, true, true);
        assertTrue(held);
        assertFalse(NotificationArtworkPolicy.compact(held,
                navigation.listOpen(true, "STACK", false), 3, true, false));
        assertFalse(navigation.listOpen(false, "NUMBER", false));
        assertTrue(navigation.listOpen(true, "LIST", false));
    }

    @Test public void passingThroughPileDuringAnimationKeepsThumbnail() {
        NotificationArtworkPolicy navigation = new NotificationArtworkPolicy();
        navigation.listOpen(true, "LIST", false);
        assertTrue(navigation.listOpen(true, "STACK", true));
        assertFalse(navigation.listOpen(true, "STACK", false));
    }

    @Test public void upwardSwipeFromMediaPlayerKeepsBigArtworkInPile() {
        NotificationArtworkPolicy navigation = new NotificationArtworkPolicy();
        assertFalse(navigation.listOpen(false, "NUMBER", false));
        assertFalse(navigation.listOpen(true, "STACK", true));
        assertFalse(navigation.listOpen(true, "STACK", false));
        assertFalse(navigation.listOpen(true, "STACK", false));
        assertTrue(navigation.listOpen(true, "LIST", false));
        assertFalse(navigation.listOpen(true, "STACK", false));
    }

    @Test public void downwardSwipeFromPileDoesNotStartAnotherArtworkMorph() {
        NotificationArtworkPolicy navigation = new NotificationArtworkPolicy();
        navigation.listOpen(true, "LIST", false);
        assertFalse(navigation.listOpen(true, "STACK", false));
        // Native state and motion flags can arrive in different frames while folding.
        assertFalse(navigation.listOpen(true, "STACK", true));
        assertFalse(navigation.listOpen(true, "LIST", true));
        assertFalse(navigation.listOpen(true, "NUMBER", true));
        assertFalse(navigation.listOpen(false, "NUMBER", false));
    }

    @Test public void twoNotificationsTemporarilyCollapseBigArtwork() {
        assertFalse(NotificationArtworkPolicy.compact(false, false, 2, true, true));
        assertTrue(NotificationArtworkPolicy.compact(false, true, 2, true, true));
        // Once collapsed, the override must survive its own thumbnail state.
        assertTrue(NotificationArtworkPolicy.compact(true, true, 2, true, false));
    }

    @Test public void foldingOrOneRemainingNotificationRestoresCover() {
        assertFalse(NotificationArtworkPolicy.compact(true, false, 3, true, false));
        assertFalse(NotificationArtworkPolicy.compact(true, true, 1, true, false));
        assertFalse(NotificationArtworkPolicy.compact(true, true, 0, true, false));
    }

    @Test public void existingThumbnailOrLyricsDoesNotAcquireAnOverride() {
        assertFalse(NotificationArtworkPolicy.compact(false, true, 5, true, false));
        ArtworkPageState page = new ArtworkPageState();
        page.preferCompact();
        NotificationArtworkPolicy.compact(false, true, 2, true, false);
        assertTrue(page.compactWithoutLyrics(true, false));
    }

    @Test public void leavingCoverClearsPendingRestoration() {
        assertFalse(NotificationArtworkPolicy.compact(true, true, 5, false, false));
        assertFalse(NotificationArtworkPolicy.compact(false, true, 1, true, true));
    }
}
