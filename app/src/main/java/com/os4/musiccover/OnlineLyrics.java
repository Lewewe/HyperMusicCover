package com.os4.musiccover;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/**
 * The by-name catalogues, in the order HyperLyrics Enhanced asks them, and the one answer taken.
 *
 * Its order is by player: a NetEase player's song is looked for on NetEase first and a QQ
 * Music player's on QQ first, because a player's own catalogue is the one whose entry is that
 * recording; everything else starts with QQ, the bigger catalogue, then NetEase. HyperLyrics
 * Enhanced stops there. This goes on to Kuwo, KuGou and LrcLib, one after another, for a song
 * neither of the two had - with KuGou and Kuwo moved to the front for their own players.
 *
 * The normal lookup uses a bounded parallel search over every provider and every safe title/artist
 * variant. A line-timed answer is retained as a fallback, but is not published until all of the
 * word-timed candidates have had their chance.
 */
final class OnlineLyrics {

    private OnlineLyrics() {
    }

    enum Src { QQ, NETEASE, KUWO, KUGOU, LRCLIB }

    /** One catalogue's answer, in the shape LyricSource parses. */
    static final class Found {
        final Src src;
        final String id;
        final String body;
        final String translation;
        final String roma;
        final boolean words;

        Found(Src src, String id, String body, String translation, String roma, boolean words) {
            this.src = src;
            this.id = id;
            this.body = body;
            this.translation = translation;
            this.roma = roma;
            this.words = words;
        }

        int source() {
            switch (src) {
                case QQ:
                    return LyricSource.SRC_QQ;
                case NETEASE:
                    return LyricSource.SRC_NETEASE;
                case KUWO:
                    return LyricSource.SRC_KUWO;
                case KUGOU:
                    return LyricSource.SRC_KUGOU;
                default:
                    return LyricSource.SRC_LRCLIB;
            }
        }

        String who() {
            switch (src) {
                case QQ:
                    return "QQ Music";
                case NETEASE:
                    return "NetEase";
                case KUWO:
                    return "Kuwo";
                case KUGOU:
                    return "KuGou";
                default:
                    return "LrcLib";
            }
        }
    }

    /** The order for a player; it also determines which equally good result wins. */
    static List<Src> order(String pkg) {
        if ("com.netease.cloudmusic".equals(pkg)) {
            return Arrays.asList(Src.NETEASE, Src.QQ, Src.KUWO, Src.KUGOU, Src.LRCLIB);
        }

        if ("com.kugou.android".equals(pkg) || "com.kugou.android.lite".equals(pkg)) {
            return Arrays.asList(Src.KUGOU, Src.QQ, Src.NETEASE, Src.KUWO, Src.LRCLIB);
        }
        if ("cn.kuwo.player".equals(pkg)) {
            return Arrays.asList(Src.KUWO, Src.QQ, Src.NETEASE, Src.KUGOU, Src.LRCLIB);
        }
        // QQ Music's own and everyone else's: QQ, then NetEase.
        return Arrays.asList(Src.QQ, Src.NETEASE, Src.KUWO, Src.KUGOU, Src.LRCLIB);
    }

    static boolean enabled(Src src) {
        switch (src) {
            case QQ: return LockLyrics.sProviderQq;
            case NETEASE: return LockLyrics.sProviderNetease;
            case KUWO: return LockLyrics.sProviderKuwo;
            case KUGOU: return LockLyrics.sProviderKugou;
            case LRCLIB: return LockLyrics.sProviderLrcLib;
            default: return true;
        }
    }

    /** The whole lookup's budget, the same as the race it runs in. */
    private static final long BUDGET_MS = 8000L;
    private static final int SEARCH_WORKERS = 5;

    static final class Lookup {
        final Found found;
        final List<LyricLine> lines;

        Lookup(Found found, List<LyricLine> lines) {
            this.found = found;
            this.lines = lines;
        }
    }

