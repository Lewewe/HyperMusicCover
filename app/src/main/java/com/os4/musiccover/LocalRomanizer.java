package com.os4.musiccover;

import java.util.List;

/** Dispatches locally available reading engines on the lyric worker. */
final class LocalRomanizer {
    private LocalRomanizer() { }
    // Shares the original local-romaji cache schema; each engine also owns a distinct script,
    // so a cached Japanese result can never be mistaken for Chinese/Korean/etc.
    static final int REVISION = 1;
    static boolean needsApply(List<LyricLine> lines) {
        return JapaneseRomanizer.needsApply(lines) || ChineseRomanizer.needsApply(lines)
                || SimpleScriptRomanizer.needsApply(lines);
    }
    static void apply(List<LyricLine> lines) {
        JapaneseRomanizer.apply(lines);
        ChineseRomanizer.apply(lines);
        SimpleScriptRomanizer.apply(lines);
    }
    static boolean handles(String text) {
        return JapaneseRomanizer.handles(text) || ChineseRomanizer.handles(text)
                || SimpleScriptRomanizer.handles(text);
    }
}
