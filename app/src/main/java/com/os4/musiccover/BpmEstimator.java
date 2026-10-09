package com.os4.musiccover;

import android.media.audiofx.Visualizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Local, low-fidelity tempo estimate from the system output mix. It never records audio to disk. */
final class BpmEstimator {
    private BpmEstimator() { }
    private static final long WINDOW_MS = 15_000L;
    private static final float LOW_HZ = 30f;
    private static final float HIGH_HZ = 250f;
    private static final Map<String, Integer> CACHE = new LinkedHashMap<String, Integer>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> e) { return size() > 80; }
    };
    private static Visualizer visualizer;
    private static String track = "";
    /** Low-band onset envelope; no audio samples are retained. */
    private static final ArrayList<Float> envelope = new ArrayList<>();
    private static volatile float energy;
    private static long firstSampleAt;
    private static long lastSampleAt;
    private static float[] previousMagnitudes;
    private static float fluxBaseline;
    private static long lastBeatAt;
    private static long beatAt;
    private static long beatCount;
    private static int beatDirection = 1;
    private static volatile float learnedBeatMs;
    private static volatile boolean analysisReady;
    private static boolean resultDelivered;
    private static final float[] tempoSupport = new float[201];
    private static int stableBpm;
    private static final String[] FACES = {
            "(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧", "( •̀ ω •́ )✧", "(づ｡◕‿‿◕｡)づ",
            "(≧▽≦)", "(っ˘ω˘ς )", "(ง •̀_•́)ง"
    };
    private static final String[] MESSAGES_EN = {
            "we have no lyrics, but here's a friend",
            "dance alongside the kaomoji for now..",
            "this song has no lyrics, but the beat is still here",
            "don't worry buddy, it'll be alright",
            "there are no lyrics.."
    };
    private static final String[] MESSAGES_ZH = {
            "我们没有歌词，但还有一个朋友陪着你",
            "暂时和颜文字一起跳舞吧..",
            "这首歌没有歌词，但节拍依然在",
            "别担心，一切都会好起来的",
            "没有歌词.."
    };
    private static final String[] SEARCH_FACES = {
            "(・_・?)", "(・・ )", "(¬_¬)", "(・へ・)", "(⊙_⊙)"
    };
    private static final String[] SEARCH_MESSAGES_EN = {
            "looking for your lyrics…", "checking for lyrics…", "searching for lyrics…"
    };
    private static final String[] SEARCH_MESSAGES_ZH = {
            "正在寻找歌词…", "正在检查歌词…", "正在搜索歌词…"
    };

    static String presentationFace(int seed) {
        return FACES[Math.floorMod(seed, FACES.length)];
    }

    static String presentationMessage(int seed) {
        String[] messages = Locale.getDefault().getLanguage().startsWith("zh")
                ? MESSAGES_ZH : MESSAGES_EN;
        return messages[Math.floorMod(seed, messages.length)];
    }

    static String searchingFace(int seed) {
        return SEARCH_FACES[Math.floorMod(seed, SEARCH_FACES.length)];
    }

    static String searchingMessage(int seed) {
        String[] messages = Locale.getDefault().getLanguage().startsWith("zh")
                ? SEARCH_MESSAGES_ZH : SEARCH_MESSAGES_EN;
        return messages[Math.floorMod(seed, messages.length)];
    }

    static void start(String key, final Callback cb) {
        stop(); track = key == null ? "" : key;
        Integer cached;
        synchronized (CACHE) { cached = CACHE.get(track); }
        try {
            final Visualizer v = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange(); v.setCaptureSize(range[1]);
            envelope.clear(); previousMagnitudes = null; firstSampleAt = 0L; lastSampleAt = 0L;
            fluxBaseline = 0f; lastBeatAt = 0L; beatAt = 0L; beatCount = 0L; beatDirection = 1;
            learnedBeatMs = cached == null ? 0f : 60000f / cached;
            stableBpm = cached == null ? 0 : cached;
            java.util.Arrays.fill(tempoSupport, 0f);
            analysisReady = cached != null;
            resultDelivered = false;
            energy = 0f; visualizer = v;
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer ignored, byte[] wave, int rate) { }
                @Override public void onFftDataCapture(Visualizer ignored, byte[] fft, int rate) { sample(fft, rate); }
            }, Visualizer.getMaxCaptureRate(), false, true);
            v.setEnabled(true);
            if (cached == null) {
                Main.main().postDelayed(new Runnable() { @Override public void run() {
                    if (visualizer != v) return;
                    int bpm = estimate();
                    if (bpm <= 0) {
                        Main.main().postDelayed(this, 5_000L);
                        return;
                    }
                    int previousStable = stableBpm;
                    addTempoObservation(bpm);
                    stableBpm = mostConsistentTempo();
                    synchronized (CACHE) { CACHE.put(track, stableBpm); }
                    learnedBeatMs = 60000f / stableBpm;
                    analysisReady = true;
                    if (!resultDelivered || stableBpm != previousStable) {
                        resultDelivered = true;
                        cb.onEstimated(stableBpm);
                    }
                    Main.main().postDelayed(this, 5_000L);
                }}, 5_000L);
            }
            if (cached != null) {
                resultDelivered = true;
                cb.onEstimated(cached);
            }
        } catch (Throwable t) { Xp.w("BPM visualizer unavailable: " + t); cb.onEstimated(0); }
    }
    static boolean estimating() { return visualizer != null && !analysisReady; }
    static float pulse() { return energy; }
    static void clearCache() {
        synchronized (CACHE) { CACHE.clear(); }
        stop();
    }
    static void stop() { Visualizer v = visualizer; visualizer = null; if (v != null) try { v.setEnabled(false); v.release(); } catch (Throwable ignored) { } }
    private static synchronized void sample(byte[] fft, int rateMilliHz) {
        if (fft == null || fft.length < 4 || visualizer == null) return;
        int bins = fft.length / 2;
        float sampleRate = rateMilliHz > 0 ? rateMilliHz / 1000f : 44100f;
        float binWidth = sampleRate / (bins * 2f);
        int from = Math.max(1, (int) Math.ceil(LOW_HZ / binWidth));
        int to = Math.min(bins - 1, (int) Math.floor(HIGH_HZ / binWidth));
        if (from > to) return;

        float[] magnitudes = new float[to - from + 1];
        float lowEnergy = 0f;
        for (int bin = from; bin <= to; bin++) {
            int offset = bin * 2;
            float magnitude = (float) Math.hypot(fft[offset], fft[offset + 1]);
            magnitudes[bin - from] = (float) Math.log1p(magnitude);
            lowEnergy += magnitude;
        }
        float flux = 0f;
        if (previousMagnitudes != null && previousMagnitudes.length == magnitudes.length) {
            for (int i = 0; i < magnitudes.length; i++) {
                flux += Math.max(0f, magnitudes[i] - previousMagnitudes[i]);
            }
            flux /= magnitudes.length;
        }
        previousMagnitudes = magnitudes;
        long now = android.os.SystemClock.uptimeMillis();
        if (firstSampleAt == 0L) firstSampleAt = now;
        lastSampleAt = now;
        fluxBaseline = fluxBaseline == 0f ? flux : fluxBaseline * .96f + flux * .04f;
        float minimumGap = learnedBeatMs > 0f ? learnedBeatMs * .82f : 300f;
        if (flux > Math.max(.006f, fluxBaseline * 1.65f) && now - lastBeatAt >= minimumGap) {
            if (lastBeatAt > 0L) {
                float interval = now - lastBeatAt;
                if (interval >= 350f && interval <= 1500f
                        && (learnedBeatMs == 0f || (interval / learnedBeatMs >= .70f
                        && interval / learnedBeatMs <= 1.50f))) {
                    learnedBeatMs = learnedBeatMs == 0f ? interval
                            : learnedBeatMs * .88f + interval * .12f;
                }
            }
            lastBeatAt = now;
            beatAt = now;
            beatCount++;
            beatDirection = -beatDirection;
        }
        energy = Math.min(1f, energy * .65f + Math.min(1f, lowEnergy / (magnitudes.length * 80f)) * .35f);
        envelope.add(flux);
        if (envelope.size() > 420) envelope.remove(0);
    }
    static long beatAt() { return beatAt; }
    static long beatCount() { return beatCount; }
    static int beatDirection() { return beatDirection; }
    private static void addTempoObservation(int bpm) {
        if (bpm < 75 || bpm > 190) return;
        for (int candidate = Math.max(75, bpm - 2); candidate <= Math.min(190, bpm + 2); candidate++) {
            tempoSupport[candidate] += 1f / (1f + Math.abs(candidate - bpm));
        }
    }
    private static int mostConsistentTempo() {
        int best = stableBpm;
        float score = best >= 75 && best <= 190 ? tempoSupport[best] : -1f;
        for (int bpm = 50; bpm <= 200; bpm++) {
            if (tempoSupport[bpm] > score + .15f) {
                score = tempoSupport[bpm];
                best = bpm;
            }
        }
        return best;
    }
    private static synchronized int estimate() {
        final int n = envelope.size();
        if (n < 36) return 0;
        float[] onset = new float[n];
        for (int i = 0; i < n; i++) onset[i] = envelope.get(i);
        float samplesPerSecond = lastSampleAt > firstSampleAt
                ? (n - 1) * 1000f / (lastSampleAt - firstSampleAt)
                : n * 1000f / WINDOW_MS;
        return estimateTempo(onset, samplesPerSecond);
    }

    static int estimateTempo(float[] onset, float samplesPerSecond) {
        final int n = onset.length;
        if (n < 36 || samplesPerSecond <= 0f) return 0;
        float mean = 0f;
        for (float value : onset) mean += value;
        mean /= n;
        float variance = 0f;
        for (float value : onset) { float d = value - mean; variance += d * d; }
        if (variance < .00002f) return 0;

        int best = 0;
        float bestScore = -.01f;
        float[] scores = new float[201];
        for (int bpm = 75; bpm <= 190; bpm++) {
            int lag = Math.round(samplesPerSecond * 60f / bpm);
            if (lag < 2 || lag >= n - 8) continue;
            float xy = 0f, xx = 0f, yy = 0f;
            for (int i = lag; i < n; i++) {
                float x = onset[i] - mean, y = onset[i - lag] - mean;
                xy += x * y; xx += x * x; yy += y * y;
            }
            float correlation = xy / (float) Math.sqrt(Math.max(.0000001f, xx * yy));
            // A tempo comb rewards repeated beat spacing and its subdivisions. The shorter
            // period gets a small preference when both beat and half-time periods correlate.
            float score = correlation + .12f * harmonicScore(onset, mean, samplesPerSecond, bpm, n);
            scores[bpm] = score;
            if (score > bestScore) { bestScore = score; best = bpm; }
        }
        // A kick pattern can make the half-time period correlate slightly better. Prefer its
        // beat-level double when it remains coherent, which is the useful visual tempo.
        if (best > 0 && best < 105) {
            int lo = Math.max(50, best * 2 - 8), hi = Math.min(200, best * 2 + 8);
            int doubleBest = 0;
            float doubleScore = -.01f;
            for (int bpm = lo; bpm <= hi; bpm++) {
                if (scores[bpm] > doubleScore) { doubleScore = scores[bpm]; doubleBest = bpm; }
            }
            if (doubleBest > 0 && doubleScore >= bestScore * .35f) {
                best = doubleBest;
                bestScore = doubleScore;
            }
        }
        return bestScore >= .10f ? best : 0;
    }
    private static float harmonicScore(float[] onset, float mean, float rate, int bpm, int n) {
        int halfLag = Math.round(rate * 30f / bpm);
        if (halfLag < 1) return 0f;
        float score = 0f;
        int count = 0;
        for (int i = halfLag; i < n; i += halfLag) {
            score += Math.max(0f, onset[i] - mean);
            count++;
        }
        return count == 0 ? 0f : score / count;
    }
    interface Callback { void onEstimated(int bpm); }
}
