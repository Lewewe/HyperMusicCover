package com.os4.musiccover;

import android.annotation.SuppressLint;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.Bundle;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The cover of the track that has not been asked for yet.
 *
 * Measured on this phone: from a press landing to the player saying a track changed is 713-972ms,
 * and everything the cover does after that is ~110ms. So the swap is not slow - it starts late,
 * and no amount of work on the second number touches the first.
 *
 * A player that publishes a play QUEUE says what is coming before it is asked, which is what this
 * uses: the artwork either side of the current item is fetched and kept, and the press itself -
 * caught on TransportControls, ~0.8s before the player reports anything - hands the right one
 * straight to the wallpaper.
 *
 * Only Apple Music was originally observed publishing one here. Spotify can publish one too, but
 * its active queue id can lag behind playback, so its current item is matched against metadata
 * instead. Players without a queue still wait for their normal metadata update.
 *
 * Guessing is the whole idea, so being wrong has to be cheap: the prediction is only ever an
 * early push of a picture, and the player's own metadata arrives a moment later and is what
 * settles it. See Main.onMediaUpdate(), which pushes again whenever the two disagree.
 */
final class Prefetch {

    private Prefetch() {
    }

    private static final String TAG = "[MCPre] ";

    /**
     * How many items either side of the current one are worth holding.
     *
     * Two, not one, because pressing next twice quickly is the case this exists for: the second
     * press predicts from where the first one landed, and one item of reach would already be
     * behind it.
     */
    private static final int REACH = 2;
    /** Artwork is ~1MB decoded; this is a handful of tracks, not a library. */
    private static final int CACHE_MAX = 6;
    private static final int LYRIC_READY_MAX = 3;

    /** One queue item, reduced to what a cover and a lyric lookup need. */
    private static final class Item {
        final long id;
        final String title;
        final Uri icon;
        /** The platform's own song id, which is what the lyric database is keyed by. */
        final String mediaId;
        /**
         * The other half of a lyric search. Only the artist, because the search takes the song's
         * name and its first artist and nothing else - the album and the duration that the rest
         * of a by-name lookup wants are not needed until the choosing, which happens later and
         * elsewhere. See NcmLyrics.terms().
         */
        final String artist;
        /** Optional queue metadata; present on some players before the track becomes current. */
        final String album;
        final long durationMs;

        Item(long id, String title, Uri icon, String mediaId, String artist) {
            this(id, title, icon, mediaId, artist, null, 0L);
        }

        Item(long id, String title, Uri icon, String mediaId, String artist, String album,
             long durationMs) {
            this.id = id;
            this.title = title;
            this.icon = icon;
            this.mediaId = mediaId;
            this.artist = artist;
            this.album = album;
            this.durationMs = durationMs;
        }

        /** The same track: by the platform id where there is one, else by title. */
        boolean sameTrack(Item o) {
            if (o == null) return false;
            if (mediaId != null && o.mediaId != null) return mediaId.equals(o.mediaId);
            return title != null && title.equals(o.title);
        }
    }

    /** Complete provider result for one of previous/current/next, held only in RAM. */
    static final class Lyrics {
        final String pkg, mediaId, title, artist;
        final List<LyricLine> lines;
        final int source;
        final boolean translated;
        Lyrics(String pkg, Item item, List<LyricLine> lines, int source, boolean translated) {
            this.pkg = pkg;
            this.mediaId = item.mediaId;
            this.title = item.title;
            this.artist = item.artist;
            this.lines = lines;
            this.source = source;
            this.translated = translated;
        }

        Lyrics(String pkg, String mediaId, List<LyricLine> lines, int source,
               boolean translated) {
            this.pkg = pkg;
            this.mediaId = mediaId;
            this.title = null;
            this.artist = null;
            this.lines = lines;
            this.source = source;
            this.translated = translated;
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

    /** Three queue positions, not an offline library cache. Completed misses are retained too. */
    private static final Map<String, Lyrics> LYRIC_READY =
            new LinkedHashMap<String, Lyrics>(LYRIC_READY_MAX + 1, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Lyrics> e) {
                    return size() > LYRIC_READY_MAX;
                }
            };
    private static final java.util.HashSet<String> LYRIC_LOADING = new java.util.HashSet<>();
    private static final Map<String, List<LyricsCallback>> LYRIC_WAITERS = new java.util.HashMap<>();

