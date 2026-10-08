package com.os4.musiccover;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

public class TranslationProviderTest {
    private static final List<LyricTranslationLogic.Entry> ENTRIES = Arrays.asList(
            new LyricTranslationLogic.Entry(0, "0-0", "bonjour"),
            new LyricTranslationLogic.Entry(2, "3e8-2", "こんにちは"));

    @Test
    public void legacyAndUnknownProvidersRetainCustomEndpoint() {
        assertEquals(TranslationProvider.CUSTOM, TranslationProvider.normalize(null));
        assertEquals(TranslationProvider.CUSTOM, TranslationProvider.normalize("future"));
        LyricTranslator.Config legacy = new LyricTranslator.Config(
                "https://my.server/", "", "auto", "en", 1);
        assertTrue(legacy.enabled());
        assertEquals("https://my.server", legacy.requestEndpoint());
        assertTrue(TranslationProvider.translator(null) instanceof LibreTranslateLyricTranslator);
    }

    @Test
    public void officialEndpointsIgnoreCustomUrlAndRequireUserKey() {
        String[] providers = {TranslationProvider.GOOGLE, TranslationProvider.DEEPL_FREE,
                TranslationProvider.DEEPL_PRO};
        String[] endpoints = {"https://translation.googleapis.com/language/translate/v2",
                "https://api-free.deepl.com/v2/translate", "https://api.deepl.com/v2/translate"};
        for (int i = 0; i < providers.length; i++) {
            LyricTranslator.Config cfg = new LyricTranslator.Config(
                    providers[i], "https://untrusted.example", "user-owned-key", "auto", "en", 1);
            assertTrue(cfg.enabled());
            assertEquals(endpoints[i], cfg.requestEndpoint());
            assertTrue(TranslationProvider.translator(providers[i]) instanceof CloudApiLyricTranslator);
            assertFalse(new LyricTranslator.Config(providers[i], "anything", "", "auto", "en", 1)
                    .enabled());
            assertFalse(new LyricTranslator.Config(providers[i], "", "key", "auto", "en", 0)
                    .enabled());
        }
    }

    @Test
    public void customUrlsRejectCredentialsQueriesAndNonHttpSchemes() {
        assertTrue(TranslationProvider.isValidCustomEndpoint(" https://host.example/translate/ "));
        assertTrue(TranslationProvider.isValidCustomEndpoint("http://localhost:5000"));
        for (String url : Arrays.asList("", "host.example", "file:///secret", "https://host/?key=secret",
                "https://user:secret@host", "https://host/#fragment")) {
            assertFalse(url, TranslationProvider.isValidCustomEndpoint(url));
        }
    }

    @Test
    public void googleUsesPlainTextArrayAndOmitsAutomaticSource() throws Exception {
        JSONObject body = new JSONObject(CloudApiLyricTranslator.payload(
                ENTRIES, TranslationProvider.GOOGLE, "auto", "ja"));
        assertEquals("bonjour", body.getJSONArray("q").getString(0));
        assertEquals("こんにちは", body.getJSONArray("q").getString(1));
        assertEquals("ja", body.getString("target"));
        assertEquals("text", body.getString("format"));
        assertFalse(body.has("source"));
        assertFalse(body.has("key"));
        assertFalse(body.toString().contains("MCID"));
        JSONObject explicit = new JSONObject(CloudApiLyricTranslator.payload(
                ENTRIES, TranslationProvider.GOOGLE, "fr", "en"));
        assertEquals("fr", explicit.getString("source"));
    }

    @Test
    public void deeplUsesUppercaseLanguageCodesAndIndependentTexts() throws Exception {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            JSONObject body = new JSONObject(CloudApiLyricTranslator.payload(
                    ENTRIES, TranslationProvider.DEEPL_FREE, "auto", "en-us"));
            assertEquals(2, body.getJSONArray("text").length());
            assertEquals("EN-US", body.getString("target_lang"));
            assertFalse(body.has("source_lang"));
            JSONObject explicit = new JSONObject(CloudApiLyricTranslator.payload(
                    ENTRIES, TranslationProvider.DEEPL_PRO, "fi", "it"));
            assertEquals("FI", explicit.getString("source_lang"));
            assertEquals("IT", explicit.getString("target_lang"));
            assertEquals("it", LyricTranslationLogic.normLang("IT", "en"));
        } finally {
            Locale.setDefault(old);
        }
    }

    @Test
    public void settingsSnapshotRejectsProviderLanguageKeyAndModeChanges() {
        LyricTranslator.Config a = new LyricTranslator.Config("google", "", "key", "auto", "en", 1);
        assertTrue(a.sameSettings(new LyricTranslator.Config("google", "", "key", "auto", "en", 1)));
        assertFalse(a.sameSettings(new LyricTranslator.Config("deepl_free", "", "key", "auto", "en", 1)));
        assertFalse(a.sameSettings(new LyricTranslator.Config("google", "", "key", "auto", "ja", 1)));
        assertFalse(a.sameSettings(new LyricTranslator.Config("google", "", "other", "auto", "en", 1)));
        assertFalse(a.sameSettings(new LyricTranslator.Config("google", "", "key", "auto", "en", 0)));
    }
}
