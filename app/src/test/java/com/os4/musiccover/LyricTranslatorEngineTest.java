package com.os4.musiccover;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Exercise batching/cache without any HTTP requests or provider credentials. */
public class LyricTranslatorEngineTest {
    private static final class FakeTranslator extends LibreTranslateLyricTranslator {
        int requests;
        final String prefix;
        FakeTranslator(String prefix) { this.prefix = prefix; }
        @Override
        protected Map<String, String> translateBatch(LyricTranslationLogic.Batch batch, Config cfg,
                                                     String source, String target) {
            requests++;
            Map<String, String> mapped = new LinkedHashMap<>();
            for (LyricTranslationLogic.Entry entry : batch.entries) mapped.put(entry.id, prefix + entry.text);
            return mapped;
        }
    }

    private static final class Result implements LyricTranslator.Callback {
        final CountDownLatch done = new CountDownLatch(1);
        List<LyricLine> lines;
        String failure;
        boolean cached;
        @Override
        public void onTranslated(String key, List<LyricLine> merged, boolean fromCache) {
            lines = merged;
            cached = fromCache;
            done.countDown();
        }
        @Override
        public void onFailed(String why) {
            failure = why;
            done.countDown();
        }
        void await() throws Exception { assertTrue("Callback timed out", done.await(5, TimeUnit.SECONDS)); }
    }

    private static LyricLine line(String text, String translation, int start) {
        return new LyricLine(text, translation, start, start + 1000, false, null, null, null);
    }

    @Test
    public void latinLyricsTranslateWhileNativeTranslationsAreNotRequested() throws Exception {
        FakeTranslator translator = new FakeTranslator("translated: ");
        LyricLine nativeLine = line("native", "already translated", 0);
        List<LyricLine> lines = Arrays.asList(nativeLine, line("bonjour", null, 1000));
        Result result = new Result();
        translator.translate("latin-engine-test", lines,
                new LyricTranslator.Config("https://server.example", "", "auto", "en", 1), result);
        result.await();
        assertNull(result.failure);
        assertEquals(1, translator.requests);
        assertSame(nativeLine, result.lines.get(0));
        assertEquals("translated: bonjour", result.lines.get(1).onlineTranslation);
    }

    @Test
    public void providerCachesStaySeparateEvenWithIdenticalEffectiveEndpoints() throws Exception {
        String endpoint = TranslationProvider.endpoint(TranslationProvider.GOOGLE, "");
        List<LyricLine> lines = Arrays.asList(line("bonjour", null, 0));
        FakeTranslator custom = new FakeTranslator("custom: ");
        FakeTranslator google = new FakeTranslator("google: ");
        LyricTranslator.Config customConfig = new LyricTranslator.Config(endpoint, "", "auto", "en", 1);
        LyricTranslator.Config googleConfig = new LyricTranslator.Config(
                TranslationProvider.GOOGLE, "ignored", "test-key", "auto", "en", 1);
        Result a = new Result();
        custom.translate("provider-cache-test", lines, customConfig, a);
        a.await();
        Result b = new Result();
        google.translate("provider-cache-test", lines, googleConfig, b);
        b.await();
        assertFalse(a.cached);
        assertFalse(b.cached);
        assertEquals("google: bonjour", b.lines.get(0).onlineTranslation);
        Result hit = new Result();
        google.translate("provider-cache-test", lines, googleConfig, hit);
        hit.await();
        assertTrue(hit.cached);
        assertEquals(1, google.requests);
    }

    @Test
    public void allNativeTranslationsAndDisabledModeNeverRequest() throws Exception {
        FakeTranslator translator = new FakeTranslator("unexpected");
        Result nativeResult = new Result();
        translator.translate("all-native-engine-test", Arrays.asList(line("bonjour", "hello", 0)),
                new LyricTranslator.Config("https://server.example", "", "auto", "en", 1), nativeResult);
        nativeResult.await();
        assertNotNull(nativeResult.failure);
        Result off = new Result();
        translator.translate("off-engine-test", Arrays.asList(line("bonjour", null, 0)),
                new LyricTranslator.Config("https://server.example", "", "auto", "en", 0), off);
        off.await();
        assertNotNull(off.failure);
        assertEquals(0, translator.requests);
    }

    @Test
    public void obsoleteJobsStopBeforeNextBillableBatch() throws Exception {
        final FakeTranslator translator = new FakeTranslator("test: ");
        final CountDownLatch cancelled = new CountDownLatch(1);
        final CountDownLatch callback = new CountDownLatch(1);
        List<LyricLine> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) lines.add(line("bonjour " + i, null, i * 1000));
        translator.translate("cancel-engine-test", lines,
                new LyricTranslator.Config("https://server.example", "", "auto", "en", 1),
                new LyricTranslator.Callback() {
                    @Override
                    public boolean isCurrent() {
                        if (translator.requests == 0) return true;
                        cancelled.countDown();
                        return false;
                    }
                    @Override
                    public void onTranslated(String key, List<LyricLine> merged, boolean cached) {
                        callback.countDown();
                    }
                    @Override
                    public void onFailed(String why) { callback.countDown(); }
                });
        assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        assertEquals(1, translator.requests);
        assertEquals(1L, callback.getCount());
    }
}
