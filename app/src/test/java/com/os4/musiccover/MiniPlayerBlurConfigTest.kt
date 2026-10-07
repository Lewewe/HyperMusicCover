package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniPlayerBlurConfigTest {
    @Test fun brightnessBoundsMalformedAndOutOfRangeValues() {
        assertEquals(0f, MiniPlayerConfig.blurBrightness(-20.0), 0f)
        assertEquals(100f, MiniPlayerConfig.blurBrightness(200.0), 0f)
        assertEquals(80f, MiniPlayerConfig.blurBrightness(Double.NaN), 0f)
        assertEquals(80f, MiniPlayerConfig.blurBrightness(Double.POSITIVE_INFINITY), 0f)
        assertEquals(42f, MiniPlayerConfig.blurBrightness(42.0), 0f)
    }

    @Test fun brightnessLightensTheUpperRangeAndKeepsTintAtBothExtremes() {
        assertEquals(0x33676B6C, MiniPlayerConfig.blurTint(80.0))
        assertEquals(0x991F2324.toInt(), MiniPlayerConfig.blurTint(0.0))
        assertEquals(0x33F8FAFC, MiniPlayerConfig.blurTint(100.0))
        assertEquals(0x33B0B2B4, MiniPlayerConfig.blurTint(90.0))
        assertEquals(MiniPlayerConfig.blurTint(80.0), MiniPlayerConfig.blurTint(Double.NaN))
    }

    @Test fun brighterValuesAlwaysReduceBackdropDarkening() {
        var previousAlpha = 255
        var previousColor = 0
        for (brightness in 0..100) {
            val tint = MiniPlayerConfig.blurTint(brightness.toDouble())
            val alpha = tint ushr 24
            org.junit.Assert.assertTrue(alpha in 51..153)
            val color = tint and 0x00FFFFFF
            org.junit.Assert.assertTrue(color >= previousColor)
            org.junit.Assert.assertTrue(alpha <= previousAlpha)
            previousAlpha = alpha
            previousColor = color
        }
    }

    @Test fun blurRadiusBoundsMalformedAndOutOfRangeValues() {
        assertEquals(0f, MiniPlayerConfig.blurRadius(-20.0), 0f)
        assertEquals(80f, MiniPlayerConfig.blurRadius(200.0), 0f)
        assertEquals(30f, MiniPlayerConfig.blurRadius(Double.NaN), 0f)
        assertEquals(30f, MiniPlayerConfig.blurRadius(Double.POSITIVE_INFINITY), 0f)
        assertEquals(42f, MiniPlayerConfig.blurRadius(42.0), 0f)
    }
}
