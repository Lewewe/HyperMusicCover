package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniPlayerBlurConfigTest {
    @Test fun blurRadiusBoundsMalformedAndOutOfRangeValues() {
        assertEquals(0f, MiniPlayerConfig.blurRadius(-20.0), 0f)
        assertEquals(80f, MiniPlayerConfig.blurRadius(200.0), 0f)
        assertEquals(30f, MiniPlayerConfig.blurRadius(Double.NaN), 0f)
        assertEquals(30f, MiniPlayerConfig.blurRadius(Double.POSITIVE_INFINITY), 0f)
        assertEquals(42f, MiniPlayerConfig.blurRadius(42.0), 0f)
    }
}
