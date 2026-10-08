package com.os4.musiccover;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure search hints, not evidence that two recordings (or their lyrics) are interchangeable. */
final class LyricSearchAliases {
    private LyricSearchAliases() {
    }

    static final int MAX_VARIANTS = 8;
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final String FEATURE = "(?:feat\\.?|ft\\.?|featuring)";
    private static final Pattern FEATURE_CREDIT = Pattern.compile(
            "(?iu)^" + FEATURE + "\\s+(.+)$");
    private static final Pattern BRACKETS = Pattern.compile(
            "\\(([^()]*)\\)|\\[([^\\[\\]]*)\\]|\\{([^{}]*)\\}");
    private static final Pattern FEATURE_TAIL = Pattern.compile(
            "(?iu)\\s+(?:[-–—]\\s*)?" + FEATURE + "\\s+([^()\\[\\]{}]+)$");
    private static final Pattern SOURCE_CREDIT = Pattern.compile("(?iu)^from\\s+(.+)$");
    private static final Pattern SOURCE_TAIL = Pattern.compile("(?iu)\\s+[-–—]\\s*from\\s+(.+)$");
    private static final Pattern VERSION_QUALIFIER = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:part|pt\\.?|vol\\.?|volume|version|ver\\.?|"
                    + "edit|mix|take|session)(?![\\p{L}\\p{N}])");
    private static final Pattern ARTIST_SEPARATOR = Pattern.compile(
            "(?iu)\\s+" + FEATURE + "\\s+|[&,，、/／;；]");
    private static final Pattern RECORDING = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:live|remix(?:ed)?|acoustic|remaster(?:ed)?|"
                    + "cover|instrumental|demo|unplugged|radio\\s+edit)(?![\\p{L}\\p{N}])"
                    + "|现场|演唱会|混音|不插电|翻唱|重制|重置|伴奏");

    /**
     * Original and combined-credit hints first, then the same core title separately with
     * each credited artist, including explicit title guests. Finally, an explicit featured title
     * is searched with each artist from the original metadata, preserving the feature wording.
     * Every hint retains the album/duration and must still go through catalogue validation.
     */
    static List<NcmLyrics.Query> variants(NcmLyrics.Query q) {
        if (q == null) return Collections.emptyList();
        Map<String, NcmLyrics.Query> out = new LinkedHashMap<>();
        out.put(q.key(), q);
        String title = coreTitle(q.title);
        List<String> artists = splitArtists(q.artist);
        for (String guest : splitArtists(String.join(" / ", titleFeatures(q.title)))) {
            if (!containsArtist(artists, guest)) artists.add(guest);
        }
        add(out, q, title, q.artist);
        if (artists.size() > 1) add(out, q, title, canonicalArtists(artists));
        for (String artist : artists) add(out, q, title, artist);
        // Some catalogues index the feature title as the song's name, but only one primary
        // artist at a time. Keep these exact-title hints after the safer core-title searches.
        for (String artist : splitArtists(q.artist)) add(out, q, q.title, artist);
        return Collections.unmodifiableList(new ArrayList<>(out.values()));
    }

    private static void add(Map<String, NcmLyrics.Query> out, NcmLyrics.Query q,
                            String title, String artist) {
        if (out.size() >= MAX_VARIANTS || title.isEmpty()) return;
        NcmLyrics.Query alias = q.withNames(title, artist);
        out.putIfAbsent(alias.key(), alias);
    }

    private static String canonicalArtists(List<String> artists) {
        List<String> out = new ArrayList<>();
        for (String artist : artists) out.add(text(artist).toLowerCase(Locale.ROOT));
        return String.join(" / ", out);
    }

    /** Remove catalogue annotations, not recording/version distinctions or arbitrary subtitles. */
    static String coreTitle(String value) {
        String title = text(value);
        Matcher source = SOURCE_TAIL.matcher(title);
        if (source.find() && !versionLabel(source.group(1))) {
            title = text(title.substring(0, source.start()));
        }
        Matcher brackets = BRACKETS.matcher(title);
        StringBuffer out = new StringBuffer();
        while (brackets.find()) {
            String content = bracketContent(brackets);
            String prefix = title.substring(0, brackets.start());
            boolean annotation = featureName(content) != null || titleAnnotation(prefix, content);
            String replacement = annotation ? " " : brackets.group();
            brackets.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        brackets.appendTail(out);
        title = text(out.toString());
        Matcher tail = FEATURE_TAIL.matcher(title);
        if (tail.find() && !RECORDING.matcher(tail.group(1)).find()) {
            title = title.substring(0, tail.start()).trim();
        }
        return title;
    }

    private static boolean versionLabel(String value) {
        return RECORDING.matcher(value).find() || VERSION_QUALIFIER.matcher(value).find();
    }

    private static boolean titleAnnotation(String prefix, String content) {
        if (versionLabel(content)) return false;
        if (SOURCE_CREDIT.matcher(text(content)).matches()) return true;
        // Catalogue bilingual titles such as 瞬息甜味 (Sugar Time), not Song (Part II).
        // Exact normalized artist and duration/album evidence are still required by the matcher.
        return hasCjk(prefix) && !hasLatin(prefix) && hasLatin(content) && !hasCjk(content)
                || hasLatin(prefix) && !hasCjk(prefix) && hasCjk(content) && !hasLatin(content);
    }

    private static boolean hasCjk(String value) {
        return value.codePoints().anyMatch(cp -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL;
        });
    }

    private static boolean hasLatin(String value) {
        return value.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp)
                == Character.UnicodeScript.LATIN);
    }

    private static List<String> titleFeatures(String value) {
        String title = text(value);
        List<String> out = new ArrayList<>();
        Matcher brackets = BRACKETS.matcher(title);
        while (brackets.find()) {
            String guest = featureName(bracketContent(brackets));
            if (guest != null) out.add(guest);
        }
        Matcher tail = FEATURE_TAIL.matcher(title);
        if (tail.find() && !RECORDING.matcher(tail.group(1)).find()) out.add(text(tail.group(1)));
        return out;
    }

    private static String bracketContent(Matcher m) {
        for (int i = 1; i <= 3; i++) if (m.group(i) != null) return m.group(i);
        return "";
    }

    private static String featureName(String value) {
        Matcher m = FEATURE_CREDIT.matcher(text(value));
        return m.matches() && !RECORDING.matcher(m.group(1)).find() ? text(m.group(1)) : null;
    }

    /** Same tokenization for session credits and provider comma/&/feat-separated credits. */
    static List<String> splitArtists(String value) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher brackets = BRACKETS.matcher(text(value));
        StringBuffer credits = new StringBuffer();
        while (brackets.find()) {
            String guest = featureName(bracketContent(brackets));
            brackets.appendReplacement(credits, Matcher.quoteReplacement(
                    guest == null ? brackets.group() : " / " + guest));
        }
        brackets.appendTail(credits);
        for (String part : ARTIST_SEPARATOR.split(credits.toString())) {
            String artist = text(part);
            String key = artistKey(artist);
            if (!key.isEmpty()) out.putIfAbsent(key, artist);
        }
        return new ArrayList<>(out.values());
    }

    static List<String> artistKeys(String value) {
        List<String> out = new ArrayList<>();
        for (String artist : splitArtists(value)) out.add(artistKey(artist));
        return out;
    }

    /** Exact tokens, not substring matching ("San" is not "San-Z"). */
    static boolean artistsOverlap(String local, String remote) {
        return keysOverlap(artistKeys(local), artistKeys(remote));
    }

    static boolean keysOverlap(List<String> local, List<String> remote) {
        for (String key : local) if (!key.isEmpty() && remote.contains(key)) return true;
        return false;
    }

    private static boolean containsArtist(List<String> artists, String artist) {
        String key = artistKey(artist);
        for (String a : artists) if (key.equals(artistKey(a))) return true;
        return false;
    }

    private static String artistKey(String value) {
        String key = compact(value);
        if (key.equals("unknown") || key.equals("unknownartist") || key.equals("null")
                || key.equals("未知") || key.equals("未知歌手") || key.equals("未知艺术家")) return "";
        return key;
    }

    /** Unicode width, punctuation, whitespace and case normalization; no Android dependencies. */
    static String compact(String value) {
        String s = text(value).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        s.codePoints().filter(Character::isLetterOrDigit).forEach(out::appendCodePoint);
        return out.toString();
    }

    private static String text(String value) {
        if (value == null) return "";
        return SPACE.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC)).replaceAll(" ").trim();
    }
}
