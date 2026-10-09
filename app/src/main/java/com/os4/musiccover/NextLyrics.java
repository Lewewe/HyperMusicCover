package com.os4.musiccover;

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The small, queue-shaped lyric working set: previous/current/next results and the request for
 * the item following the current one.  Artwork prediction owns the queue itself; this class owns
 * only lyric reuse and preparation from that queue data.
 */
final class NextLyrics {
    private NextLyrics() { }

    private static final String TAG = "[MCPre] ";
    private static final int READY_MAX = 3;

    private static volatile long sConfigurationRevision;

    static final class Context {
        final String configuration;
        final long revision;
        Context(String configuration, long revision) {
            this.configuration = configuration;
            this.revision = revision;
        }
    }

    static String configurationKey() {
        // v3 keeps queue entries made before per-line target-language filtering from restoring
        // stale online translations (notably English-to-English rows) into the renderer.
        return LyricCacheScope.digest("lyric-settings-v3",
                String.valueOf(LockLyrics.sSearchMode),
                String.valueOf(LockLyrics.sProviderQq), String.valueOf(LockLyrics.sProviderNetease),
                String.valueOf(LockLyrics.sProviderKuwo), String.valueOf(LockLyrics.sProviderKugou),
                String.valueOf(LockLyrics.sProviderLrcLib), String.valueOf(LockLyrics.sProviderVariants),
                String.valueOf(LockLyrics.sSpicyLyricsEnabled), LockLyrics.sSpicyLyricsApiKey,
                LockLyrics.sTranslateProvider, LockLyrics.sTranslateEndpoint,
                LockLyrics.sTranslateSourceLang, LockLyrics.sTranslateTargetLang,
                String.valueOf(LockLyrics.sOnlineTranslateMode), LockLyrics.sTranslateApiKey);
    }

    static Context context() {
        synchronized (READY) {
            return new Context(configurationKey(), sConfigurationRevision);
        }
    }

    static boolean isCurrent(Context context) {
        return context != null && LyricCacheScope.current(context.revision, context.configuration,
                sConfigurationRevision, configurationKey());
    }

    /** Forget obsolete RAM results, leaving separately scoped disk entries available for reuse. */
    static void configurationChanged() {
        List<LyricsCallback> waiters = new ArrayList<>();
        synchronized (READY) {
            sConfigurationRevision++;
            READY.clear();
            LOADING.clear();
            for (List<LyricsCallback> callbacks : WAITERS.values()) waiters.addAll(callbacks);
            WAITERS.clear();
        }
        for (LyricsCallback waiter : waiters) waiter.onReady(null);
        warmAhead();
    }

    /** Complete provider result for one of previous/current/next, held only in RAM. */
    static final class Lyrics {
        final String pkg, mediaId, title, artist;
        final List<LyricLine> lines;
        final int source;
        final boolean translated;
        final Context context;

        Lyrics(String pkg, Prefetch.Item item, List<LyricLine> lines, int source,
               boolean translated, Context context) {
            this.pkg = pkg;
            this.mediaId = item.mediaId;
            this.title = item.title;
            this.artist = item.artist;
            this.lines = lines;
            this.source = source;
            this.translated = translated;
            this.context = context;
        }

        Lyrics(String pkg, String mediaId, List<LyricLine> lines, int source,
               boolean translated, Context context) {
            this.pkg = pkg;
            this.mediaId = mediaId;
            this.title = null;
            this.artist = null;
            this.lines = lines;
            this.source = source;
            this.translated = translated;
            this.context = context;
        }

        boolean matches(MediaController controller) {
            if (controller == null || !pkg.equals(controller.getPackageName())) return false;
            MediaMetadata metadata = controller.getMetadata();
            if (metadata == null) return false;
            String id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (mediaId != null && id != null) return mediaId.equals(id);
            return same(title, metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
                    && same(artist, metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
        }
    }

    interface LyricsCallback {
        void onReady(Lyrics lyrics);
    }

    /** Three queue positions, not an offline library cache. Completed misses are retained too. */
    private static final Map<String, Lyrics> READY =
            new LinkedHashMap<String, Lyrics>(READY_MAX + 1, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Lyrics> entry) {
                    return size() > READY_MAX;
                }
            };
    private static final java.util.HashSet<String> LOADING = new java.util.HashSet<>();
    private static final Map<String, List<LyricsCallback>> WAITERS = new java.util.HashMap<>();

