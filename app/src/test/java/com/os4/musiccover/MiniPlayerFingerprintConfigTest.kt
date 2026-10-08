package com.os4.musiccover

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MiniPlayerFingerprintConfigTest {
    @Test fun existingBlurSettingsSurviveTheFingerprintSwitchMigration() {
        val old = JSONObject().put(MiniPlayerConfig.BACKGROUND_BLUR, true)
            .put(MiniPlayerConfig.BACKGROUND_BLUR_RADIUS, 42)
            .put(MiniPlayerConfig.BACKGROUND_BLUR_BRIGHTNESS, 90)
        val migrated = JSONObject(MiniPlayerConfig.normalizedJson(old.toString()))
        assertTrue(migrated.getBoolean(MiniPlayerConfig.FOD_LIFT))
        migrated.put(MiniPlayerConfig.FOD_LIFT, false)
        val disabled = JSONObject(MiniPlayerConfig.normalizedJson(migrated.toString()))
        assertFalse(disabled.getBoolean(MiniPlayerConfig.FOD_LIFT))
        assertTrue(disabled.getBoolean(MiniPlayerConfig.BACKGROUND_BLUR))
        assertEquals(42.0, disabled.getDouble(MiniPlayerConfig.BACKGROUND_BLUR_RADIUS), 0.0)
        assertEquals(90.0, disabled.getDouble(MiniPlayerConfig.BACKGROUND_BLUR_BRIGHTNESS), 0.0)
    }
}
