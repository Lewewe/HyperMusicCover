package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class CoverBackdropPaletteTest {
    @Test public void retainsFrequentDistinctColorsAndIgnoresTransparentPixels() {
        int[] colors = CoverBackdropPalette.colors(new int[]{
                0xffff0000, 0xffff0000, 0xfffe0101, 0xff00ff00, 0xff0000ff,
                0x00ffffff, 0x00ffffff, 0x00ffffff});
        assertEquals(3, colors.length);
        assertTrue(((colors[0] >>> 16) & 255) >= 254);
        assertEquals(0xff0000ff, colors[1]);
        assertEquals(0xff00ff00, colors[2]);
    }

    @Test public void monochromeAndTransparentArtworkHaveUsablePalettes() {
        assertArrayEquals(new int[]{0xff808080},
                CoverBackdropPalette.colors(new int[]{0xff808080, 0xff818181}));
        assertArrayEquals(new int[]{0xff000000},
                CoverBackdropPalette.colors(new int[]{0x00000000}));
    }

    @Test public void regionsStayStableAndWithinTheRequestedOpacityRange() {
        int[] colors = {0xffcc5533, 0xff3366aa, 0xff998877};
        float[][] a = CoverBackdropPalette.regions(colors);
        float[][] b = CoverBackdropPalette.regions(colors.clone());
        for (int i = 0; i < a.length; i++) {
            assertArrayEquals(a[i], b[i], 0f);
            assertTrue(a[i][0] >= 0.1f && a[i][0] <= 0.9f);
            assertTrue(a[i][1] > i / 3f && a[i][1] < (i + 1) / 3f);
            assertTrue(a[i][3] >= 0.30f && a[i][3] <= 0.40f);
        }
        assertFalse(java.util.Arrays.equals(a[0],
                CoverBackdropPalette.regions(new int[]{0xff112233})[0]));
    }
}
