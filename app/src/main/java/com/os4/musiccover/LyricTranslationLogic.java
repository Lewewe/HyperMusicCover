package com.os4.musiccover;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Pure helpers for online line-level translation.
 *
 * The timing data belongs to the original line and is never derived from translated text here:
 * the translated line is secondary text only, so word-by-word karaoke timing remains exactly what
 * came from the original source parse.
 */
final class LyricTranslationLogic {

    private LyricTranslationLogic() {
    }

    static final class Entry {
        final int index;
        final String id;
        final String text;

        Entry(int index, String id, String text) {
            this.index = index;
            this.id = id;
            this.text = text;
        }
    }

    static final class Batch {
        final List<Entry> entries;
        final String payload;

        Batch(List<Entry> entries, String payload) {
            this.entries = entries;
            this.payload = payload;
        }
    }

    static boolean hasJapaneseScript(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HAN) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }
    private static final String MARKER_PREFIX = "[[MCID:";
    private static final String MARKER_SUFFIX = "]]";

    static List<Entry> entriesOf(List<LyricLine> lines) {
        if (lines == null || lines.isEmpty()) return Collections.emptyList();
        ArrayList<Entry> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            if (line == null || line.text == null) continue;
            String text = line.text.trim();
            if (!text.isEmpty()) out.add(new Entry(i, idOf(i, line), text));
        }
        return out;
    }

    static List<Entry> entriesForTranslation(List<LyricLine> lines, String source,
                                              String target) {
        ArrayList<Entry> out = new ArrayList<>();
        for (Entry entry : entriesOf(lines)) {
            if (hasJapaneseScript(entry.text)) out.add(entry);
        }
        return out;
    }

    static List<Batch> batches(List<Entry> entries, int maxChars, int maxLines) {
        if (entries == null || entries.isEmpty()) return Collections.emptyList();
        if (maxChars < 64) maxChars = 64;
        if (maxLines < 1) maxLines = 1;
        ArrayList<Batch> out = new ArrayList<>();
        ArrayList<Entry> bucket = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int chars = 0;
        for (Entry e : entries) {
            String row = marked(e.id, e.text);
            int add = row.length() + 1;
            if (!bucket.isEmpty() && (bucket.size() >= maxLines || chars + add > maxChars)) {
                out.add(new Batch(new ArrayList<>(bucket), sb.toString()));
                bucket.clear();
                sb.setLength(0);
                chars = 0;
            }
            bucket.add(e);
            if (sb.length() > 0) sb.append('\n');
            sb.append(row);
            chars += add;
        }
        if (!bucket.isEmpty()) out.add(new Batch(new ArrayList<>(bucket), sb.toString()));
        return out;
    }

    static String idOf(int index, LyricLine line) {
        return Integer.toHexString(line.start) + "-" + index;
    }

    static String marked(String id, String text) {
        return MARKER_PREFIX + id + MARKER_SUFFIX + " " + text;
    }

    static Map<String, String> parseLibreResponse(String body) {
        return parseLibreResponse(body, Collections.<Entry>emptyList());
    }

    static Map<String, String> parseLibreResponse(String body, List<Entry> entries) {
        if (body == null || body.trim().isEmpty()) return Collections.emptyMap();
        try {
            JSONObject obj = new JSONObject(body);
            if (!obj.has("translatedText")) return Collections.emptyMap();
            Object translated = obj.get("translatedText");
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            if (translated instanceof String) {
                String text = (String) translated;
                out.putAll(parseMarkedTranslation(text));
                if (out.isEmpty()) out.putAll(parseOrderedTranslation(text, entries));
            } else if (translated instanceof JSONArray) {
                JSONArray arr = (JSONArray) translated;
                for (int i = 0; i < arr.length(); i++) {
                    Object one = arr.get(i);
                    if (!(one instanceof String)) continue;
                    String text = (String) one;
                    Map<String, String> marked = parseMarkedTranslation(text);
                    if (!marked.isEmpty()) {
                        out.putAll(marked);
                    } else if (i < entries.size() && !text.trim().isEmpty()) {
                        out.put(entries.get(i).id, text.trim());
                    }
                }
            }
            return out;
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
    }

    private static Map<String, String> parseOrderedTranslation(String text, List<Entry> entries) {
        if (text == null || entries == null || entries.isEmpty()) return Collections.emptyMap();
        String[] lines = text.split("\\r?\\n", -1);
        if (entries.size() == 1) {
            return lines.length == 0 || lines[0].trim().isEmpty()
                    ? Collections.<String, String>emptyMap()
                    : Collections.singletonMap(entries.get(0).id, text.trim());
        }
        if (lines.length != entries.size()) return Collections.emptyMap();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) out.put(entries.get(i).id, lines[i].trim());
        }
        return out;
    }

    /** Official array APIs return one result per input, in the same order; reject count drift. */
    static Map<String, String> parseCloudResponse(String body, List<Entry> entries, boolean google) {
        if (body == null || entries == null) return Collections.emptyMap();
        try {
            JSONObject root = new JSONObject(body);
            JSONArray results = (google ? root.getJSONObject("data") : root)
                    .getJSONArray("translations");
            if (results.length() != entries.size()) return Collections.emptyMap();
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            for (int i = 0; i < results.length(); i++) {
                String text = results.getJSONObject(i).getString(google ? "translatedText" : "text");
                if (!text.trim().isEmpty()) out.put(entries.get(i).id, text.trim());
            }
            return out;
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    static Map<String, String> parseMarkedTranslation(String translated) {
        if (translated == null || translated.isEmpty()) return Collections.emptyMap();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        String currentId = null;
        int contentStart = -1;
        int p = 0;
        while (p < translated.length()) {
            int markerAt = translated.indexOf(MARKER_PREFIX, p);
            if (markerAt < 0) break;
            int idStart = markerAt + MARKER_PREFIX.length();
            int idEnd = translated.indexOf(MARKER_SUFFIX, idStart);
            if (idEnd < 0) break;
            if (currentId != null) {
                String text = clean(translated.substring(contentStart, markerAt));
                if (!text.isEmpty()) out.put(currentId, text);
            }
            currentId = translated.substring(idStart, idEnd);
            contentStart = idEnd + MARKER_SUFFIX.length();
            p = contentStart;
        }
        if (currentId != null && contentStart >= 0 && contentStart <= translated.length()) {
            String text = clean(translated.substring(contentStart));
            if (!text.isEmpty()) out.put(currentId, text);
        }
        return out;
    }

    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (!t.isEmpty() && (t.charAt(0) == ':' || t.charAt(0) == '-' || t.charAt(0) == '—')) {
            t = t.substring(1).trim();
        }
        return t;
    }

    static List<LyricLine> merge(List<LyricLine> base, Map<String, String> translatedById) {
        if (base == null || base.isEmpty() || translatedById == null || translatedById.isEmpty()) {
            return base == null ? Collections.<LyricLine>emptyList() : base;
        }
        ArrayList<LyricLine> out = new ArrayList<>(base.size());
        boolean changed = false;
        for (int i = 0; i < base.size(); i++) {
            LyricLine line = base.get(i);
            if (line == null) {
                out.add(line);
                continue;
            }
            String translated = translatedById.get(idOf(i, line));
            String secondary = translated == null || translated.trim().isEmpty()
                    ? null : translated;
            if (!java.util.Objects.equals(line.translation, secondary)) changed = true;
            LyricLine copy = new LyricLine(line.text, secondary, line.roma, line.start, line.end,
                    line.opposite, line.sylStart, line.sylEnd, line.charEnd);
            copy.bg = line.bg;
            out.add(copy);
        }
        return changed ? out : base;
    }

    static String cacheKey(String trackKey, String endpoint, String sourceLang, String targetLang,
                           List<LyricLine> lines) {
        String ep = normalizeEndpoint(endpoint);
        String source = normLang(sourceLang, "auto");
        String target = normLang(targetLang, Locale.getDefault().getLanguage());
        return safe(trackKey) + "|" + ep + "|" + source + "|" + target + "|" + linesHash(lines);
    }

    static String normalizeEndpoint(String endpoint) {
        if (endpoint == null) return "";
        String e = endpoint.trim();
        while (e.endsWith("/")) e = e.substring(0, e.length() - 1);
        return e;
    }

    static String normLang(String lang, String fallback) {
        String l = lang == null ? "" : lang.trim().toLowerCase(java.util.Locale.ROOT);
        if (l.isEmpty() || "auto".equals(l)) return fallback;
        return l;
    }

    static long linesHash(List<LyricLine> lines) {
        CRC32 crc = new CRC32();
        if (lines != null) {
            for (LyricLine line : lines) {
                if (line == null) continue;
                put(crc, line.start);
                put(crc, line.end);
                put(crc, line.text);
            }
        }
        return crc.getValue();
    }

    static boolean hasNonAsciiLetters(List<LyricLine> lines) {
        if (lines == null) return false;
        for (LyricLine line : lines) {
            if (line == null || line.text == null) continue;
            for (int i = 0; i < line.text.length(); i++) {
                char c = line.text.charAt(i);
                if (c > 127 && Character.isLetter(c)) return true;
            }
        }
        return false;
    }

    private static void put(CRC32 crc, int value) {
        crc.update((value >>> 24) & 0xff);
        crc.update((value >>> 16) & 0xff);
        crc.update((value >>> 8) & 0xff);
        crc.update(value & 0xff);
    }

    private static void put(CRC32 crc, String s) {
        if (s == null) return;
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        crc.update(b, 0, b.length);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
