package com.os4.musiccover;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * A LibreTranslate-compatible backend.
 *
 * Input lines are tagged with stable IDs before translation so responses are mapped by ID, not by
 * list index, and stale or partial responses can be ignored safely.
 */
class LibreTranslateLyricTranslator implements LyricTranslator {
    private static final String TAG = "MCTr";
    private static final int BATCH_MAX_LINES = 24;
    private static final int BATCH_MAX_CHARS = 2200;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "MCLyricTranslate");
                    t.setDaemon(true);
                    return t;
                }
            });

    private static final int CACHE_MAX = 48;
    private static final Map<String, Map<String, String>> CACHE =
            new LinkedHashMap<String, Map<String, String>>(CACHE_MAX + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> e) {
                    return size() > CACHE_MAX;
                }
            };

    @Override
    public void translate(final String trackKey, final List<LyricLine> lines, final Config cfg,
                          final Callback cb) {
        if (cb == null) return;
        if (cfg == null || !cfg.enabled()) {
            cb.onFailed("translation is disabled");
            return;
        }
        if (lines == null || lines.isEmpty()) {
            cb.onFailed("no lines");
            return;
        }
        final String endpoint = cfg.requestEndpoint();
        if (endpoint.isEmpty()) {
            cb.onFailed("translation endpoint missing");
            return;
        }
        final String source = LyricTranslationLogic.normLang(cfg.sourceLanguage, "auto");
        final String target = LyricTranslationLogic.normLang(cfg.targetLanguage, "en");
        final List<LyricTranslationLogic.Entry> entries =
                LyricTranslationLogic.entriesForTranslation(lines, source, target);
        if (entries.isEmpty()) {
            // No network call or cache lookup: do not spend credits when the lyrics already match
            // the requested target language (or contain no usable lyric text).
            cb.onFailed("lyrics already in target language or no translatable lines");
            return;
        }
        final String cacheKey = LyricTranslationLogic.cacheKey("v3|" + cfg.provider + "|" + trackKey,
                endpoint, source, target, lines);
        Map<String, String> hit;
        synchronized (CACHE) {
            hit = CACHE.get(cacheKey);
        }
        if (hit != null && !hit.isEmpty()) {
            cb.onTranslated(cacheKey, LyricTranslationLogic.merge(lines, hit), true);
            return;
        }
        final List<LyricTranslationLogic.Batch> batches =
                LyricTranslationLogic.batches(entries, BATCH_MAX_CHARS, BATCH_MAX_LINES);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                LinkedHashMap<String, String> translated = new LinkedHashMap<>();
                for (LyricTranslationLogic.Batch batch : batches) {
                    if (!cb.isCurrent()) return;
                    Map<String, String> mapped = translateBatch(batch, cfg, source, target);
                    if (!mapped.isEmpty()) translated.putAll(mapped);
                }
                if (translated.isEmpty()) {
                    cb.onFailed("translation request failed");
                    return;
                }
                synchronized (CACHE) {
                    CACHE.put(cacheKey, translated);
                }
                cb.onTranslated(cacheKey, LyricTranslationLogic.merge(lines, translated), false);
            }
        });
    }

    protected Map<String, String> translateBatch(LyricTranslationLogic.Batch batch, Config cfg,
                                                  String source, String target) {
        String body = payload(batch.payload, source, target, cfg.apiKey);
        if (body == null) return java.util.Collections.emptyMap();
        Http.Raw reply = Http.request(urlOf(cfg.requestEndpoint()), TAG, body,
                "Content-Type", "application/json", "Accept", "application/json");
        return reply.ok() ? LyricTranslationLogic.parseLibreResponse(reply.text(), batch.entries)
                : java.util.Collections.<String, String>emptyMap();
    }

    private static String payload(String markedLines, String source, String target, String apiKey) {
        try {
            JSONObject body = new JSONObject();
            body.put("q", markedLines);
            body.put("source", source);
            body.put("target", target);
            body.put("format", "text");
            if (apiKey != null && !apiKey.trim().isEmpty()) body.put("api_key", apiKey.trim());
            return body.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String urlOf(String endpoint) {
        String e = endpoint;
        return e.endsWith("/translate") ? e : (e + "/translate");
    }
}
