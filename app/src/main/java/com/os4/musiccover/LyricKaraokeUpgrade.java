package com.os4.musiccover;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure, fail-closed replacement policy; does not fetch lyrics or mutate either input. */
public final class LyricKaraokeUpgrade {
    private static final long TIME_TOLERANCE_MS = 2000;

    private LyricKaraokeUpgrade() {}

    /** Whether any lead line has actual word arrays, as defined by LyricLine.hasWords(). */
    public static boolean words(List<LyricLine> lines) {
        if (lines != null) for (LyricLine line : lines) {
            if (line != null && line.hasWords()) return true;
        }
        return false;
    }

    /**
     * Returns an independent candidate copy, or null when replacement is unsafe.
     * Requires complete normalized text equality in lyric order (ignoring line segmentation,
     * punctuation and case), at least 90% word-timed text, and timing agreement within 2s.
     * Existing lead word timing is never replaced. Background text is not song identity.
     * Missing metadata is inherited only at identical normalized whole-line boundaries,
     * starts within 2s, and matching singer flags. Every baseline line with translation or
     * background vocals must map this way; otherwise replacement would lose secondary text.
     */
    public static List<LyricLine> merge(List<LyricLine> baseline, List<LyricLine> candidate) {
        if (words(baseline) || !words(candidate)) return null;
        List<Span> old = spans(baseline);
        List<Span> next = spans(candidate);
        if (old == null || next == null || old.isEmpty() || next.isEmpty()) return null;
        StringBuilder oldText = new StringBuilder();
        StringBuilder newText = new StringBuilder();
        long timed = 0;
        for (Span span : old) oldText.append(span.text);
        for (Span span : next) {
            newText.append(span.text);
            if (span.line.hasWords()) timed += span.text.length();
        }
        // Full equality avoids a short chorus or a mostly matching alternate verse passing.
        if (!oldText.toString().equals(newText.toString())
                || timed * 10 < (long) newText.length() * 9) return null;

        int j = 0;
        for (Span span : old) {
            while (next.get(j).limit() <= span.offset) j++;
            Span target = next.get(j);
            int local = span.offset - target.offset;
            if (!near(span.line.start, onset(target, local))) return null;
            if (local == 0 && !near(span.line.start, target.line.start)) return null;
        }
        int i = 0;
        for (Span span : next) {
            while (old.get(i).limit() <= span.offset) i++;
            if (!inside(old.get(i).line, span.line.start)) return null;
            if (span.line.hasWords()) {
                int wordIndex = i;
                for (int k = 0; k < span.line.sylStart.length; k++) {
                    int from = k == 0 ? 0 : span.line.charEnd[k - 1];
                    int offset = span.offset + normalize(span.line.text.substring(0, from)).length();
                    int limit = span.offset
                            + normalize(span.line.text.substring(0, span.line.charEnd[k])).length();
                    // Punctuation-only syllables carry no comparable sung text.
                    if (limit <= offset) continue;
                    while (old.get(wordIndex).limit() <= offset) wordIndex++;
                    if (!inside(old.get(wordIndex).line, span.line.sylStart[k])) return null;
                    while (old.get(wordIndex).limit() < limit) wordIndex++;
                    if (!inside(old.get(wordIndex).line, span.line.sylEnd[k])) return null;
                }
            }
        }

        int secondaryLines = 0;
        for (LyricLine line : baseline) {
            if (line.translation != null || line.onlineTranslation != null || line.bg != null) {
                secondaryLines++;
            }
        }
        int matchedSecondaryLines = 0;
        List<LyricLine> result = new ArrayList<>(candidate.size());
        int spanIndex = 0;
        int oldIndex = 0;
        for (LyricLine line : candidate) {
            LyricLine source = null;
            if (!normalize(line.text).isEmpty()) {
                Span span = next.get(spanIndex++);
                while (oldIndex < old.size() && old.get(oldIndex).offset < span.offset) oldIndex++;
                if (oldIndex < old.size()) {
                    Span match = old.get(oldIndex);
                    if (match.offset == span.offset && match.text.equals(span.text)
                            && near(match.line.start, line.start)
                            && match.line.opposite == line.opposite) source = match.line;
                }
            }
            if (source != null && (source.translation != null || source.onlineTranslation != null
                    || source.bg != null)) {
                matchedSecondaryLines++;
            }
            // A matched baseline background with words must not become line-timed either.
            if (source != null && source.bg != null && source.bg.hasWords()
                    && line.bg != null && !line.bg.hasWords()) return null;
            LyricLine merged = copy(line, line.translation == null && source != null
                    ? source.translation : line.translation);
            merged.onlineTranslation = line.onlineTranslation != null ? line.onlineTranslation
                    : source == null ? null : source.onlineTranslation;
            merged.bg = copyTree(line.bg != null ? line.bg : source == null ? null : source.bg);
            if (merged.bg != null) merged.end = Math.max(merged.end, merged.bg.end);
            result.add(merged);
        }
        if (matchedSecondaryLines != secondaryLines) return null;
        return result;
    }

