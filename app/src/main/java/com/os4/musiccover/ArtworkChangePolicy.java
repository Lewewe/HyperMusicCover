package com.os4.musiccover;

/** Distinguish YouTube presentation changes from lower-resolution copies of the same cover. */
final class ArtworkChangePolicy {
    private ArtworkChangePolicy() {}

    static boolean isYouTube(String key) {
        if (key == null) return false;
        int split = key.indexOf('|');
        String pkg = split < 0 ? key : key.substring(0, split);
        return "com.google.android.apps.youtube.music".equals(pkg)
                || "com.google.android.youtube".equals(pkg);
    }

    static boolean changed(String key, int oldWidth, int oldHeight, int[] oldPixels,
                           int width, int height, int[] pixels) {
        if (!isYouTube(key) || oldWidth <= 0 || oldHeight <= 0 || width <= 0 || height <= 0
                || oldPixels == null || pixels == null || oldPixels.length == 0
                || oldPixels.length != pixels.length) return false;
        // Switching between an album square and a video thumbnail is a presentation change.
        double oldAspect = oldWidth / (double) oldHeight;
        double aspect = width / (double) height;
        if (Math.abs(Math.log(aspect / oldAspect)) > 0.12) return true;
        long difference = 0;
        int changed = 0;
        for (int i = 0; i < pixels.length; i++) {
            int sum = 0;
            for (int shift = 16; shift >= 0; shift -= 8) {
                sum += Math.abs(((oldPixels[i] >>> shift) & 255) - ((pixels[i] >>> shift) & 255));
            }
            difference += sum;
            if (sum > 72) changed++;
        }
        // Allow small compression/resampling differences without losing the sharper source.
        return difference > 18L * 3 * pixels.length && changed * 3 >= pixels.length;
    }
}
