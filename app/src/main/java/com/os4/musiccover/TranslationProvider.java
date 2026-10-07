package com.os4.musiccover;

/** Stable persisted IDs shared by the settings UI and SystemUI. */
public final class TranslationProvider {
    public static final String CUSTOM = "custom";
    public static final String GOOGLE = "google";
    public static final String DEEPL_FREE = "deepl_free";
    public static final String DEEPL_PRO = "deepl_pro";

    private TranslationProvider() { }

    public static String normalize(String provider) {
        if (GOOGLE.equals(provider) || DEEPL_FREE.equals(provider) || DEEPL_PRO.equals(provider)) {
            return provider;
        }
        // Missing IDs in old state/backup files retain the user's LibreTranslate endpoint.
        return CUSTOM;
    }

    public static String endpoint(String provider, String customEndpoint) {
        switch (normalize(provider)) {
            case GOOGLE: return "https://translation.googleapis.com/language/translate/v2";
            case DEEPL_FREE: return "https://api-free.deepl.com/v2/translate";
            case DEEPL_PRO: return "https://api.deepl.com/v2/translate";
            default: return LyricTranslationLogic.normalizeEndpoint(customEndpoint);
        }
    }

    public static boolean isValidCustomEndpoint(String endpoint) {
        try {
            java.net.URI uri = new java.net.URI(endpoint == null ? "" : endpoint.trim());
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    static LyricTranslator translator(String provider) {
        return CUSTOM.equals(normalize(provider))
                ? new LibreTranslateLyricTranslator() : new CloudApiLyricTranslator();
    }
}