    interface LyricsCallback {
        void onReady(Lyrics lyrics);
    }

    /**
     * The tracks played before this one, most recent first.
     *
     * Kept here because Apple Music's queue does not hold them: it always publishes the current
     * item at index 0 and what comes after (measured 2026-09-23 with `op queue`: "active id=0
     * (index 0)"), so a skip back found nothing before it and waited the whole ~0.8s for the
     * player, where a skip on was answered at once.
     */
    private static final java.util.ArrayList<Item> sHistory = new java.util.ArrayList<>();
    private static final int HISTORY_MAX = 4;
    /** The item that was current at the last queue read, to notice it changing. */
    private static Item sCurrent;
    /** How many skips back have been predicted from sHistory since the last queue read. */
    private static int sBackDepth;
    /** The session's last playback state, for where in the track a skip back is pressed. */
    private static volatile PlaybackState sState;

    /**
     * How far into a track a skip back only restarts it. media3's default
     * (maxSeekToPreviousPositionMs), which Apple Music is built on. Past it the cover is not
     * going to change, so nothing is predicted rather than flashing the previous album.
     */
    private static final long RESTART_MS = 3000L;

    /** Which player the queue belongs to; the lyric half is only run for one of them. */
    private static volatile String sPkg;

    private static volatile List<Item> sItems = new ArrayList<>();
    /** Where the player says it is in that list, or -1 when it does not say. */
    private static volatile int sIndex = -1;

    /** Decoded artwork, keyed on the URI it came from. Guarded by CACHE. */
    private static final java.util.LinkedHashMap<String, Bitmap> CACHE =
            new java.util.LinkedHashMap<>(8, 0.75f, true);

    private static Handler sWork;

    /**
     * The title this predicted for the press that has not been confirmed yet, and when it was
     * predicted. Read by Main to decide whether the player's own report needs another push.
     */
    private static volatile String sPredicted;
    private static volatile String sPredictedArtist;
    private static volatile long sPredictedAt;

    /** A prediction older than this is not worth matching against - the player never got there. */
    private static final long PREDICTION_TTL_MS = 4000L;
    /** Spotify may publish queue and metadata on adjacent callbacks during a skip or shuffle. */
    private static final int SPOTIFY_QUEUE_RETRIES = 2;
    private static final long SPOTIFY_QUEUE_RETRY_MS = 300L;
    private static Runnable sSpotifyQueueRetry;
    private static int sSpotifyQueueRetryCount;

