package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationPillPolicyTest {
    @Test public void measuredXiaomi17ProSensorIsHigh() {
        assertTrue(NotificationPillPolicy.highSensor(2052f, 2486.5f - 176f / 2f));
        assertTrue(NotificationPillPolicy.highSensor(1026f, (2486.5f - 88f) / 2f));
    }
    @Test public void lowSensorAndUnknownGeometryDoNotTriggerHighSensorLock() {
        assertFalse(NotificationPillPolicy.highSensor(2490f, 2398.5f));
        assertFalse(NotificationPillPolicy.highSensor(2398.5f, 2398.5f));
        assertFalse(NotificationPillPolicy.highSensor(Float.NaN, 2398.5f));
        assertFalse(NotificationPillPolicy.highSensor(0f, 2398.5f));
    }
    @Test public void downwardOffsetUsesTheSpaceFreedByShrinking() {
        // Xiaomi 17 Pro geometry: old inset reservation capped every offset above ~5dp.
        float center = 2486.5f;
        float small = 123f;
        assertEquals(center, NotificationPillPolicy.loweredCenter(center, small, 2656f, 13f, 52f, 0f), 0f);
        assertEquals(center + 26f, NotificationPillPolicy.loweredCenter(center, small, 2656f, 13f, 26f, 1f), 0f);
        assertEquals(center + 52f, NotificationPillPolicy.loweredCenter(center, small, 2656f, 13f, 52f, 1f), 0f);
        float largest = NotificationPillPolicy.loweredCenter(center, small, 2656f, 13f, 104f, 1f);
        assertTrue(largest > center + 52f);
        assertEquals(2656f - 13f, largest + small / 2f, 0f);
    }

    @Test public void appliesOnlyToNotificationsWithExpandedMusic() {
        assertTrue(NotificationPillPolicy.active(true, true, true, true, false));
        assertFalse(NotificationPillPolicy.active(false, true, true, true, false));
        assertFalse(NotificationPillPolicy.active(true, false, true, true, false));
        assertFalse(NotificationPillPolicy.active(true, true, false, true, false));
        assertFalse(NotificationPillPolicy.active(true, true, true, false, false));
        assertFalse(NotificationPillPolicy.active(true, true, true, true, true));
    }
}
