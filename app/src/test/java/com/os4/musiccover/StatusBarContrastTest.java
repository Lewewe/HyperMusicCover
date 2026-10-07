package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class StatusBarContrastTest {
    @Test public void brightAndDarkPlayerBackdropsChooseOppositeIcons() {
        assertFalse(StatusBarContrast.darkIcons(StatusBarContrast.luminance(0xff141414)));
        assertTrue(StatusBarContrast.darkIcons(StatusBarContrast.luminance(0xffe8e8e8)));
        assertEquals(0.0, StatusBarContrast.luminance(0xff000000), 0.000001);
        assertEquals(1.0, StatusBarContrast.luminance(0xffffffff), 0.000001);
    }

    @Test public void contrastUsesLinearLightRatherThanRawRgbAverage() {
        assertFalse(StatusBarContrast.darkIcons(StatusBarContrast.luminance(0xff0000ff)));
        assertTrue(StatusBarContrast.darkIcons(StatusBarContrast.luminance(0xff00ff00)));
        assertFalse(StatusBarContrast.darkIcons(StatusBarContrast.luminance(0xff707070)));
        assertTrue(StatusBarContrast.darkIcons(StatusBarContrast.luminance(0xff808080)));
    }

    @Test public void wallpaperAndAodKeepNativeColorOwnership() {
        assertTrue(StatusBarContrast.ownsColors(true, true, false, false, 0.4));
        assertFalse(StatusBarContrast.ownsColors(false, true, false, false, 0.4));
        assertFalse(StatusBarContrast.ownsColors(true, false, false, false, 0.4));
        assertFalse(StatusBarContrast.ownsColors(true, true, true, false, 0.4));
        assertFalse(StatusBarContrast.ownsColors(true, true, false, true, 0.4));
        assertFalse(StatusBarContrast.ownsColors(true, true, false, false, Double.NaN));
    }
}