    /**
     * Search every provider with every bounded name variant before settling for line timing.
     *
     * Requests are parallelised, but the result is selected by provider/variant order rather
     * than completion order. This keeps the common path fast without allowing a quick line-timed
     * response to prevent a slower word-timed catalogue from being considered.
     */
    static Lookup wordFirst(String pkg, final NcmLyrics.Query q) {
        if (q == null) return null;
        final List<Src> catalogues = new ArrayList<>();
        for (Src src : order(pkg)) if (enabled(src)) catalogues.add(src);
        if (catalogues.isEmpty()) return null;
        final List<NcmLyrics.Query> queries = LockLyrics.sProviderVariants
                ? q.aliases() : java.util.Collections.singletonList(q);
        final int count = catalogues.size() * queries.size();
        final Lookup[] results = new Lookup[count];
        final AtomicInteger next = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger(SEARCH_WORKERS);
        final BlockingQueue<Integer> done = new LinkedBlockingQueue<>();
        final long deadline = android.os.SystemClock.uptimeMillis() + BUDGET_MS;
        Thread[] workers = new Thread[SEARCH_WORKERS];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (android.os.SystemClock.uptimeMillis() < deadline) {
                            int slot = next.getAndIncrement();
                            if (slot >= count) return;
                            Found found = ask(catalogues.get(slot % catalogues.size()),
                                    queries.get(slot / catalogues.size()));
                            if (found == null) continue;
                            List<LyricLine> lines = LyricParse.parse(
                                    found.body, found.translation, found.roma);
                            if (!lines.isEmpty() && !placeholderLines(lines)) {
                                results[slot] = new Lookup(found, lines);
                            }
                        }
                    } finally {
                        done.offer(1);
                        running.decrementAndGet();
                    }
                }
            }, "MCLyricWordSearch" + i);
            workers[i].start();
        }
        while (running.get() > 0 && android.os.SystemClock.uptimeMillis() < deadline) {
            try {
                done.poll(Math.min(100L,
                        deadline - android.os.SystemClock.uptimeMillis()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Thread worker : workers) worker.interrupt();

        Lookup lineFallback = null;
        for (Lookup result : results) {
            if (result == null) continue;
            if (timedLeadOrBacking(result.lines)) return result;
            if (lineFallback == null) lineFallback = result;
        }
        return lineFallback;
    }

    private static boolean placeholderLines(List<LyricLine> lines) {
        boolean any = false;
        for (LyricLine line : lines) {
            String text = line.text == null ? "" : line.text.trim().toLowerCase(java.util.Locale.ROOT);
            if (text.isEmpty() || text.matches("^[^:：]{1,16}[:：].*")) continue;
            if (!text.contains("纯音乐") && !text.contains("请欣赏")
                    && !text.contains("instrumental") && !text.contains("no lyrics")) return false;
            any = true;
        }
        return any;
    }

    /** Legacy fast lookup retained for diagnostics and compatibility. */
    static Found first(final String pkg, final NcmLyrics.Query q) {
        final List<Src> order = order(pkg);
        final Found[] got = new Found[2];
        final BlockingQueue<Integer> done = new LinkedBlockingQueue<>();
        for (int i = 0; i < 2; i++) {
            final int slot = i;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        got[slot] = ask(order.get(slot), q);
                    } finally {
                        done.offer(slot);
                    }
                }
            }, "MCLyric" + order.get(i)).start();
        }
        long deadline = android.os.SystemClock.uptimeMillis() + BUDGET_MS;
        boolean[] finished = new boolean[2];
        for (int n = 0; n < 2; n++) {
            long left = deadline - android.os.SystemClock.uptimeMillis();
            if (left <= 0) break;
            Integer slot;
            try {
                slot = done.poll(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (slot == null) break;
            finished[slot] = true;
            // The first in the order decides as soon as it has answered; the second only once
            // the first has answered with nothing.
            if (finished[0] && got[0] != null) return got[0];
            if (finished[0] && finished[1]) break;
        }
        // Out of time for the first, or both done: whichever has something.
        return got[0] != null ? got[0] : got[1];
    }

    /** Legacy sequential lookup retained for diagnostics and compatibility. */
    static Found rest(String pkg, NcmLyrics.Query q) {
        List<Src> order = order(pkg);
        for (int i = 2; i < order.size(); i++) {
            Found f = ask(order.get(i), q);
            if (f != null) return f;
        }
        return null;
    }

    static final class Upgrade {
        final Found found;
        final List<LyricLine> lines;

        Upgrade(Found found, List<LyricLine> lines) {
            this.found = found;
            this.lines = lines;
        }
    }

    private static final int MAX_UPGRADE_REQUESTS = 18;

    /**
     * A separate, bounded quality pass: the original answer stays visible while two workers
     * try catalogue/name alternatives. Aliases are only hints; parsed text and timing must
     * agree with the established answer before its word timings can replace that answer.
     */
    static Upgrade upgrade(String pkg, NcmLyrics.Query q, final List<LyricLine> baseline,
                           final BooleanSupplier current) {
        if (q == null || LyricKaraokeUpgrade.words(baseline)
                || !current.getAsBoolean()) return null;
        final boolean needMatch = baseline != null && !baseline.isEmpty();
        final List<Src> sources = new ArrayList<>();
        final List<NcmLyrics.Query> queries = new ArrayList<>();
        List<Src> catalogues = order(pkg);
        // Give every credited artist a turn on the primary two catalogues before spending
        // requests on lower-priority providers. A long credit list must not crowd out its guests.
        for (NcmLyrics.Query alias : q.aliases()) {
            for (int i = 0; i < 2 && sources.size() < MAX_UPGRADE_REQUESTS; i++) {
                sources.add(catalogues.get(i));
                queries.add(alias);
            }
        }
        for (int i = 2; i < catalogues.size() && sources.size() < MAX_UPGRADE_REQUESTS; i++) {
            if (catalogues.get(i) == Src.LRCLIB) continue;
            sources.add(catalogues.get(i));
            queries.add(q);
        }
        final long deadline = android.os.SystemClock.uptimeMillis() + BUDGET_MS;
        final AtomicInteger next = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger(2);
        final BlockingQueue<Upgrade> answers = new LinkedBlockingQueue<>();
        Thread[] workers = new Thread[2];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (!Thread.currentThread().isInterrupted() && current.getAsBoolean()
                                && android.os.SystemClock.uptimeMillis() < deadline) {
                            int slot = next.getAndIncrement();
                            if (slot >= sources.size()) return;
                            Found found = ask(sources.get(slot), queries.get(slot));
                            if (found == null || !current.getAsBoolean()
                                    || android.os.SystemClock.uptimeMillis() >= deadline) continue;
                            List<LyricLine> parsed = LyricParse.parse(
                                    found.body, found.translation, found.roma);
                            if (parsed.isEmpty() || LyricSource.placeholderLines(parsed)) continue;
                            List<LyricLine> merged = needMatch
                                    ? LyricKaraokeUpgrade.merge(baseline, parsed) : parsed;
                            if (merged != null) {
                                answers.offer(new Upgrade(found, merged));
                                if (needMatch) return;
                            }
                        }
                    } catch (Throwable t) {
                        Xp.log("[MCLyric] karaoke quality lookup failed: " + t);
                    } finally {
                        running.decrementAndGet();
                    }
                }
            }, "MCLyricKaraoke" + i);
            workers[i].start();
        }
        Upgrade fallback = null;
        try {
            while (current.getAsBoolean()) {
                long left = deadline - android.os.SystemClock.uptimeMillis();
                if (left <= 0) break;
                Upgrade answer = answers.poll(Math.min(left, 100L), TimeUnit.MILLISECONDS);
                if (answer != null) {
                    if (!current.getAsBoolean()) return null;
                    if (needMatch) return answer;
                    if (timedLeadOrBacking(answer.lines)) {
                        // With no established lyric the catalogue's metadata match is the proof.
                        // Once a line-timed fallback exists, also require its content to agree.
                        List<LyricLine> merged = fallback == null ? answer.lines
                                : LyricKaraokeUpgrade.merge(fallback.lines, answer.lines);
                        if (merged != null) return new Upgrade(answer.found, merged);
                    } else if (fallback == null) {
                        fallback = answer;
                    }
                    continue;
                }
                if (running.get() == 0) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Provider HTTP timeouts bound an in-flight call; interruption also stops retries.
            for (Thread worker : workers) worker.interrupt();
        }
        return current.getAsBoolean() ? fallback : null;
    }

    private static boolean timedLeadOrBacking(List<LyricLine> lines) {
        if (lines == null) return false;
        for (LyricLine line : lines) {
            if (line != null && (line.hasWords() || (line.bg != null && line.bg.hasWords()))) {
                return true;
            }
        }
        return false;
    }

    /** Where a catalogue's answer is said to be from, as LyricSource numbers its sources. */
    static int sourceOf(Src src) {
        return new Found(src, null, null, null, null, false).source();
    }

    /** One catalogue's answer for the song, or null. */
    static Found ask(Src src, NcmLyrics.Query q) {
        String cacheKey = src.name() + "|" + q.key();
        JSONObject cached = LyricDiskCache.read("providers", cacheKey);
        Found cachedFound = fromCache(src, cached);
        if (cachedFound != null) return cachedFound;
        try {
            Found found;
            switch (src) {
                case QQ: {
                    QqLyrics.Found f = QqLyrics.load(q);
                    found = f == null ? null
                            : new Found(src, f.id, f.body, f.translation, f.roma, f.words);
                    break;
                }
                case NETEASE: {
                    NcmLyrics.Found f = NcmLyrics.load(q);
                    found = f == null ? null
                            : new Found(src, f.id, f.body, f.translation, f.roma, f.words);
                    break;
                }
                case KUWO: {
                    KuwoLyrics.Found f = KuwoLyrics.load(q);
                    found = f == null ? null
                            : new Found(src, f.id, f.body, f.translation, f.roma, f.words);
                    break;
                }
                case KUGOU:
                case LRCLIB: {
                    // A KRC carries its translation and romanisation inside the body.
                    WebLyrics.Found f = WebLyrics.load(q, src == Src.KUGOU, src == Src.LRCLIB);
                    found = f == null ? null : new Found(src, f.id, f.body, null, null, f.words);
                    break;
                }
                default:
                    return null;
            }
            if (found != null && found.body != null && !found.body.isEmpty()) {
                JSONObject value = new JSONObject();
                value.put("id", found.id);
                value.put("body", found.body);
                value.put("translation", found.translation);
                value.put("roma", found.roma);
                value.put("words", found.words);
                LyricDiskCache.write("providers", cacheKey, value);
            }
            return found;
        } catch (Throwable t) {
            Xp.log("[MCLyric] " + src + " failed: " + t);
            return null;
        }
    }

    private static Found fromCache(Src src, JSONObject value) {
        if (value == null) return null;
        String body = value.optString("body", "");
        if (body.isEmpty()) return null;
        return new Found(src, value.optString("id", null), body,
                value.optString("translation", null), value.optString("roma", null),
                value.optBoolean("words", false));
    }

    /** For op ncm: the order, and what QQ Music and Kuwo make of the song. */
    static String probe(String pkg, NcmLyrics.Query q) {
        StringBuilder sb = new StringBuilder("\n  order: ").append(describe(pkg));
        if (q == null) return sb.toString();
        for (Src s : new Src[]{Src.QQ, Src.KUWO}) {
            Found f = ask(s, q);
            sb.append("\n  ").append(s == Src.QQ ? "QQ Music" : "Kuwo").append(": ");
            if (f == null) {
                sb.append("no match");
                continue;
            }
            List<LyricLine> lines = LyricParse.parse(f.body, f.translation, f.roma);
            int tr = 0;
            for (LyricLine l : lines) if (l.translation != null) tr++;
            sb.append(f.id).append(' ').append(f.words ? "word-timed" : "line-timed").append(' ')
                    .append(lines.size()).append(" lines, ").append(tr).append(" with a translation")
                    .append(f.roma != null ? " (romanised)" : "");
        }
        return sb.toString();
    }

    /** For probes: the order a player's songs are looked for in. */
    static String describe(String pkg) {
        List<String> names = new ArrayList<>();
        for (Src s : order(pkg)) names.add(s.name().toLowerCase(java.util.Locale.ROOT));
        return String.join(">", names);
    }
}
