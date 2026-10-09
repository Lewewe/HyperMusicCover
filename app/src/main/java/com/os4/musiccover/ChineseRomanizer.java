package com.os4.musiccover;

import java.util.List;

/** Basic tone-less Hanyu pinyin, with one canonical reading per Han character. */
final class ChineseRomanizer {
    static final int REVISION = 1;
    private ChineseRomanizer() { }
    static boolean handles(String text) { return hasHan(text); }
    static boolean needsApply(List<LyricLine> lines) { return LockLyrics.sOnDeviceTransliteration && LockLyrics.sLocalChinese && ChineseDictionary.ready(Main.appContext()) && any(lines); }
    static void apply(List<LyricLine> lines) { if (needsApply(lines)) for (LyricLine line : lines) apply(line); }
    private static boolean any(List<LyricLine> lines) { for (LyricLine line : lines) if (line != null && (needs(line) || line.bg != null && needs(line.bg))) return true; return false; }
    private static boolean needs(LyricLine line) { return line.localRomaRevision != LocalRomanizer.REVISION && hasHan(line.text); }
    private static void apply(LyricLine line) { if (line == null) return; if (needs(line)) { String r = romanize(line.text); if (r != null) { line.localRoma = r; line.localRomaRevision = LocalRomanizer.REVISION; } } apply(line.bg); }
    private static String romanize(String text) { StringBuilder out = new StringBuilder(); boolean space = true; for (int i = 0; i < text.length();) { int cp = text.codePointAt(i); String r = ChineseDictionary.reading(Main.appContext(), cp); if (r != null) { if (!space && out.length() > 0) out.append(' '); out.append(r); space = false; } else { out.appendCodePoint(cp); space = Character.isWhitespace(cp); } i += Character.charCount(cp); } String value = out.toString().trim(); return value.isEmpty() ? null : value; }
    private static boolean hasHan(String text) { if (text == null) return false; for (int i = 0; i < text.length();) { int cp = text.codePointAt(i); if (cp >= 0x3400 && cp <= 0x9fff || cp >= 0xf900 && cp <= 0xfaff) return true; i += Character.charCount(cp); } return false; }
}
