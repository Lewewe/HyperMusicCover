package com.os4.musiccover;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Official Google Cloud Basic v2 and DeepL API Free/Pro, using only the user's key. */
final class CloudApiLyricTranslator extends LibreTranslateLyricTranslator {
    @Override
    protected Map<String, String> translateBatch(LyricTranslationLogic.Batch batch, Config cfg,
                                                 String source, String target) {
        String body = payload(batch.entries, cfg.provider, source, target);
        if (body == null) return Collections.emptyMap();
        boolean google = TranslationProvider.GOOGLE.equals(cfg.provider);
        // Header authentication keeps credentials out of URLs and exception messages.
        Http.Raw reply = Http.request(cfg.requestEndpoint(), "MCTr", body,
                "Content-Type", "application/json", "Accept", "application/json",
                google ? "X-Goog-Api-Key" : "Authorization",
                google ? cfg.apiKey.trim() : "DeepL-Auth-Key " + cfg.apiKey.trim());
        return reply.ok() ? LyricTranslationLogic.parseCloudResponse(reply.text(), batch.entries, google)
                : Collections.<String, String>emptyMap();
    }

    static String payload(List<LyricTranslationLogic.Entry> entries, String provider,
                          String source, String target) {
        try {
            boolean google = TranslationProvider.GOOGLE.equals(provider);
            JSONObject body = new JSONObject();
            JSONArray texts = new JSONArray();
            for (LyricTranslationLogic.Entry entry : entries) texts.put(entry.text);
            body.put(google ? "q" : "text", texts);
            body.put(google ? "target" : "target_lang",
                    google ? target : target.toUpperCase(Locale.ROOT));
            if (!"auto".equalsIgnoreCase(source)) {
                body.put(google ? "source" : "source_lang",
                        google ? source : source.toUpperCase(Locale.ROOT));
            }
            if (google) body.put("format", "text");
            return body.toString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