    /** Latest queue snapshot, used when a live lookup completes and asks to warm the next item. */
    private static volatile String sPkg;
    private static volatile List<Prefetch.Item> sItems = new ArrayList<>();
    private static volatile int sIndex = -1;
    private static Handler sWork;

    /** Records the queue snapshot without doing I/O; Prefetch chooses when warming is safe. */
    static void setQueue(String pkg, List<Prefetch.Item> items, int index) {
        sPkg = pkg;
        sItems = items;
        sIndex = index;
    }

    /** Starts the one-ahead request for the supplied, already reconciled queue position. */
    static void warm(String pkg, List<Prefetch.Item> items, int index) {
        setQueue(pkg, items, index);
        warmAhead();
    }

    private static void warmAhead() {
        String pkg = sPkg;
        List<Prefetch.Item> items = sItems;
        int at = sIndex;
        if (pkg == null || items.isEmpty() || at < 0 || at + 1 >= items.size()) return;
        final Prefetch.Item item = items.get(at + 1);
        if (item.title == null || item.title.isEmpty() || item.artist == null
                || item.artist.isEmpty()) return;
        final Context context = context();
        final String key = key(pkg, item, context.configuration);
        final String request = request(key, context);
        synchronized (READY) {
            if (READY.containsKey(key) || !LOADING.add(request)) return;
        }
        lyricWork().post(new Runnable() {
            @Override public void run() {
                try {
                    if (!isCurrent(context)) return;
                    QueuedLyricCache.Entry cached = QueuedLyricCache.read(pkg, item.mediaId, context.configuration);
                    if (cached != null) {
                        LocalRomanizer.apply(cached.lines);
                        complete(key, request, context, new Lyrics(pkg, item, cached.lines, cached.source,
                                cached.translated, context));
                        Xp.log(TAG + "restored ready lyrics for \"" + item.title + "\": "
                                + cached.lines.size() + " lines");
                        return;
                    }
                    Xp.log(TAG + "preparing lyrics for \"" + item.title + "\"");
                    LyricSource.loadAhead(pkg, item.title, item.artist, item.album, item.durationMs,
                            item.mediaId, new LyricSource.AheadCallback() {
                                @Override public void onLines(final List<LyricLine> lines,
                                                              String why, final int source) {
                                    if (!isCurrent(context)) return;
                                    LocalRomanizer.apply(lines);
                                    LockLyrics.translateAhead(key, lines, context,
                                            new LockLyrics.AheadTranslationCallback() {
                                                @Override public void onReady(List<LyricLine> merged,
                                                                              boolean translated) {
                                                    if (!isCurrent(context)) return;
                                                    List<LyricLine> ready = merged == null
                                                            ? java.util.Collections.<LyricLine>emptyList()
                                                            : merged;
                                                    QueuedLyricCache.write(pkg, item.mediaId, ready,
                                                            source, translated, context.configuration);
                                                    complete(key, request, context, new Lyrics(pkg, item, ready, source,
                                                            translated, context));
                                                    Xp.log(TAG + "prepared \"" + item.title + "\": "
                                                            + (merged == null ? 0 : merged.size())
                                                            + " lines (" + why + ")");
                                                }
                                            });
                                }
                            });
                } catch (Throwable t) {
                    complete(key, request, context, null);
                    Xp.w(TAG + "reading ahead failed: " + t);
                }
            }
        });
    }

    static Lyrics take(MediaController controller) {
        if (controller == null) return null;
        synchronized (READY) {
            for (Lyrics lyrics : READY.values()) {
                if (isCurrent(lyrics.context) && !lyrics.lines.isEmpty() && lyrics.matches(controller)) return lyrics;
            }
        }
        return null;
    }

    static boolean await(MediaController controller, LyricsCallback callback) {
        if (controller == null || callback == null) return false;
        Prefetch.Item item = itemOf(controller);
        if (item == null) return false;
        Context context = context();
        String key = request(key(controller.getPackageName(), item, context.configuration), context);
        synchronized (READY) {
            if (!LOADING.contains(key)) return false;
            List<LyricsCallback> waiters = WAITERS.get(key);
            if (waiters == null) {
                waiters = new ArrayList<>();
                WAITERS.put(key, waiters);
            }
            waiters.add(callback);
            return true;
        }
    }

