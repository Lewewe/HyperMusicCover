package com.os4.musiccover;

import android.graphics.Color;
import android.view.View;
import java.util.WeakHashMap;

/** Applies artwork hue to the live glyph field used by HyperOS's glass renderer. */
final class ClockArtworkColor {
    private static final WeakHashMap<View, Entry> entries = new WeakHashMap<>();
    private static final class Entry {
        final float[] original = new float[3], applied = new float[3];
        final float[] hsv = new float[3], tintHsv = new float[3];
        boolean written;
    }

    static void apply(View glyph, int tint) {
        Entry entry = entries.get(glyph);
        if (tint == 0 && entry == null) return;
        try {
            float[] data = (float[]) Xp.getObjectField(glyph, "glassData");
            if (data == null || data.length < 42) return;
            boolean ours = entry != null && entry.written
                    && data[11] == entry.applied[0] && data[12] == entry.applied[1]
                    && data[13] == entry.applied[2];
            if (tint == 0) {
                if (ours) {
                    System.arraycopy(entry.original, 0, data, 11, 3);
                    Xp.callMethod(glyph, "setMiGlass", data);
                }
                entries.remove(glyph);
                return;
            }
            if (entry == null) { entry = new Entry(); entries.put(glyph, entry); }
            // A new OEM palette replaces the baseline; our own last write never does.
            if (!ours) System.arraycopy(data, 11, entry.original, 0, 3);
            Color.colorToHSV(Color.rgb(channel(entry.original[0]), channel(entry.original[1]),
                    channel(entry.original[2])), entry.hsv);
            Color.colorToHSV(tint, entry.tintHsv);
            entry.hsv[0] = entry.tintHsv[0];
            entry.hsv[1] = entry.tintHsv[1];
            int color = Color.HSVToColor(entry.hsv);
            entry.applied[0] = Color.red(color) / 255f;
            entry.applied[1] = Color.green(color) / 255f;
            entry.applied[2] = Color.blue(color) / 255f;
            boolean changed = data[11] != entry.applied[0] || data[12] != entry.applied[1]
                    || data[13] != entry.applied[2];
            System.arraycopy(entry.applied, 0, data, 11, 3);
            entry.written = true;
            // Upload changed colors to the shader; its cached parameters need an explicit setter.
            if (changed) Xp.callMethod(glyph, "setMiGlass", data);
        } catch (Throwable ignored) { }
    }

    private static int channel(float value) { return Math.max(0, Math.min(255, Math.round(value * 255f))); }
}
