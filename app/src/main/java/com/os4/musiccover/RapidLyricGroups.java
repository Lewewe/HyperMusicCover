package com.os4.musiccover;

import java.util.Arrays;
import java.util.List;

/** Keeps quick successive lines readable without changing their playback or word timestamps. */
final class RapidLyricGroups {
    static final int QUICK_MS = 1200;
    static final int MAX_LINES = 4;
    private final int[] heads;
    private final int[] tails;

    RapidLyricGroups(List<LyricLine> lines) {
        heads = new int[lines.size()];
        tails = new int[lines.size()];
        for (int first = 0; first < lines.size();) {
            int last = first;
            while (last + 1 < lines.size() && last - first + 1 < MAX_LINES
                    && quick(lines.get(last), lines.get(last + 1))) last++;
            Arrays.fill(heads, first, last + 1, first);
            Arrays.fill(tails, first, last + 1, last);
            first = last + 1;
        }
    }

    private static boolean quick(LyricLine previous, LyricLine next) {
        long interval = (long) next.start - previous.start;
        // Simultaneous/overlapping duet lines and a silent break retain their usual layout.
        boolean wordTimedSequence = previous.hasWords() && next.hasWords();
        boolean closeLineSequence = previous.end <= (long) next.start + 150
                && (long) next.start - previous.end <= 600;
        return interval > 0 && interval <= QUICK_MS && !previous.opposite && !next.opposite
                && (wordTimedSequence || closeLineSequence)
                && !previous.text.trim().isEmpty() && !next.text.trim().isEmpty();
    }

    boolean grouped(int index) {
        return index >= 0 && index < heads.length && heads[index] != tails[index];
    }

    /** Packs the first/last line of the fitting subgroup; bounded by four, with no frame allocation. */
    long range(int index, float[] base, float[] height, float available) {
        int first = heads[index], end = tails[index];
        while (first <= end) {
            int last = first;
            while (last < end && base[last + 1] - base[first] + height[last + 1] <= available) last++;
            if (index <= last) return pack(first, last);
            first = last + 1;
        }
        return pack(index, index);
    }

    private static long pack(int first, int last) {
        return ((long) first << 32) | (last & 0xffffffffL);
    }

    static int first(long range) { return (int) (range >>> 32); }
    static int last(long range) { return (int) range; }
}