    private static boolean near(int a, int b) {
        return Math.abs((long) a - b) <= TIME_TOLERANCE_MS;
    }

    private static boolean inside(LyricLine line, int time) {
        return (long) time >= (long) line.start - TIME_TOLERANCE_MS
                && (long) time <= (long) line.end + TIME_TOLERANCE_MS;
    }

    private static int onset(Span span, int local) {
        LyricLine line = span.line;
        if (!line.hasWords()) return line.start;
        for (int k = 0; k < line.charEnd.length; k++) {
            if (normalize(line.text.substring(0, line.charEnd[k])).length() > local) {
                return line.sylStart[k];
            }
        }
        return line.start;
    }

    private static List<Span> spans(List<LyricLine> lines) {
        if (lines == null || lines.isEmpty()) return null;
        List<Span> result = new ArrayList<>();
        int offset = 0;
        int previousStart = Integer.MIN_VALUE;
        for (LyricLine line : lines) {
            if (line == null || line.text == null || line.start < previousStart) return null;
            previousStart = line.start;
            String text = normalize(line.text);
            if (line.hasWords()) {
                int previousChar = 0;
                int previousTime = Integer.MIN_VALUE;
                for (int k = 0; k < line.charEnd.length; k++) {
                    int end = line.charEnd[k];
                    if (end <= previousChar || end > line.text.length()
                            || line.sylStart[k] < previousTime
                            || line.sylEnd[k] < line.sylStart[k]) return null;
                    previousChar = end;
                    previousTime = line.sylStart[k];
                }
                if (!normalize(line.text.substring(previousChar)).isEmpty()) return null;
            }
            if (!text.isEmpty()) {
                result.add(new Span(line, text, offset));
                offset += text.length();
            }
        }
        return result;
    }

    private static String normalize(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < folded.length();) {
            int cp = folded.codePointAt(i);
            int type = Character.getType(cp);
            if (Character.isLetterOrDigit(cp) || type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK) result.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return result.toString();
    }

    private static LyricLine copy(LyricLine line, String translation) {
        return new LyricLine(line.text, translation, line.start, line.end, line.opposite,
                line.sylStart == null ? null : line.sylStart.clone(),
                line.sylEnd == null ? null : line.sylEnd.clone(),
                line.charEnd == null ? null : line.charEnd.clone());
    }

    private static LyricLine copyTree(LyricLine line) {
        if (line == null) return null;
        LyricLine result = copy(line, line.translation);
        result.onlineTranslation = line.onlineTranslation;
        result.bg = copyTree(line.bg);
        return result;
    }

    private static final class Span {
        final LyricLine line;
        final String text;
        final int offset;

        Span(LyricLine line, String text, int offset) {
            this.line = line;
            this.text = text;
            this.offset = offset;
        }

        int limit() { return offset + text.length(); }
    }
}
