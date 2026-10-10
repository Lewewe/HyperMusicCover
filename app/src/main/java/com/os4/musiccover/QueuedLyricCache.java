package com.os4.musiccover;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persistent ready-to-play lyrics, keyed by the player's stable media id rather than a query. */
final class QueuedLyricCache {
    private QueuedLyricCache() { }

    static final class Entry {
        final List<LyricLine> lines;
        final int source;
        final boolean translated;
        Entry(List<LyricLine> lines, int source, boolean translated) {
            this.lines = lines;
            this.source = source;
            this.translated = translated;
        }
    }

    static Entry read(String pkg, String mediaId, String configuration) {
        JSONObject root = LyricDiskCache.read("ready-config-v2", key(pkg, mediaId, configuration));
        if (root == null) return null;
        try {
            JSONArray rows = root.optJSONArray("lines");
            if (rows == null) return null;
            ArrayList<LyricLine> lines = new ArrayList<>(rows.length());
            for (int i = 0; i < rows.length(); i++) {
                LyricLine line = line(rows.optJSONObject(i));
                if (line != null) lines.add(line);
            }
            return lines.isEmpty() ? null : new Entry(lines, root.optInt("source"),
                    root.optBoolean("translated"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void write(String pkg, String mediaId, List<LyricLine> lines, int source,
                      boolean translated, String configuration) {
        if (lines == null || lines.isEmpty()) return;
        Entry old = read(pkg, mediaId, configuration);
        // A live lookup may have richer metadata than queue prefetch, but it must not turn an
        // already cached word-timed lyric back into a merely line-timed one. A translated result
        // is also retained over an otherwise equal untranslated refresh.
        if (old != null && quality(lines) < quality(old.lines)) return;
        if (old != null && quality(lines) == quality(old.lines)
                && old.translated && !translated) return;
        try {
            JSONObject root = new JSONObject();
            root.put("source", source);
            root.put("translated", translated);
            JSONArray rows = new JSONArray();
            for (LyricLine line : lines) if (line != null) rows.put(json(line));
            root.put("lines", rows);
            LyricDiskCache.write("ready-config-v2", key(pkg, mediaId, configuration), root);
        } catch (Throwable ignored) { }
    }

    private static String key(String pkg, String mediaId, String configuration) {
        return pkg == null || mediaId == null || mediaId.isEmpty() ? ""
                : LyricCacheScope.track(configuration, pkg, mediaId);
    }

    /** Keep the live source cache independent of an online result stored in the queue cache. */
    static List<LyricLine> withoutOnline(List<LyricLine> lines) {
        ArrayList<LyricLine> nativeLines = new ArrayList<>(lines.size());
        for (LyricLine line : lines) nativeLines.add(withoutOnline(line));
        return nativeLines;
    }

    private static LyricLine withoutOnline(LyricLine line) {
        if (line == null) return null;
        LyricLine copy = new LyricLine(line.text, line.translation, line.roma, line.start, line.end,
                line.opposite, line.sylStart, line.sylEnd, line.charEnd);
        copy.localRoma = line.localRoma;
        copy.localRomaRevision = line.localRomaRevision;
        copy.localRomaSettings = line.localRomaSettings;
        copy.bg = withoutOnline(line.bg);
        return copy;
    }

    private static int quality(List<LyricLine> lines) {
        int score = lines == null ? 0 : lines.size();
        return LyricSource.words(lines) ? 100000 + score : score;
    }

    private static JSONObject json(LyricLine l) throws Exception {
        JSONObject o = new JSONObject();
        o.put("text", l.text); o.put("translation", l.translation); o.put("online", l.onlineTranslation);
        o.put("roma", l.roma); o.put("localRoma", l.localRoma); o.put("localRomaRev", l.localRomaRevision); o.put("localRomaSettings", l.localRomaSettings);
        o.put("start", l.start); o.put("end", l.end); o.put("opposite", l.opposite);
        o.put("ss", array(l.sylStart)); o.put("se", array(l.sylEnd)); o.put("ce", array(l.charEnd));
        if (l.bg != null) o.put("bg", json(l.bg));
        return o;
    }

    private static LyricLine line(JSONObject o) {
        if (o == null || o.optString("text", "").isEmpty()) return null;
        LyricLine l = new LyricLine(o.optString("text"), o.optString("translation", null),
                o.optString("roma", null), o.optInt("start"), o.optInt("end"),
                o.optBoolean("opposite"), ints(o.optJSONArray("ss")), ints(o.optJSONArray("se")),
                ints(o.optJSONArray("ce")));
        String localRoma = o.optString("localRoma", null);
        l.localRoma = localRoma == null || localRoma.trim().isEmpty() ? null : localRoma.trim();
        l.localRomaRevision = o.optInt("localRomaRev", 0);
        l.localRomaSettings = o.optInt("localRomaSettings", 0);
        l.onlineTranslation = o.optString("online", null);
        l.bg = line(o.optJSONObject("bg"));
        return l;
    }

    private static JSONArray array(int[] values) {
        JSONArray out = new JSONArray();
        if (values != null) for (int value : values) out.put(value);
        return out;
    }

    private static int[] ints(JSONArray values) {
        if (values == null || values.length() == 0) return null;
        int[] out = new int[values.length()];
        for (int i = 0; i < out.length; i++) out[i] = values.optInt(i);
        return out;
    }
}
