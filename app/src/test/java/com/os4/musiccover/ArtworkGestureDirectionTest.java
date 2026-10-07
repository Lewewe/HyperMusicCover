package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArtworkGestureDirectionTest {
    @Test public void downwardPullAndSpringReboundCannotOpenTheList() {
        ArtworkGestureDirection gesture = new ArtworkGestureDirection();
        gesture.beginTouch(1000f);
        gesture.updateTouch(1040f);
        assertFalse(gesture.allowsListMotion());
        gesture.updateTouch(1000f);
        gesture.reset();
        assertFalse(gesture.allowsListMotion());
        gesture.clearTouch();
        assertTrue(gesture.allowsListMotion());
    }

    @Test public void realUpwardDragAndDeliberateReversalStillOpenTheList() {
        ArtworkGestureDirection gesture = new ArtworkGestureDirection();
        gesture.beginTouch(1000f);
        gesture.updateTouch(999f);
        assertFalse(gesture.allowsListMotion());
        gesture.updateTouch(990f);
        assertTrue(gesture.allowsListMotion());
        gesture.reset();
        assertTrue(gesture.allowsListMotion());
        gesture.beginTouch(1000f);
        gesture.updateTouch(1040f);
        assertFalse(gesture.allowsListMotion());
        gesture.updateTouch(980f);
        assertTrue(gesture.allowsListMotion());
    }

    @Test public void directionIsReusedOnlyForTheSameGestureAndNotificationCount() {
        ArtworkGestureDirection cache = new ArtworkGestureDirection();
        assertEquals(0, cache.forMembers(3));
        cache.remember(1023, 0, 3);
        for (int i = 0; i < 100; i++) assertEquals(1, cache.forMembers(3));
        assertEquals(0, cache.forMembers(2));
        cache.reset();
        assertEquals(0, cache.forMembers(3));
    }

    @Test public void reversedAndEqualGeometryKeepTheirOriginalDirectionAndMissingGeometryRetries() {
        ArtworkGestureDirection cache = new ArtworkGestureDirection();
        cache.remember(0, 1023, 3);
        assertEquals(-1, cache.forMembers(3));
        cache.remember(0, 0, 3);
        assertEquals(1, cache.forMembers(3));
        cache.remember(null, 0, 3);
        assertEquals(0, cache.forMembers(3));
    }
}
