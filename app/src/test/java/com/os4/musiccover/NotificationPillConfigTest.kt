package com.os4.musiccover

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NotificationPillConfigTest {
    @Test fun coverSceneCompactsNotificationsWithoutANativeSelection() {
        val expanded = MiniPlayerPresentationPolicy.mediaExpanded(
            cardPresent = true, sessionUsable = true, musicInRow = false,
            nativeRequested = false, coverActive = true)
        assertTrue(NotificationPillPolicy.active(true, true, expanded, true, false))
        val collapsed = MiniPlayerPresentationPolicy.mediaExpanded(
            cardPresent = true, sessionUsable = true, musicInRow = true,
            nativeRequested = false, coverActive = true)
        assertFalse(NotificationPillPolicy.active(true, true, collapsed, true, false))
    }

    @Test fun oldFingerprintSettingIsDroppedWithoutLosingBlurPreferences() {
        val old = JSONObject().put("fodLift", true)
            .put(MiniPlayerConfig.BACKGROUND_BLUR, true)
            .put(MiniPlayerConfig.BACKGROUND_BLUR_RADIUS, 42)
        val result = JSONObject(MiniPlayerConfig.normalizedJson(old.toString()))
        assertFalse(result.has("fodLift"))
        assertFalse(result.getBoolean(MiniPlayerConfig.COMPACT_NOTIFICATIONS))
        assertTrue(result.getBoolean(MiniPlayerConfig.BACKGROUND_BLUR))
        assertEquals(42.0, result.getDouble(MiniPlayerConfig.BACKGROUND_BLUR_RADIUS), 0.0)
    }
    @Test fun compactSettingsAreBoundedAndSurviveNormalization() {
        val input = JSONObject().put(MiniPlayerConfig.COMPACT_NOTIFICATIONS, true)
            .put(MiniPlayerConfig.NOTIFICATION_SIZE, 10)
            .put(MiniPlayerConfig.NOTIFICATION_DROP, 90)
        val result = JSONObject(MiniPlayerConfig.normalizedJson(input.toString()))
        assertTrue(result.getBoolean(MiniPlayerConfig.COMPACT_NOTIFICATIONS))
        assertEquals(70.0, result.getDouble(MiniPlayerConfig.NOTIFICATION_SIZE), 0.0)
        assertEquals(32.0, result.getDouble(MiniPlayerConfig.NOTIFICATION_DROP), 0.0)
        assertEquals(80f, MiniPlayerConfig.notificationSize(Double.NaN), 0f)
        assertEquals(16f, MiniPlayerConfig.notificationDrop(Double.POSITIVE_INFINITY), 0f)
        assertEquals(result.toString(), MiniPlayerConfig.normalizedJson(result.toString()))
    }
}
