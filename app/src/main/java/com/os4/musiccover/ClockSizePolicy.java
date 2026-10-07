package com.os4.musiccover;

/** Native clock measurements belong to one editor geometry, not just one renderer class. */
final class ClockSizePolicy {
    private ClockSizePolicy() {}

    static String editorKey(String template, int style, double height, double width,
                            int font, int weight, boolean doubleRow) {
        return template + "/" + style + "/" + height + "/" + width + "/" + font
                + "/" + weight + "/" + doubleRow;
    }

    static String measurementKey(String renderer, String geometry, int width, int height, int dpi) {
        return renderer + "#geometry-v1/" + geometry + "/" + width + "/" + height + "/" + dpi;
    }

    static boolean retainLargest(String savedKey, String currentKey, float saved, float measured) {
        return currentKey.equals(savedKey) && Float.isFinite(saved) && saved > 0f
                && measured < saved + 0.5f;
    }

    static float nativeUnit(float full, float live, boolean notificationCompact) {
        return notificationCompact && Float.isFinite(live) && live > 0f
                ? Math.min(full, live) : full;
    }

    static float coverUnit(float requested, float reference, float nativeUnit, float artworkScale,
                           float minimumFraction, float nativeProgress) {
        float compact = Math.max(minimumFraction * reference,
                Math.min(reference, requested * artworkScale));
        return compact + (nativeUnit - compact) * nativeProgress;
    }

    static float transitionUnit(float drawnUnit, float destination, float progress) {
        return drawnUnit + (destination - drawnUnit) * Math.max(0f, Math.min(1f, progress));
    }
}
