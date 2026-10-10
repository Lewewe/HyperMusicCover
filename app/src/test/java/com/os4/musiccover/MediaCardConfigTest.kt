package com.os4.musiccover

import org.junit.Assert.*
import org.junit.Test

class MediaCardConfigTest {
    @Test fun independentProfilesRoundTripWithoutChangingNativeDefaults() {
        val defaults = MediaCardConfig.parse(null)
        assertEquals(0, defaults.getValue("notification.background"))
        assertEquals(0, defaults.getValue("island.flow"))
        val changed = defaults + ("notification.cover" to 2) + ("island.background" to 5)
        assertEquals(changed, MediaCardConfig.parse(MediaCardConfig.encode(changed)))
        assertEquals(0, changed.getValue("island.cover"))
        assertEquals(0, changed.getValue("notification.background"))
    }
    @Test fun malformedAndOutOfRangeSettingsAreBounded() {
        assertEquals(MediaCardConfig.parse(null), MediaCardConfig.parse("not json"))
        val values = MediaCardConfig.parse("""{"notification.blur":100,"island.cover":-9,"island.background":7,"notification.hideSource":2,"unknown":99}""")
        assertEquals(20, values.getValue("notification.blur"))
        assertEquals(0, values.getValue("island.cover"))
        assertEquals(5, values.getValue("island.background"))
        assertEquals(1, values.getValue("notification.hideSource"))
        assertFalse(values.containsKey("unknown"))
    }
    @Test fun hiddenAndScreenOffCardsNeverAnimate() {
        assertTrue(MediaCardConfig.animate(true, true, true, true))
        assertFalse(MediaCardConfig.animate(true, true, false, true))
        assertFalse(MediaCardConfig.animate(true, true, true, false))
        assertFalse(MediaCardConfig.animate(false, true, true, true))
        assertTrue(MediaCardConfig.animate(false, false, true, true))
        assertFalse(MediaCardConfig.animate(false, false, true, false))
    }
    @Test fun nativeAndDisabledFlowDoNotCreateAnOverlay() {
        assertFalse(MediaCardConfig.customBackground(0, 0))
        assertFalse(MediaCardConfig.customBackground(0, 4))
        for (flow in 1..3) assertTrue(MediaCardConfig.customBackground(0, flow))
        for (style in 1..5) assertTrue(MediaCardConfig.customBackground(style, 0))
    }
}
