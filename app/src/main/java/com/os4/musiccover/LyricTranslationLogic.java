package com.os4.musiccover;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
            // Always translate the original lyric, never romanisation or a source translation.
            // Spicy lines are already assembled from syllable fragments by SpicyLyrics; use the
            // complete line so providers receive words/phrases, never individual syllables.
            if (line == null || line.text == null) continue;
            String text = line.text.trim();
            if (text.isEmpty()) continue;
            out.add(new Entry(i, idOf(i, line), text));
        }
        return out;
    }

    static List<Entry> entriesForTranslation(List<LyricLine> lines, String source, String target) {
    List<Entry> entries = entriesOf(lines);
    if (entries.isEmpty()) return entries;
    if (sameLanguage(source, target) || likelyLanguage(entries, target)) {
        return Collections.emptyList();
    }
    
    // Only translate lines containing Japanese script
    List<Entry> japaneseOnly = new ArrayList<>();
    for (Entry entry : entries) {
        if (hasJapaneseScript(entry.text)) {
            japaneseOnly.add(entry);
        }
    }
    return japaneseOnly;
}

    private static boolean sameLanguage(String source, String target) {
        String src = languageBase(source);
        String dst = languageBase(target);
        return !src.isEmpty() && !"auto".equals(src) && src.equals(dst);
    }

    private static String languageBase(String language) {
        if (language == null) return "";
        String code = language.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int separator = code.indexOf('-');
        return separator < 0 ? code : code.substring(0, separator);
    }

    private static boolean likelyLanguage(List<Entry> entries, String target) {
    String language = languageBase(target);
    if (language.isEmpty() || "auto".equals(language)) return false;
    StringBuilder text = new StringBuilder();
    for (Entry entry : entries) {
        if (text.length() > 0) text.append(' ');
        text.append(entry.text);
    }
    String all = text.toString();
    int kana = 0, hangul = 0, han = 0, greek = 0, thai = 0, armenian = 0, georgian = 0;
    for (int i = 0; i < all.length();) {
        int cp = all.codePointAt(i);
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        if (script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA) kana++;
        else if (script == Character.UnicodeScript.HANGUL) hangul++;
        else if (script == Character.UnicodeScript.HAN) han++;
        else if (script == Character.UnicodeScript.GREEK) greek++;
        else if (script == Character.UnicodeScript.THAI) thai++;
        else if (script == Character.UnicodeScript.ARMENIAN) armenian++;
        else if (script == Character.UnicodeScript.GEORGIAN) georgian++;
        i += Character.charCount(cp);
    }
    if ("ja".equals(language) && kana >= 2) return true;
    if ("ko".equals(language) && hangul >= 3) return true;
    if ("zh".equals(language) && han >= 4 && kana == 0) return true;
    if ("el".equals(language) && greek >= 4) return true;
    if ("th".equals(language) && thai >= 4) return true;
    if ("hy".equals(language) && armenian >= 4) return true;
    if ("ka".equals(language) && georgian >= 4) return true;

    // Do not flag as English if Japanese script is present
    if ("en".equals(language) && (kana > 0 || han > 0)) return false;

    return likelyLatinLanguage(all, language);
}

    private static boolean likelyLatinLanguage(String text, String target) {
        Map<String, Set<String>> markers = latinMarkers();
        Set<String> targetMarkers = markers.get(target);
        if (targetMarkers == null) return false;
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Set<String>> distinct = new HashMap<>();
        int words = 0;
        StringBuilder token = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            int cp = i == text.length() ? -1 : text.codePointAt(i);
            if (cp >= 0 && Character.isLetter(cp)) {
                token.appendCodePoint(cp);
                if (Character.charCount(cp) == 2) i++;
                continue;
            }
            if (token.length() > 0) {
                String word = fold(token.toString());
                token.setLength(0);
                words++;
                for (Map.Entry<String, Set<String>> language : markers.entrySet()) {
                    if (language.getValue().contains(word)) {
                        counts.put(language.getKey(), counts.containsKey(language.getKey())
                                ? counts.get(language.getKey()) + 1 : 1);
                        Set<String> found = distinct.get(language.getKey());
                        if (found == null) {
                            found = new HashSet<>();
                            distinct.put(language.getKey(), found);
                        }
                        found.add(word);
                    }
                }
            }
        }
        int score = counts.containsKey(target) ? counts.get(target) : 0;
        Set<String> targetWords = distinct.get(target);
        if (score < 2 || targetWords == null || targetWords.size() < 2) return false;
        // English lyrics are commonly short and repetitive, so a whole-song ratio can reject
        // valid English tracks after only a few marker words. Keep the stricter ratio for other
        // Latin languages, where overlap with English markers is more common.
        if (!"en".equals(target) && score * 6 < words) return false;
        for (Map.Entry<String, Integer> language : counts.entrySet()) {
            if (!target.equals(language.getKey()) && language.getValue() >= score) return false;
        }
        return true;
    }

    private static String fold(String word) {
        String decomposed = Normalizer.normalize(word.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD);
        StringBuilder result = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length();) {
            int cp = decomposed.codePointAt(i);
            int type = Character.getType(cp);
            if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK
                    && type != Character.ENCLOSING_MARK) result.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return result.toString();
    }

    private static Map<String, Set<String>> latinMarkers() {
        Map<String, Set<String>> markers = new HashMap<>();
        markers.put("en", words("i me my you your we they the and are is was were have do not to of "
                + "in on for with from what when where who will can this that he she it"));
        markers.put("es", words("yo tu te mi que el la los las de del y en un una por para con "
                + "como es soy eres estoy no pero porque cuando donde amor"));
        markers.put("fr", words("je tu nous vous il elle le la les des du et est suis sont pas "
                + "une un mon ma mes dans avec pour mais qui que amour"));
        markers.put("de", words("ich du er sie wir ihr der die das und ist sind bin nicht ein "
                + "eine mein meine mit auf fur zu von was wie liebe"));
        markers.put("it", words("io tu lui lei noi voi il lo la gli le di del della e che sono "
                + "sei non un una per con mi ti amore ma"));
        markers.put("pt", words("eu voce voces ele ela nos eles elas o a os as de do da e que nao "
                + "um uma para com meu minha seu sua amor estou sou"));
        markers.put("nl", words("ik jij je hij zij wij de het een en van is niet mijn met voor "
                + "liefde"));
        return markers;
    }

    private static Set<String> words(String text) {
        Set<String> result = new HashSet<>();
        Collections.addAll(result, text.split("\s+"));
        return result;
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
                out.putAll(parseMarkedTranslation((String) translated));
                String text = (String) translated;
                out.putAll(parseMarkedTranslation(text));
                if (out.isEmpty()) out.putAll(parseOrderedTranslation(text, entries));
            } else if (translated instanceof JSONArray) {
                JSONArray arr = (JSONArray) translated;
                for (int i = 0; i < arr.length(); i++) {
                    Object one = arr.get(i);
                    if (one instanceof String) out.putAll(parseMarkedTranslation((String) one));
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
                out.add(null);
                continue;
            }
            String translated = translatedById.get(idOf(i, line));
            if (translated == null || translated.trim().isEmpty()) {
                out.add(line);
                continue;
            }
            changed = true;
            LyricLine copy = new LyricLine(line.text, line.translation, line.roma, line.start, line.end,
                    line.opposite, line.sylStart, line.sylEnd, line.charEnd);
            copy.onlineTranslation = translated.trim();
            copy.bg = line.bg;
            out.add(copy);
        }
        return changed ? out : base;
    }

    static String cacheKey(String trackKey, String endpoint, String sourceLang, String targetLang,
                           List<LyricLine> lines) {
        String ep = normalizeEndpoint(endpoint);
        String source = normLang(sourceLang, "auto");
        String target = normLang(targetLang, "en");
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
