package com.os4.musiccover;

/** Contrast decisions for the actual backdrop, independent of the saved wallpaper palette. */
final class StatusBarContrast {
    private StatusBarContrast() {}

    static double luminance(int color) {
        return 0.2126 * linear((color >> 16) & 255)
                + 0.7152 * linear((color >> 8) & 255) + 0.0722 * linear(color & 255);
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    static boolean darkIcons(double luminance) {
        // Select whichever endpoint has the higher contrast against the sampled strip.
        return (luminance + 0.05) / 0.05 > 1.05 / (luminance + 0.05);
    }

    static boolean ownsColors(boolean cover, boolean locked, boolean blocked, boolean dozing,
                              double luminance) {
        return cover && locked && !blocked && !dozing && Double.isFinite(luminance);
    }
}
