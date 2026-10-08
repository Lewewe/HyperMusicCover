package com.os4.musiccover;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Dependency-free JVM regressions; run tools/run-lyric-karaoke-upgrade-tests.py. */
public final class LyricKaraokeUpgradeTest {
    private static int checks;

    public static void main(String[] args) {
        wordDetection();
        normalization();
        segmentation();
        coverage();
        timing();
        metadata();
        noDowngrade();
        invalidInputs();
        isolation();
        System.out.println("LyricKaraokeUpgradeTest: " + checks + " checks passed");
    }

    private static void wordDetection() {
        check(!LyricKaraokeUpgrade.words(null), "null words");
        check(!LyricKaraokeUpgrade.words(Collections.<LyricLine>emptyList()), "empty words");
        check(!LyricKaraokeUpgrade.words(list(line("hello", 1000, 2000))), "line timing isn't words");
        check(LyricKaraokeUpgrade.words(list(null, word("hello", 1000, 2000))), "hasWords with null entry");
        LyricLine bad = new LyricLine("hello", null, 1000, 2000, false,
                new int[]{1000}, new int[]{2000, 2100}, new int[]{5});
        check(!bad.hasWords() && !LyricKaraokeUpgrade.words(list(bad)), "constructor rejects array mismatch");
        check(!LyricKaraokeUpgrade.words(list(new LyricLine("hello", null, 1000, 2000,
                false, new int[0], new int[0], new int[0]))), "empty arrays aren't words");
        LyricLine parent = line("hello", 1000, 2000);
        parent.bg = word("oh", 1000, 2000);
        check(!LyricKaraokeUpgrade.words(list(parent)), "background-only timing is not lead karaoke");
    }

