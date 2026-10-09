package com.os4.musiccover;

/**
 * One lyric line, as the renderer draws it. Times are milliseconds from the start of the track.
 *
 * Public only because the parser is Kotlin, and Kotlin refuses to let a public function return a
 * package-private Java type.
 */
public final class LyricLine {
    final String text;
    /** Null when the file has no native translation or romanisation for this line. */
    final String translation;
    /** The selected online translation, which replaces native secondary text in online mode. */
    String onlineTranslation;
    /**
     * How the line is said in Latin letters - romaji, jyutping, a Korean romanisation - or null.
     * Kept apart from the translation because it has a switch of its own (LockLyrics.sRoma);
     * drawn over it when both are on (under()).
     */
    /** Provider-supplied romanisation. Kept intact as a fallback and for cache compatibility. */
    final String roma;
    /**
     * Locally generated Japanese romanisation. This is deliberately derived data rather than a
     * replacement for {@link #roma}: a dictionary update can improve it without losing what the
     * provider originally sent us, and unsupported scripts still have their provider fallback.
     */
    String localRoma;
    int localRomaRevision;
    final int start;
    /** Not final: a background vocal hung under this line can outlast it, and extends it. */
    int end;
    /** The second singer's line in a duet: drawn against the other edge. */
    final boolean opposite;

    /**
     * Word timing, or null for a line-timed file. syllable k covers the characters
     * [charEnd[k-1], charEnd[k]) of text and is sung from sylStart[k] to sylEnd[k].
     */
    final int[] sylStart;
    final int[] sylEnd;
    final int[] charEnd;

    /**
     * The background vocal sung over this line, if the file has one - drawn smaller underneath,
     * with its own word timing. Null for most lines.
     */
    LyricLine bg;

    public LyricLine(String text, String translation, int start, int end, boolean opposite,
                     int[] sylStart, int[] sylEnd, int[] charEnd) {
        this(text, translation, null, start, end, opposite, sylStart, sylEnd, charEnd);
    }

    public LyricLine(String text, String translation, String roma, int start, int end,
                     boolean opposite, int[] sylStart, int[] sylEnd, int[] charEnd) {
        this.text = text;
        this.translation = translation == null || translation.trim().isEmpty()
                ? null : translation.trim();
        this.roma = roma == null || roma.trim().isEmpty() ? null : roma.trim();
        this.start = start;
        // A database file is edited by hand; a line that ends before it starts is clamped here
        // rather than trusted into a negative duration later.
        this.end = Math.max(end, start);
        this.opposite = opposite;
        boolean words = sylStart != null && sylStart.length > 0
                && sylEnd != null && sylEnd.length == sylStart.length
                && charEnd != null && charEnd.length == sylStart.length;
        this.sylStart = words ? sylStart : null;
        this.sylEnd = words ? sylEnd : null;
        this.charEnd = words ? charEnd : null;
        // A word-timed line is not over while its last word is still being sung, whatever end
        // the file reported. Most word-timed files give a line no end of its own - enhanced LRC
        // is a timestamp per word and nothing else - and lyrics-core reads the line's end off
        // its last syllable, which for the last word is that word's own start; the parser then
        // gives that word the room the file was leaving it. The renderer moves the stack on at
        // this end and stops drawing the word path at it, so an end ahead of the last syllable
        // cuts its fill off mid-sweep: on a line whose next one starts within a second, the
        // last word gets no sweep at all and the line leaves just as it begins.
        if (words) {
            // Some providers preserve source word order even when a sustained/ad-lib word ends
            // after a later token. The line remains live until every timed word has ended.
            for (int wordEnd : sylEnd) this.end = Math.max(this.end, wordEnd);
        }
    }

    /**
     * How far through the text the singing is at ms, in characters, fractional inside the
     * syllable being sung. 0 before the line, text.length() after it; a line-timed file is
     * all-or-nothing.
     */
    float sungChars(int ms) {
        if (LockLyrics.sSearchMode == LockLyrics.SEARCH_ORIGINAL) return originalSungChars(ms);
        int n = text.length();
        if (sylStart == null) return ms >= start ? n : 0f;
        int count = sylStart.length;
        float progress = 0f;
        for (int k = 0; k < count; k++) {
            int from = k == 0 ? 0 : charEnd[k - 1];
            int to = Math.max(from, Math.min(n, charEnd[k]));
            int effectiveStart = sylStart[k];
            // Repaired tokens with tied starts occupy consecutive spans, not simultaneous fills.
            if (k > 0 && sylStart[k] == sylStart[k - 1] && sylEnd[k] > sylEnd[k - 1]) {
                effectiveStart = Math.max(effectiveStart, sylEnd[k - 1]);
            }
            if (ms < effectiveStart) continue;
            if (sylEnd[k] <= effectiveStart) {
                if (ms >= sylEnd[k]) progress = Math.max(progress, to);
                continue;
            }
            if (ms < sylEnd[k]) {
                float f = (ms - effectiveStart) / (float) (sylEnd[k] - effectiveStart);
                progress = Math.max(progress, from + f * (to - from));
            } else {
                progress = Math.max(progress, to);
            }
        }
        return Math.min(n, progress);
    }

    private float originalSungChars(int ms) {
        int n = text.length();
        if (sylStart == null) return ms >= start ? n : 0f;
        if (ms <= sylStart[0]) return 0f;
        int count = sylStart.length;
        for (int k = 0; k < count; k++) {
            int from = k == 0 ? 0 : charEnd[k - 1];
            if (ms < sylStart[k]) return from;
            if (ms < sylEnd[k]) {
                float f = (ms - sylStart[k]) / (float) Math.max(1, sylEnd[k] - sylStart[k]);
                return from + f * (charEnd[k] - from);
            }
        }
        return n;
    }

    boolean hasWords() {
        return sylStart != null;
    }

    /**
     * Whether a timed token is actually in progress. This is deliberately independent of the
     * line end and of the character map: providers occasionally give the final token an invalid
     * character range, but it is still an audible held word and must keep its row alive.
     */
    boolean singingWordAt(int ms) {
        if (sylStart == null) return false;
        for (int k = 0; k < sylStart.length; k++) {
            if (ms >= sylStart[k] && ms < sylEnd[k]) return true;
        }
        return false;
    }

    boolean hasDisplayWords() {
        return hasWords() && !usesRomaAsMain();
    }

    boolean usesRomaAsMain() {
        return false;
    }

    String displayText() {
        return usesRomaAsMain() ? roma : text;
    }

    String displayRoma() {
        return localRoma != null && localRomaRevision == LocalRomanizer.REVISION ? localRoma : roma;
    }

    /** Presentation value: Japanese provider roma is intentionally never a fallback. */
    String renderedRoma() {
        return LockLyrics.sOnDeviceTransliteration && localRoma != null
                && localRomaRevision == LocalRomanizer.REVISION ? localRoma
                : LocalRomanizer.handles(text) ? null : roma;
    }

    /**
     * The text drawn under the line for the switches in mode (LockLyrics.below()): the
     * romanisation over the translation, either alone, or null for nothing.
     */
    String under(int mode) {
        String r = (mode & LockLyrics.BELOW_ROMA) != 0 ? displayRoma() : null;
        String t = (mode & LockLyrics.BELOW_TRANS) != 0 ? translation : null;
        if (r == null) return t;
        return t == null ? r : r + "\n" + t;
    }
}
