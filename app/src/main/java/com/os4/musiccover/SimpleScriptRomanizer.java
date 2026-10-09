package com.os4.musiccover;

import java.util.List;

/** Small no-data-pack romanisers for scripts with deterministic letter readings. */
final class SimpleScriptRomanizer {
    private SimpleScriptRomanizer() { }
    static boolean handles(String text) { return enabled() && script(text) != 0; }
    static boolean needsApply(List<LyricLine> lines) {
        if (!enabled() || lines == null) return false;
        for (LyricLine line : lines) if (line != null && (needs(line) || line.bg != null && needs(line.bg))) return true;
        return false;
    }
    static void apply(List<LyricLine> lines) { if (lines != null) for (LyricLine line : lines) apply(line); }
    private static boolean enabled() { return LockLyrics.sOnDeviceTransliteration && (LockLyrics.sLocalKorean || LockLyrics.sLocalCyrillic || LockLyrics.sLocalGreek); }
    private static boolean needs(LyricLine l) { return l.localRomaRevision != LocalRomanizer.REVISION && script(l.text) != 0; }
    private static void apply(LyricLine l) { if (l == null) return; if (needs(l)) { String r = romanize(l.text); if (r != null) { l.localRoma = r; l.localRomaRevision = LocalRomanizer.REVISION; } } apply(l.bg); }
    private static int script(String text) { if (text == null) return 0; for (int i = 0; i < text.length();) { int cp = text.codePointAt(i); if (LockLyrics.sLocalKorean && cp >= 0xac00 && cp <= 0xd7a3) return 1; if (LockLyrics.sLocalCyrillic && cp >= 0x0400 && cp <= 0x052f) return 2; if (LockLyrics.sLocalGreek && cp >= 0x0370 && cp <= 0x03ff) return 3; i += Character.charCount(cp); } return 0; }
    private static String romanize(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length();) { int cp = text.codePointAt(i); String s = korean(cp); if (s == null) s = cyrillic(cp); if (s == null) s = greek(cp); if (s == null) out.appendCodePoint(cp); else out.append(s); i += Character.charCount(cp); }
        String value = out.toString().trim(); return value.isEmpty() ? null : value;
    }
    private static String korean(int cp) {
        if (!LockLyrics.sLocalKorean || cp < 0xac00 || cp > 0xd7a3) return null;
        final String[] lead = {"g","kk","n","d","tt","r","m","b","pp","s","ss","","j","jj","ch","k","t","p","h"};
        final String[] vowel = {"a","ae","ya","yae","eo","e","yeo","ye","o","wa","wae","oe","yo","u","wo","we","wi","yu","eu","ui","i"};
        final String[] tail = {"","k","k","k","n","n","n","t","l","k","m","p","l","l","l","p","l","m","p","p","t","t","ng","t","t","k","t","p","t"};
        int n = cp - 0xac00; return lead[n / 588] + vowel[n % 588 / 28] + tail[n % 28];
    }
    private static String cyrillic(int cp) {
        if (!LockLyrics.sLocalCyrillic || cp < 0x0400 || cp > 0x052f) return null;
        String src = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя";
        String[] dst = {"A","B","V","G","D","E","Yo","Zh","Z","I","Y","K","L","M","N","O","P","R","S","T","U","F","Kh","Ts","Ch","Sh","Shch","","Y","","E","Yu","Ya","a","b","v","g","d","e","yo","zh","z","i","y","k","l","m","n","o","p","r","s","t","u","f","kh","ts","ch","sh","shch","","y","","e","yu","ya"};
        int index = src.indexOf(cp); return index < 0 ? null : dst[index];
    }
    private static String greek(int cp) {
        if (!LockLyrics.sLocalGreek || cp < 0x0370 || cp > 0x03ff) return null;
        String src = "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩαβγδεζηθικλμνξοπρσςτυφχψω";
        String[] dst = {"A","V","G","D","E","Z","I","Th","I","K","L","M","N","X","O","P","R","S","T","Y","F","Ch","Ps","O","a","v","g","d","e","z","i","th","i","k","l","m","n","x","o","p","r","s","s","t","y","f","ch","ps","o"};
        int index = src.indexOf(cp); return index < 0 ? null : dst[index];
    }
}