    private static void normalization() {
        List<LyricLine> base = list(line("Hello, WORLD!", 1000, 4000), line("Don't go.", 5000, 8000));
        List<LyricLine> candidate = list(word("hello world", 1000, 4000), word("DONT GO", 5000, 8000));
        accepted(base, candidate, "case punctuation whitespace");
        accepted(list(line("Ｈｅｌｌｏ，世界！", 1000, 4000)), list(word("hello 世界", 1000, 4000)),
                "Unicode width and non-Latin text");
        accepted(list(line("Cafe\u0301", 1000, 4000)), list(word("CAFÉ", 1000, 4000)), "canonical accents");
        rejected(list(line("café", 1000, 4000)), list(word("cafe", 1000, 4000)), "don't erase accents");
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            accepted(list(line("I WILL", 1000, 4000)), list(word("i will", 1000, 4000)), "locale independent");
        } finally {
            Locale.setDefault(saved);
        }
        accepted(list(line("", 0, 500), line("hello", 1000, 4000), line("...", 4500, 5000)),
                list(word("HELLO", 1000, 4000)), "empty decorations do not affect identity");
        accepted(list(line("hello", 1000, 4000)),
                list(line("", 0, 500), word("hello", 1000, 4000), line("...", 4500, 5000)),
                "candidate decorations retained");
    }

    private static void segmentation() {
        LyricLine a = line("Hello world", 1000, 4000);
        LyricLine b = line("Stay here", 5000, 8000);
        LyricLine joined = timed("hello world stay here", 1000, 8000,
                new int[]{1000, 2500, 5000, 6500}, new int[]{2200, 4000, 6200, 8000},
                new int[]{6, 12, 17, 21});
        accepted(list(a, b), list(joined), "merged lines use interior word onset");
        accepted(list(line("Hello world stay here", 1000, 8000)),
                list(word("hello world", 1000, 4000), word("stay here", 5000, 8000)), "split lines");
        accepted(list(line("你好吗", 1000, 4000), line("我很好", 5000, 8000)),
                list(timed("你好吗我很好", 1000, 8000, new int[]{1000, 5000},
                        new int[]{4000, 8000}, new int[]{3, 6})), "Chinese segmentation");
        rejected(list(a, b), list(word("hello world stay here", 1000, 8000)),
                "combined line without interior timing evidence");
        rejected(list(a, b), list(timed("hello world stay here", 1000, 12000,
                new int[]{1000, 2500, 9000, 10500}, new int[]{2200, 4000, 10200, 12000},
                new int[]{6, 12, 17, 21})), "interior recording shift");
    }

    private static void coverage() {
        List<LyricLine> base = list(line("Hello world", 1000, 4000), line("Stay here", 5000, 8000),
                line("Come back again", 9000, 12000));
        rejected(base, list(word("Hello world", 1000, 4000)), "tiny overlap");
        rejected(base, list(word("Hello world", 1000, 4000), word("Stay here", 5000, 8000)), "partial prefix");
        rejected(base, list(word("Stay here", 5000, 8000), word("Come back again", 9000, 12000)), "partial suffix");
        rejected(base, list(word("Hello world", 1000, 4000), word("Stay there", 5000, 8000),
                word("Come back again", 9000, 12000)), "mostly matching but different verse");
        rejected(base, list(word("Hello world", 1000, 4000), word("Stay here", 5000, 8000),
                word("Come back again", 9000, 12000), word("Encore", 13000, 16000)), "extra candidate verse");
        rejected(base, list(word("Hello world", 1000, 4000), line("Stay here", 5000, 8000),
                line("Come back again", 9000, 12000)), "token amount of karaoke");
        rejected(list(line("a", 1000, 2000), line("bcdefghijk", 3000, 5000)),
                list(word("a", 1000, 2000), line("bcdefghijk", 3000, 5000)), "one tiny timed line");
        accepted(list(line("abcdefghi", 1000, 3000), line("j", 4000, 5000)),
                list(word("abcdefghi", 1000, 3000), line("j", 4000, 5000)), "90 percent timing boundary");
        rejected(list(line("abcdefgh", 1000, 3000), line("ij", 4000, 5000)),
                list(word("abcdefgh", 1000, 3000), line("ij", 4000, 5000)), "below timing coverage boundary");
        rejected(list(line("hello hello", 1000, 4000)), list(word("hello", 1000, 4000)), "repetition count");
        rejected(list(line("hello", 1000, 4000), line("world", 5000, 8000)),
                list(word("world", 1000, 4000), word("hello", 5000, 8000)), "text order matters");
    }

    private static void timing() {
        List<LyricLine> base = list(line("hello", 10000, 14000), line("world", 20000, 24000));
        for (int delta : new int[]{-2000, -100, 0, 100, 2000}) {
            accepted(base, list(word("hello", 10000 + delta, 14000 + delta),
                    word("world", 20000 + delta, 24000 + delta)), "allowed shift " + delta);
        }
        for (int delta : new int[]{-10000, -2001, 2001, 10000}) {
            rejected(base, list(word("hello", 10000 + delta, 14000 + delta),
                    word("world", 20000 + delta, 24000 + delta)), "shifted recording " + delta);
        }
        rejected(base, list(word("hello", 10000, 14000), word("world", 24000, 28000)), "later drift");
        rejected(base, list(timed("hello", 10000, 19000, new int[]{18000}, new int[]{19000}, new int[]{5}),
                word("world", 20000, 24000)), "word onset shifted despite matching line start");
        rejected(list(line("helloworld", 1000, 4000)), list(word("hello", 1000, 3000),
                word("world", 10000, 12000)), "split candidate falls outside baseline window");
        rejected(list(line("helloworld", 1000, 4000)), list(timed("helloworld", 1000, 12000,
                new int[]{1000, 10000}, new int[]{3000, 12000}, new int[]{5, 10})), "interior word drift");
        accepted(list(line("hello", 1000, 4000)), list(word("hello", 1000, 6000)), "word end tolerance boundary");
        rejected(list(line("hello", 1000, 4000)), list(word("hello", 1000, 6001)), "stretched word end");
        rejected(list(line("hello", Integer.MIN_VALUE, Integer.MIN_VALUE + 1000)),
                list(word("hello", Integer.MAX_VALUE - 1000, Integer.MAX_VALUE)), "difference overflow");
    }

    private static void metadata() {
        LyricLine base = new LyricLine("Hello!", "baseline translation", 1000, 4000, false, null, null, null);
        base.bg = word("oh", 2000, 6000);
        LyricLine candidate = word("hello", 1000, 4000);
        List<LyricLine> result = accepted(list(base), list(candidate), "metadata match");
        equal("baseline translation", result.get(0).translation, "translation inherited");
        equal("oh", result.get(0).bg.text, "background inherited");
        check(result.get(0).bg.hasWords(), "background timing inherited");
        equal(6000, result.get(0).end, "inherited background extends parent");
        LyricLine own = new LyricLine("Hello!", "candidate translation", 1000, 4000, false,
                new int[]{1000}, new int[]{4000}, new int[]{6});
        own.bg = word("ah", 2000, 4500);
        result = accepted(list(base), list(own), "candidate metadata wins");
        equal("candidate translation", result.get(0).translation, "own translation wins");
        equal("ah", result.get(0).bg.text, "own background wins");
        check(!result.get(0).opposite, "candidate singer flag retained");
        for (int delta : new int[]{100, 2000}) {
            result = accepted(list(base), list(word("hello", 1000 + delta, 4000 + delta)),
                    "near-start normalized whole-line metadata match " + delta);
            equal("baseline translation", result.get(0).translation, "near-start translation inherited");
            equal("oh", result.get(0).bg.text, "near-start background inherited");
            check(result.get(0).bg.hasWords(), "near-start background timing retained");
        }
        LyricLine otherSinger = new LyricLine("Hello!", null, 1000, 4000, true,
                new int[]{1000}, new int[]{4000}, new int[]{6});
        rejected(list(base), list(otherSinger), "refuse losing secondary text across singers");
        rejected(list(base), list(word("Hel", 1000, 2000), word("lo!", 2000, 4000)),
                "refuse losing baseline translation/background on split lines");
        LyricLine tail = new LyricLine("world", "tail translation", 5000, 8000, false, null, null, null);
        rejected(list(base, tail), list(timed("Hello! world", 1000, 8000,
                new int[]{1000, 5000}, new int[]{4000, 8000}, new int[]{7, 12})),
                "refuse losing baseline translation/background on combined lines");
        LyricLine second = new LyricLine("Hello!", "second occurrence", 5000, 8000, false, null, null, null);
        result = accepted(list(base, second), list(word("hello", 1000, 4000), word("hello", 5000, 8000)),
                "repeated exact lines");
        equal("second occurrence", result.get(1).translation, "repeated text matches occurrence");
    }

    private static void noDowngrade() {
        rejected(list(word("hello", 1000, 4000)), list(line("hello", 1000, 4000)), "no word to line downgrade");
        rejected(list(word("hello", 1000, 4000)), list(word("hello", 1000, 4000)), "don't replace existing words");
        rejected(list(line("hello", 1000, 4000), word("world", 5000, 8000)),
                list(word("hello", 1000, 4000), line("world", 5000, 8000)), "mixed baseline words protected");
        LyricLine base = line("hello", 1000, 4000);
        base.bg = word("oh", 1000, 4000);
        LyricLine candidate = word("hello", 1000, 4000);
        candidate.bg = line("oh", 1000, 4000);
        rejected(list(base), list(candidate), "don't downgrade matched background words");
    }

    private static void invalidInputs() {
        List<LyricLine> base = list(line("hello", 1000, 4000));
        List<LyricLine> candidate = list(word("hello", 1000, 4000));
        rejected(null, candidate, "null baseline");
        rejected(base, null, "null candidate");
        rejected(Collections.<LyricLine>emptyList(), candidate, "empty baseline");
        rejected(base, Collections.<LyricLine>emptyList(), "empty candidate");
        rejected(list((LyricLine) null), candidate, "null baseline line");
        rejected(base, list(null, candidate.get(0)), "null candidate line");
        rejected(list(line(null, 1000, 4000)), candidate, "null text");
        rejected(list(line("...", 1000, 4000)), list(word("!!!", 1000, 4000)), "no real text");
        rejected(base, list(line("hello", 1000, 4000)), "no candidate words");
        rejected(base, list(timed("hello", 1000, 4000, new int[]{1000}, new int[]{4000},
                new int[]{50})), "character end out of bounds");
        rejected(base, list(timed("hello", 1000, 4000, new int[]{1000}, new int[]{4000},
                new int[]{2})), "word arrays don't cover text");
        rejected(base, list(timed("hello", 1000, 4000, new int[]{1000, 2000}, new int[]{2000, 4000},
                new int[]{3, 2})), "character ends decrease");
        rejected(base, list(timed("hello", 1000, 4000, new int[]{2000, 1000}, new int[]{3000, 4000},
                new int[]{2, 5})), "word starts decrease");
        rejected(base, list(timed("hello", 1000, 4000, new int[]{1000}, new int[]{500},
                new int[]{5})), "negative word duration");
        rejected(list(line("hello", 5000, 8000), line("world", 1000, 4000)),
                list(word("hello", 5000, 8000), word("world", 1000, 4000)), "unsorted inputs");
    }

    private static void isolation() {
        LyricLine base = new LyricLine("hello", "translation", 1000, 4000, false, null, null, null);
        base.bg = word("oh", 1000, 6000);
        LyricLine candidate = word("hello", 1000, 4000);
        List<LyricLine> result = accepted(Collections.unmodifiableList(list(base)),
                Collections.unmodifiableList(list(candidate)), "read-only input lists");
        check(result.get(0) != candidate && result.get(0) != base, "fresh lead line");
        check(result.get(0).bg != base.bg, "fresh inherited background");
        check(result.get(0).sylStart != candidate.sylStart, "fresh word start array");
        check(result.get(0).sylEnd != candidate.sylEnd, "fresh word end array");
        check(result.get(0).charEnd != candidate.charEnd, "fresh character array");
        equal(4000, candidate.end, "merge doesn't extend input");
        check(candidate.bg == null && candidate.translation == null, "merge doesn't annotate input");
        result.get(0).sylStart[0] = 42;
        result.get(0).bg.sylEnd[0] = 42;
        result.get(0).end = 42;
        result.clear();
        equal(1000, candidate.sylStart[0], "result mutation doesn't change candidate");
        equal(6000, base.bg.sylEnd[0], "result mutation doesn't change baseline");
        accepted(list(base), list(candidate), "repeat merge remains valid");
        candidate.bg = word("own", 1000, 4000);
        result = accepted(list(base), list(candidate), "candidate background copy");
        check(result.get(0).bg != candidate.bg && result.get(0).bg.charEnd != candidate.bg.charEnd,
                "candidate background deep copied");
    }

    private static LyricLine line(String text, int start, int end) {
        return new LyricLine(text, null, start, end, false, null, null, null);
    }

    private static LyricLine word(String text, int start, int end) {
        return timed(text, start, end, new int[]{start}, new int[]{end}, new int[]{text.length()});
    }

    private static LyricLine timed(String text, int start, int end, int[] starts, int[] ends, int[] chars) {
        return new LyricLine(text, null, start, end, false, starts, ends, chars);
    }

    private static List<LyricLine> list(LyricLine... lines) { return Arrays.asList(lines); }

    private static List<LyricLine> accepted(List<LyricLine> base, List<LyricLine> candidate, String message) {
        List<LyricLine> result = LyricKaraokeUpgrade.merge(base, candidate);
        check(result != null, message);
        equal(candidate.size(), result.size(), message + " retains candidate segmentation");
        check(LyricKaraokeUpgrade.words(result), message + " retains words");
        return result;
    }

    private static void rejected(List<LyricLine> base, List<LyricLine> candidate, String message) {
        check(LyricKaraokeUpgrade.merge(base, candidate) == null, message);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(expected == null ? actual == null : expected.equals(actual), message);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