    private static synchronized Handler work() {
        if (sWork == null) {
            HandlerThread t = new HandlerThread("mc-prefetch",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            t.start();
            sWork = new Handler(t.getLooper());
        }
        return sWork;
    }

    // ------------------------------------------------------------------ from Main

    /**
     * The player reported something: re-read its queue and fetch what sits either side of the
     * current track. Cheap when nothing moved - the fetches are keyed on the artwork URI and a
     * cached one does not go out again.
     */
    static void onTrack(final MediaController c) {
        if (c == null) {
            sItems = new ArrayList<>();
            sIndex = -1;
            return;
        }
        work().post(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean coherent = readQueue(c);
                    if (coherent) cancelSpotifyQueueRetry();
                    else scheduleSpotifyQueueRetry(c);
                    fetchAround();
                    warmLyricAhead();
                } catch (Throwable t) {
                    Xp.w(TAG + "queue read failed: " + t);
                }
            }
        });
    }

    private static final java.util.Map<String, String> CURRENT_READS = new java.util.HashMap<>();

    static boolean currentArtworkPending(String key) {
        synchronized (CACHE) {
            for (String track : CURRENT_READS.values()) {
                if (Main.sameTrack(track, key)) return true;
            }
        }
        return false;
    }

    /** Resolve the current song's URI independently of queue predictions. */
    // Spotify publishes its artwork URL under a custom metadata key.
    @SuppressLint("WrongConstant")
    static Bitmap currentArtwork(MediaMetadata metadata, String pkg) {
        String spotifyUrl = "com.spotify.music".equals(pkg)
                ? metadata.getString("com.spotify.music.extra.ART_HTTPS_URI") : null;
        String[] fields = spotifyUrl != null && !spotifyUrl.isEmpty()
                ? new String[]{"com.spotify.music.extra.ART_HTTPS_URI"}
                : new String[]{
                MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
                "com.spotify.music".equals(pkg) ? "com.spotify.music.extra.ART_HTTPS_URI" : null
        };
        Bitmap best = null;
        for (String field : fields) {
            if (field == null) continue;
            String value = metadata.getString(field);
            if (value == null || value.isEmpty()) continue;
            Uri uri = Uri.parse(value);
            Bitmap art;
            synchronized (CACHE) { art = CACHE.get(value); }
            if (art == null || art.isRecycled()) {
                String scheme = uri.getScheme();
                if ("content".equals(scheme) || "https".equals(scheme) || "http".equals(scheme)) {
                    String track = pkg + "|" + metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
                    boolean requested;
                    synchronized (CACHE) {
                        requested = !CURRENT_READS.containsKey(value);
                        CURRENT_READS.put(value, track);
                    }
                    if (requested) {
                        Item item = new Item(-1, metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                                uri, null, null);
                        // Decode on the background prefetch thread and ahead of queued neighbours.
                        work().postAtFrontOfQueue(() -> {
                            try { fetch(item); trim(); }
                            finally {
                                String waiting;
                                synchronized (CACHE) { waiting = CURRENT_READS.remove(value); }
                                Bitmap loaded;
                                synchronized (CACHE) { loaded = CACHE.get(value); }
                                if (loaded != null && !loaded.isRecycled()
                                        && Main.sameTrack(waiting, Main.sTrackKey)) {
                                    CoverPush.refreshArtworkQuality();
                                }
                            }
                        });
                    }
                    // A broken local provider must not block another available artwork URI.
                    continue;
                }
            }
            if (art != null && !art.isRecycled()
                    && (best == null || CoverPush.isArtworkUpgrade(best.getWidth(), best.getHeight(),
                            art.getWidth(), art.getHeight()))) best = art;
            if (best != null && !CoverPush.shouldSoftenArtwork(best.getWidth(), best.getHeight())) {
                break;
            }
        }
        return best;
    }

    static boolean isSpotifyArtwork(Bitmap art, String key) {
        if (art == null || !"com.spotify.music".equals(Main.artworkTrackPackage(key))) return false;
        synchronized (CACHE) {
            for (java.util.Map.Entry<String, Bitmap> entry : CACHE.entrySet()) {
                if (entry.getKey().startsWith("https://image-cdn.spotifycdn.com/")
                        && entry.getValue() == art) return true;
            }
        }
        return false;
    }

    /**
     * A skip was just asked for. Answers the artwork for where the queue says it lands, or null -
     * no queue, not fetched yet, or the queue has run out that way.
     *
     * Called on the thread that asked for the skip, so it only reads what is already in hand.
     */
    static Bitmap take(int dir) {
        if (dir < 0) {
            Bitmap back = takeBack();
            if (back != null) return back;
        }
        List<Item> items = sItems;
        int at = sIndex;
        if (items.isEmpty() || at < 0) return null;
        int want = at + (dir < 0 ? -1 : 1);
        if (want < 0 || want >= items.size()) {
            // The ends are real: at the last track, next may wrap, stop, or do nothing at all,
            // and the queue does not say which. Not guessing is the cheaper mistake.
            return null;
        }
        Item it = items.get(want);
        if (it.icon == null) return null;
        Bitmap b;
        synchronized (CACHE) {
            Bitmap cached = CACHE.get(it.icon.toString());
            b = cached == null || cached.isRecycled() ? null
                    : cached.copy(Bitmap.Config.ARGB_8888, false);
        }
        if (b == null) return null;
        // Moved here and now, so a second press within the burst predicts from the new place
        // rather than from where the player still thinks it is.
        sIndex = want;
        sPredicted = it.title;
        sPredictedArtist = it.artist;
        sPredictedAt = SystemClock.uptimeMillis();
        Xp.log(TAG + "predicting \"" + it.title + "\" for a skip " + (dir < 0 ? "back" : "on"));
        // The one after this one is now worth having. sIndex has already moved, so this reads
        // ahead of where the press is landing rather than of where the player still thinks it is
        // - which is the whole 0.8s the press is caught before the player reports it.
        work().post(new Runnable() {
            @Override
            public void run() {
                fetchAround();
            }
        });
        warmLyricAhead();
        return b;
    }

    /**
     * A skip back, answered from the tracks this has seen play rather than from the queue, which
     * does not hold them. Null past RESTART_MS, where the press restarts the track instead.
     */
    private static Bitmap takeBack() {
        if (positionMs() > RESTART_MS) return null;
        Item it;
        synchronized (sHistory) {
            // A player whose queue does hold the past is answered by the queue itself.
            if (sIndex > 0) return null;
            if (sBackDepth >= sHistory.size()) return null;
            it = sHistory.get(sBackDepth);
        }
        if (it.icon == null) return null;
        Bitmap b;
        synchronized (CACHE) {
            b = CACHE.get(it.icon.toString());
        }
        if (b == null || b.isRecycled()) return null;
        synchronized (sHistory) {
            sBackDepth++;
        }
        // The queue's place no longer says where the press is landing, so a skip on before the
        // player reports is left to the player rather than predicted from the wrong track.
        sIndex = -1;
        sPredicted = it.title;
        sPredictedArtist = it.artist;
        sPredictedAt = SystemClock.uptimeMillis();
        Xp.log(TAG + "predicting \"" + it.title + "\" for a skip back, from history");
        return b;
    }

    /** Where the session last said it was, carried forward to now. */
    private static long positionMs() {
        PlaybackState s = sState;
        if (s == null) return 0L;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime())
                    * s.getPlaybackSpeed());
        }
        return pos;
    }

    /**
     * Notes the current item changing: the one it replaced goes onto the history, or - when the
     * new one is the history's latest, a skip back - comes off it.
     */
    private static void noteCurrent(Item now) {
        synchronized (sHistory) {
            sBackDepth = 0;
            if (now == null) return;
            Item was = sCurrent;
            sCurrent = now;
            if (was == null || was.sameTrack(now)) return;
            if (!sHistory.isEmpty() && sHistory.get(0).sameTrack(now)) {
                sHistory.remove(0);
                return;
            }
            sHistory.add(0, was);
            while (sHistory.size() > HISTORY_MAX) sHistory.remove(sHistory.size() - 1);
        }
    }

    /**
     * Whether the track the player has now is the one already pushed for. Consumes the
     * prediction either way: it has been answered.
     */
    static String predictedKey() {
        String title = sPredicted;
        return title == null ? null : sPkg + "|" + title + "|"
                + (sPredictedArtist == null ? "" : sPredictedArtist);
    }

    static boolean wasPredicted(String title) {
        String p = sPredicted;
        long at = sPredictedAt;
        sPredicted = null;
        if (p == null || title == null) return false;
        if (SystemClock.uptimeMillis() - at > PREDICTION_TTL_MS) return false;
        boolean hit = p.equals(title);
        Xp.log(TAG + (hit ? "prediction held: " : "prediction missed: predicted \"" + p
                + "\", the player went to ") + "\"" + title + "\"");
        return hit;
    }

    /** For `op queue` and the settings page: whether this can do anything for the player. */
    static String describe() {
        List<Item> items = sItems;
        int n;
        synchronized (CACHE) {
            n = CACHE.size();
        }
        int back;
        synchronized (sHistory) {
            back = sHistory.size();
        }
        int ready;
        int readyLines = 0;
        synchronized (LYRIC_READY) {
            ready = LYRIC_READY.size();
            for (Lyrics lyric : LYRIC_READY.values()) if (!lyric.lines.isEmpty()) readyLines++;
        }
        return "queue=" + items.size() + " at=" + sIndex + " history=" + back + " cached=" + n
                + " predicted=" + sPredicted
                + " lyricReady=" + ready + "(" + readyLines + " lyrics)"
                + " " + NcmLyrics.describeSearches();
    }

    /** The three tracks relevant to a previous/current/next prediction diagnostic. */
    /** Queue diagnostics must read the session now: Spotify does not always callback on shuffle. */
    static String describeTriplet(MediaController controller) {
        if (controller != null) {
            try {
                if (readQueue(controller)) {
                    // A probe is received on SystemUI's main thread. Queue inspection is cheap,
                    // but artwork and provider I/O must remain on the prefetch worker.
                    work().post(new Runnable() {
                        @Override public void run() {
                            fetchAround();
                            warmLyricAhead();
                        }
                    });
                } else {
                    scheduleSpotifyQueueRetry(controller);
                }
            } catch (Throwable t) {
                Xp.w(TAG + "queue3 refresh failed: " + t);
            }
        }
        Item previous = null;
        synchronized (sHistory) {
            if (!sHistory.isEmpty()) previous = sHistory.get(0);
        }
        List<Item> items = sItems;
        int at = sIndex;
        Item current = at >= 0 && at < items.size() ? items.get(at) : sCurrent;
        Item next = at >= 0 && at + 1 < items.size() ? items.get(at + 1) : null;
        return "previous=" + tripletItem(previous)
                + "\ncurrent=" + tripletItem(current)
                + "\nnext=" + tripletItem(next);
    }

    private static String tripletItem(Item item) {
        if (item == null) return "none";
        String key = lyricKey(sPkg, item);
        Lyrics ready;
        boolean loading;
        synchronized (LYRIC_READY) {
            ready = LYRIC_READY.get(key);
            loading = LYRIC_LOADING.contains(key);
        }
        String state;
        if (ready != null && !ready.lines.isEmpty()) {
            state = "ready=" + ready.lines.size() + " src=" + LockLyrics.srcName(ready.source)
                    + (ready.translated ? " +translation" : "");
        } else if (loading) {
            state = "prefetching";
        } else if (ready != null) {
            state = "prefetch-miss";
        } else {
            state = "none";
        }
        if (!"none".equals(state)) {
            return "\"" + item.title + "\" by " + item.artist + " id=" + item.mediaId
                    + " " + state;
        }
        return "\"" + item.title + "\" — " + item.artist + " id=" + item.mediaId;
    }

    // ------------------------------------------------------------------ internals

    private static boolean readQueue(MediaController c) {
        String pkg = c.getPackageName();
        if (sPkg != null && !sPkg.equals(pkg)) {
            // Another player's past is not this one's.
            synchronized (sHistory) {
                sHistory.clear();
                sCurrent = null;
            }
        }
        sPkg = pkg;
        List<MediaSession.QueueItem> q = c.getQueue();
        if (q == null || q.isEmpty()) {
            sItems = new ArrayList<>();
            sIndex = -1;
            return true;
        }
        List<Item> items = new ArrayList<>(q.size());
        for (MediaSession.QueueItem qi : q) {
            MediaDescription d = qi.getDescription();
            Bundle extras = d == null ? null : d.getExtras();
            String title = d == null || d.getTitle() == null ? null : d.getTitle().toString();
            String album = extraString(extras, MediaMetadata.METADATA_KEY_ALBUM, "album");
            // A queue description is normally display text, not canonical metadata. Some
            // players (including Cider) use it for the album, though, and publish neither an
            // album extra nor duration. It is only supplemental matching evidence: a distinct
            // description can raise an already exact title/artist match, never replace it.
            if (album == null) {
                String description = str(d == null ? null : d.getDescription());
                if (description != null && !description.isEmpty() && !description.equals(title)) {
                    album = description;
                }
            }
            long duration = extraLong(extras, MediaMetadata.METADATA_KEY_DURATION,
                    "duration", "duration_ms");
            items.add(new Item(qi.getQueueId(),
                    title,
                    d == null ? null : d.getIconUri(),
                    d == null ? null : d.getMediaId(),
                    str(d == null ? null : d.getSubtitle()), album, duration));
        }
        PlaybackState ps = c.getPlaybackState();
        sState = ps;
        int at = activeQueuePosition(ps, items);
        boolean coherent = true;
        if ("com.spotify.music".equals(pkg)) {
            int metadataAt = spotifyQueuePosition(c.getMetadata(), items);
            // Spotify can update the queue and metadata on different callbacks.  A disagreement
            // is not a hint to choose either neighbour: it is an unsafe queue, so wait for the
            // next callback rather than warming lyrics for the wrong song.
            if (metadataAt < 0 || metadataAt != at) {
                at = -1;
                coherent = false;
            }
        }
        sItems = items;
        sIndex = at;
        if (at >= 0) noteCurrent(items.get(at));
        return coherent;
    }

    /** One short retry (twice at most), never a polling loop. Runs on the existing worker. */
    private static void scheduleSpotifyQueueRetry(final MediaController controller) {
        if (controller == null || !"com.spotify.music".equals(controller.getPackageName())
                || sSpotifyQueueRetry != null || sSpotifyQueueRetryCount >= SPOTIFY_QUEUE_RETRIES) {
            return;
        }
        sSpotifyQueueRetryCount++;
        Runnable retry = new Runnable() {
            @Override public void run() {
                if (sSpotifyQueueRetry != this) return;
                sSpotifyQueueRetry = null;
                try {
                    if (readQueue(controller)) {
                        sSpotifyQueueRetryCount = 0;
                        fetchAround();
                        warmLyricAhead();
                    } else {
                        scheduleSpotifyQueueRetry(controller);
                    }
                } catch (Throwable t) {
                    Xp.w(TAG + "Spotify queue retry failed: " + t);
                }
            }
        };
        sSpotifyQueueRetry = retry;
        work().postDelayed(retry, SPOTIFY_QUEUE_RETRY_MS);
    }

    private static void cancelSpotifyQueueRetry() {
        Runnable retry = sSpotifyQueueRetry;
        if (retry != null) work().removeCallbacks(retry);
        sSpotifyQueueRetry = null;
        sSpotifyQueueRetryCount = 0;
    }

    private static int activeQueuePosition(PlaybackState state, List<Item> items) {
        long active = state == null ? -1L : state.getActiveQueueItemId();
        for (int n = 0; n < items.size(); n++) {
            if (items.get(n).id == active) return n;
        }
        return -1;
    }

    private static int spotifyQueuePosition(MediaMetadata metadata, List<Item> items) {
        if (metadata == null || items.isEmpty()) return -1;
        String[] mediaIds = new String[items.size()];
        String[] titles = new String[items.size()];
        String[] artists = new String[items.size()];
        for (int n = 0; n < items.size(); n++) {
            Item item = items.get(n);
            mediaIds[n] = item.mediaId;
            titles[n] = item.title;
            artists[n] = item.artist;
        }
        return SpotifyQueuePosition.find(
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                mediaIds, titles, artists);
    }

    private static String str(CharSequence cs) {
        return cs == null ? null : cs.toString();
    }

    private static String extraString(Bundle extras, String... keys) {
        if (extras == null) return null;
        for (String key : keys) {
            try {
                String value = extras.getString(key);
                if (value != null && !value.isEmpty()) return value;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static long extraLong(Bundle extras, String... keys) {
        if (extras == null) return 0L;
        for (String key : keys) {
            try {
                long value = extras.getLong(key, 0L);
                if (value > 0L) return value;
            } catch (Throwable ignored) { }
        }
        return 0L;
    }

    /**
     * The lyric and translation for the track after this one, held ready for direct handoff.
     *
     * One ahead, where the artwork takes two either side. The cases are not the same shape: a
     * cover has to be right the instant a press lands, and two presses in a burst is what that
     * reach exists for, where nobody reads the lyrics of a song they skipped past in a second.
     * Going only forward and only one deep also keeps the request rate close to what it was,
     * which matters for the by-name half - NetEase answers a client it has decided is searching
     * too much by quietly leaving the right song out of the results.
     *
     * Any player with a usable queue can use this. Spotify additionally supplies a stable track
     * id for the optional Spicy route; it does not replace the normal providers.
     */
    private static void warmLyricAhead() {
        String pkg = sPkg;
        if (pkg == null) return;
        List<Item> items = sItems;
        int at = sIndex;
        if (items.isEmpty() || at < 0 || at + 1 >= items.size()) return;
        final Item it = items.get(at + 1);
        final boolean byName = it.title != null && !it.title.isEmpty()
                && it.artist != null && !it.artist.isEmpty();
        if (!byName) return;
        final String key = lyricKey(pkg, it);
        synchronized (LYRIC_READY) {
            if (LYRIC_READY.containsKey(key) || !LYRIC_LOADING.add(key)) return;
        }
        lyricWork().post(new Runnable() {
            @Override
            public void run() {
                try {
                    // This is deliberately keyed by the session's media id, never a fuzzy
                    // title search. It lets every player that publishes an id reuse a previous
                    // ready result without risking lyrics from a similarly named track.
                    QueuedLyricCache.Entry cached = QueuedLyricCache.read(pkg, it.mediaId);
                    if (cached != null) {
                        completeLyrics(key, new Lyrics(pkg, it, cached.lines, cached.source,
                                cached.translated));
                        Xp.log(TAG + "restored ready lyrics for \"" + it.title + "\": "
                                + cached.lines.size() + " lines");
                        return;
                    }
                    Xp.log(TAG + "preparing lyrics for \"" + it.title + "\"");
                    LyricSource.loadAhead(pkg, it.title, it.artist, it.album, it.durationMs, it.mediaId,
                            new LyricSource.AheadCallback() {
                                @Override public void onLines(final List<LyricLine> lines,
                                                              String why, final int source) {
                                    LockLyrics.translateAhead(key, lines,
                                            new LockLyrics.AheadTranslationCallback() {
                                                @Override public void onReady(List<LyricLine> merged,
                                                                              boolean translated) {
                                                    List<LyricLine> ready = merged == null
                                                            ? java.util.Collections.<LyricLine>emptyList()
                                                            : merged;
                                                    QueuedLyricCache.write(pkg, it.mediaId, ready,
                                                            source, translated);
                                                    completeLyrics(key, new Lyrics(pkg, it, ready,
                                                            source, translated));
                                                    Xp.log(TAG + "prepared \"" + it.title + "\": "
                                                            + (merged == null ? 0 : merged.size())
                                                            + " lines (" + why + ")");
                                                }
                                            });
                                }
                            });
                } catch (Throwable t) {
                    completeLyrics(key, null);
                    Xp.w(TAG + "reading ahead failed: " + t);
                }
            }
        });
    }

    /** A ready result only wins when the newly playing session is exactly that queue item. */
    static Lyrics takeLyrics(MediaController controller) {
        if (controller == null) return null;
        synchronized (LYRIC_READY) {
            for (Lyrics lyrics : LYRIC_READY.values()) {
                // A queue item has no duration or session payload, so an empty queue result is
                // never handed to the live view. Only this exact item's real lyric lines are.
                if (!lyrics.lines.isEmpty() && lyrics.matches(controller)) {
                    return lyrics;
                }
            }
        }
        return null;
    }

    /** Joins the exact queue request already in flight instead of issuing a duplicate lookup. */
    static boolean awaitLyrics(MediaController controller, LyricsCallback callback) {
        if (controller == null || callback == null) return false;
        Item item = itemOf(controller);
        if (item == null) return false;
        String key = lyricKey(controller.getPackageName(), item);
        synchronized (LYRIC_READY) {
            if (!LYRIC_LOADING.contains(key)) return false;
            List<LyricsCallback> waiters = LYRIC_WAITERS.get(key);
            if (waiters == null) {
                waiters = new ArrayList<>();
                LYRIC_WAITERS.put(key, waiters);
            }
            waiters.add(callback);
            return true;
        }
    }

    private static void completeLyrics(String key, Lyrics lyrics) {
        List<LyricsCallback> waiters;
        synchronized (LYRIC_READY) {
            LYRIC_LOADING.remove(key);
            if (lyrics != null) LYRIC_READY.put(key, lyrics);
            waiters = LYRIC_WAITERS.remove(key);
        }
        if (waiters != null) for (LyricsCallback waiter : waiters) waiter.onReady(lyrics);
    }

    /** The current track's full pipeline ended; ensure a queue update did not leave next unwarmed. */
    static void onLyricsFinal() {
        warmLyricAhead();
    }

    /** Stores a successful live result so a later queue warm can use the same exact track. */
    static void persistReady(String pkg, String mediaId, List<LyricLine> lines, int source,
                             boolean translated) {
        if (pkg == null || pkg.isEmpty() || mediaId == null || mediaId.isEmpty()
                || lines == null || lines.isEmpty()) return;
        QueuedLyricCache.write(pkg, mediaId, lines, source, translated);
        // A failed warm must not keep hiding a later successful live lookup for this exact item.
        // This update is RAM-only as well, so disabling the offline switch never disables the
        // current session's three-track handoff.
        String key = pkg + '|' + mediaId;
        synchronized (LYRIC_READY) {
            Lyrics old = LYRIC_READY.get(key);
            if (old == null || !LyricSource.words(old.lines) || LyricSource.words(lines)) {
                LYRIC_READY.put(key, new Lyrics(pkg, mediaId, lines, source, translated));
            }
        }
    }

    private static String lyricKey(String pkg, Item item) {
        return pkg + '|' + (item.mediaId != null && !item.mediaId.isEmpty()
                ? item.mediaId : item.title + '|' + item.artist);
    }

    private static Item itemOf(MediaController controller) {
        MediaMetadata metadata = controller.getMetadata();
        if (metadata == null) return null;
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null || title.isEmpty()) return null;
        return new Item(-1, title, null,
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
    }

    private static boolean same(String a, String b) {
        return a != null && a.equals(b);
    }

    /**
     * Its own thread, not the artwork's.
     *
     * The mirrors are allowed seconds and the by-name search is three round trips, and the
     * artwork prefetch is what makes a press answerable at all - sharing one thread would put
     * the cover behind the lyric of a song that has not started.
     */
    private static Handler sLyricWork;

    private static synchronized Handler lyricWork() {
        if (sLyricWork == null) {
            HandlerThread t = new HandlerThread("mc-lyricahead",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            t.start();
            sLyricWork = new Handler(t.getLooper());
        }
        return sLyricWork;
    }

    /** Fetches the artwork either side of where we think we are, newest need first. */
    private static void fetchAround() {
        List<Item> items = sItems;
        int at = sIndex;
        if (items.isEmpty() || at < 0) return;
        for (int d = 1; d <= REACH; d++) {
            fetch(items, at + d);
            fetch(items, at - d);
        }
        // What a skip back lands on, which the queue does not hold. Usually still cached from
        // when it was the track ahead; fetched again if it was trimmed since.
        Item back;
        synchronized (sHistory) {
            back = sHistory.isEmpty() ? null : sHistory.get(0);
        }
        fetch(back);
        trim();
    }

    private static void fetch(List<Item> items, int at) {
        if (at < 0 || at >= items.size()) return;
        fetch(items.get(at));
    }

    private static void fetch(Item it) {
        if (it == null || it.icon == null) return;
        String key = it.icon.toString();
        synchronized (CACHE) {
            Bitmap have = CACHE.get(key);
            if (have != null && !have.isRecycled()) return;
        }
        long t0 = SystemClock.uptimeMillis();
        Bitmap b = load(it.icon);
        if (b == null) return;
        synchronized (CACHE) {
            CACHE.put(key, b);
        }
        Xp.log(TAG + "fetched \"" + it.title + "\" " + b.getWidth() + "x" + b.getHeight()
                + " in " + (SystemClock.uptimeMillis() - t0) + "ms");
    }

    private static void trim() {
        synchronized (CACHE) {
            while (CACHE.size() > CACHE_MAX) {
                java.util.Iterator<String> it = CACHE.keySet().iterator();
                if (!it.hasNext()) return;
                it.next();
                it.remove();
            }
        }
    }

    /**
     * The artwork behind one URI. http(s) goes over the network - SystemUI holds INTERNET, and
     * this runs on the prefetch thread - and anything else goes through the resolver, so a player
     * that publishes content:// artwork is served the same way.
     */
    private static Bitmap load(Uri uri) {
        String scheme = uri.getScheme();
        try {
            if ("http".equals(scheme) || "https".equals(scheme)) {
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(uri.toString())
                                .openConnection();
                try {
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(5000);
                    conn.setInstanceFollowRedirects(true);
                    InputStream in = conn.getInputStream();
                    try {
                        return BitmapFactory.decodeStream(in);
                    } finally {
                        in.close();
                    }
                } finally {
                    conn.disconnect();
                }
            }
            Context ctx = Main.appContext();
            if (ctx == null) return null;
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            try {
                return BitmapFactory.decodeStream(in);
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            Xp.log(TAG + "could not read " + uri + ": " + t);
            return null;
        }
    }
}
