package com.os4.musiccover;

import java.util.List;

/** Online line-level lyric translation backend abstraction. */
interface LyricTranslator {

    final class Config {
        final String provider;
        final String endpoint;
        final String apiKey;
        final String sourceLanguage;
        final String targetLanguage;
        final int mode;

        Config(String endpoint, String apiKey, String sourceLanguage, String targetLanguage, int mode) {
            this(TranslationProvider.CUSTOM, endpoint, apiKey, sourceLanguage, targetLanguage, mode);
        }

        Config(String provider, String endpoint, String apiKey, String sourceLanguage,
               String targetLanguage, int mode) {
            this.provider = TranslationProvider.normalize(provider);
            this.endpoint = endpoint;
            this.apiKey = apiKey;
            this.sourceLanguage = sourceLanguage;
            this.targetLanguage = targetLanguage;
            this.mode = mode;
        }

        boolean enabled() {
            return mode != 0 && (TranslationProvider.CUSTOM.equals(provider)
                    ? TranslationProvider.isValidCustomEndpoint(endpoint)
                    : apiKey != null && !apiKey.trim().isEmpty());
        }

        String requestEndpoint() {
            return TranslationProvider.endpoint(provider, endpoint);
        }

        boolean sameSettings(Config other) {
            return other != null && provider.equals(other.provider) && mode == other.mode
                    && java.util.Objects.equals(endpoint, other.endpoint)
                    && java.util.Objects.equals(apiKey, other.apiKey)
                    && java.util.Objects.equals(sourceLanguage, other.sourceLanguage)
                    && java.util.Objects.equals(targetLanguage, other.targetLanguage);
        }
    }

    interface Callback {
        /** Check between batches so an obsolete track/config does not keep incurring API usage. */
        default boolean isCurrent() { return true; }

        void onTranslated(String cacheKey, List<LyricLine> merged, boolean fromCache);

        void onFailed(String why);
    }

    void translate(String trackKey, List<LyricLine> lines, Config config, Callback callback);
}