    /** The live pipeline ended; its successor may now be prepared from the latest queue. */
    static void onLyricsFinal() {
        warmAhead();
    }

    static void persistReady(String pkg, String mediaId, List<LyricLine> lines, int source,
                             boolean translated, Context context) {
        if (!isCurrent(context)) return;
        if (pkg == null || pkg.isEmpty() || mediaId == null || mediaId.isEmpty()
                || lines == null || lines.isEmpty()) return;
        QueuedLyricCache.write(pkg, mediaId, lines, source, translated, context.configuration);
        String key = LyricCacheScope.track(context.configuration, pkg, mediaId);
        synchronized (READY) {
            if (!isCurrent(context)) return;
            Lyrics old = READY.get(key);
            if (old == null || !LyricSource.words(old.lines) || LyricSource.words(lines)) {
                READY.put(key, new Lyrics(pkg, mediaId, lines, source, translated, context));
            }
        }
    }

    static String describe() {
        int ready;
        int readyLines = 0;
        synchronized (READY) {
            ready = READY.size();
            for (Lyrics lyric : READY.values()) if (!lyric.lines.isEmpty()) readyLines++;
        }
        return "lyricReady=" + ready + "(" + readyLines + " lyrics) "
                + NcmLyrics.describeSearches();
    }

    /** Runs the dictionary on the lyric worker, never on SystemUI's main thread. */
    static void romanizeAsync(final List<LyricLine> lines, final Runnable onReady) {
        if (!LocalRomanizer.needsApply(lines)) {
            onReady.run();
            return;
        }
        lyricWork().post(new Runnable() {
            @Override public void run() {
                LocalRomanizer.apply(lines);
                Main.main().post(onReady);
            }
        });
    }

    static String describeTriplet(String pkg, Prefetch.Item previous, Prefetch.Item current,
                                  Prefetch.Item next) {
        return "previous=" + describeItem(pkg, previous)
                + "\ncurrent=" + describeItem(pkg, current)
                + "\nnext=" + describeItem(pkg, next);
    }

    private static String describeItem(String pkg, Prefetch.Item item) {
        if (item == null) return "none";
        Lyrics ready;
        boolean loading;
        Context context = context();
        synchronized (READY) {
            String key = key(pkg, item, context.configuration);
            ready = READY.get(key);
            loading = LOADING.contains(request(key, context));
        }
        String state;
        if (ready != null && !ready.lines.isEmpty()) {
            state = "ready=" + ready.lines.size() + " src=" + LockLyrics.srcName(ready.source)
                    + (ready.translated ? " +translation" : "");
        } else if (loading) state = "prefetching";
        else if (ready != null) state = "prefetch-miss";
        else state = "none";
        if ("none".equals(state)) {
            return "\"" + item.title + "\" — " + item.artist + " id=" + item.mediaId;
        }
        return "\"" + item.title + "\" by " + item.artist + " id=" + item.mediaId
                + " " + state;
    }

    private static void complete(String key, String request, Context context, Lyrics lyrics) {
        List<LyricsCallback> waiters;
        synchronized (READY) {
            LOADING.remove(request);
            waiters = WAITERS.remove(request);
            if (!isCurrent(context)) return;
            if (lyrics != null) READY.put(key, lyrics);
        }
        if (waiters != null) for (LyricsCallback waiter : waiters) waiter.onReady(lyrics);
    }

    private static String key(String pkg, Prefetch.Item item, String configuration) {
        String identity = item.mediaId != null && !item.mediaId.isEmpty() ? item.mediaId
                : LyricCacheScope.digest(item.title, item.artist);
        return LyricCacheScope.track(configuration, pkg, identity);
    }

    private static String request(String key, Context context) {
        return context.revision + "|" + key;
    }

    private static Prefetch.Item itemOf(MediaController controller) {
        MediaMetadata metadata = controller.getMetadata();
        if (metadata == null) return null;
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null || title.isEmpty()) return null;
        return new Prefetch.Item(-1, title, null,
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
    }

    private static boolean same(String a, String b) {
        return a != null && a.equals(b);
    }

    private static synchronized Handler lyricWork() {
        if (sWork == null) {
            HandlerThread thread = new HandlerThread("mc-lyricahead",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            sWork = new Handler(thread.getLooper());
        }
        return sWork;
    }
}
