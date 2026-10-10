package com.os4.musiccover;

import java.util.List;
import java.util.ArrayList;

/** Dispatches locally available reading engines on the lyric worker. */
final class LocalRomanizer {
    private LocalRomanizer() { }
    // Derived readings must match both the engine revision and the enabled scripts.
    static final int REVISION = 1;
    static int settingsMask() {
        return (LockLyrics.sOnDeviceTransliteration ? 1 : 0)
                | (LockLyrics.sLocalChinese ? 2 : 0)
                | (LockLyrics.sLocalKorean ? 4 : 0)
                | (LockLyrics.sLocalCyrillic ? 8 : 0)
                | (LockLyrics.sLocalGreek ? 16 : 0)
                | (LockLyrics.sLocalJapanese ? 32 : 0);
    }
    static boolean currentReading(LyricLine line) {
        return line.localRomaRevision == REVISION && line.localRomaSettings == settingsMask();
    }
    /** Work on private rows so a cancelled task cannot mutate visible or cached lyrics. */
    static List<LyricLine> copyLines(List<LyricLine> lines) {
        List<LyricLine> copies = new ArrayList<>(lines.size());
        for (LyricLine line : lines) copies.add(copyLine(line));
        return copies;
    }
    private static LyricLine copyLine(LyricLine line) {
        if (line == null) return null;
        LyricLine copy = new LyricLine(line.text, line.translation, line.roma, line.start, line.end,
                line.opposite, line.sylStart, line.sylEnd, line.charEnd);
        copy.onlineTranslation = line.onlineTranslation;
        copyReading(line, copy);
        copy.bg = copyLine(line.bg);
        return copy;
    }
    static void copyReadings(List<LyricLine> from, List<LyricLine> to) {
        for (int i = 0; i < from.size(); i++) copyReading(from.get(i), to.get(i));
    }
    private static void copyReading(LyricLine from, LyricLine to) {
        if (from == null || to == null) return;
        to.localRoma = from.localRoma;
        to.localRomaSettings = from.localRomaSettings;
        to.localRomaRevision = from.localRomaRevision;
        copyReading(from.bg, to.bg);
    }
    static void invalidateReadings(List<LyricLine> lines) {
        if (lines != null) for (LyricLine line : lines) invalidateReading(line);
    }
    private static void invalidateReading(LyricLine line) {
        if (line == null) return;
        line.localRoma = null;
        line.localRomaRevision = 0;
        line.localRomaSettings = 0;
        invalidateReading(line.bg);
    }
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
    /** Whether current settings allow a stored local reading for this script. */
    static boolean enabledFor(String text) {
        if (!LockLyrics.sOnDeviceTransliteration) return false;
        if (LockLyrics.sLocalJapanese && JapaneseRomanizer.handles(text)) return true;
        return LockLyrics.sLocalChinese && ChineseRomanizer.handles(text)
                || SimpleScriptRomanizer.handles(text);
    }

}
