package com.os4.musiccover;

import android.media.audiofx.Visualizer;
import android.media.session.PlaybackState;

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
    private static final float[] BAND_EDGES = {30f, 60f, 120f, 250f};
    private static final float[] BAND_WEIGHTS = {.55f, .30f, .15f};
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
    private static final float[] previousBands = new float[3];
    private static final float[] bandBaselines = new float[3];
    private static long lastBeatAt;
    private static long beatAt;
    private static long beatCount;
    private static int beatDirection = 1;
    private static volatile float learnedBeatMs;
    private static volatile boolean analysisReady;
    private static volatile float confidence;
    private static int lastPlaybackState = PlaybackState.STATE_NONE;
    private static long lastMediaPosition;
    private static long lastMediaPositionAt;
    private static boolean resultDelivered;
    private static final float[] tempoSupport = new float[221];
    private static int stableBpm;
    private static int challengerBpm;
    private static int challengerObservations;
    private static boolean publishedTempo;
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
            "(｡•́‿•̀｡)", "(๑•́ ₃ •̀๑)", "(｡･ω･｡)ﾉ♡", "(´• ω •`)", "( •ω• )?"
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
        start(key, 0, cb);
    }

    static void start(String key, int publishedBpm, final Callback cb) {
        stop(); track = key == null ? "" : key;
        Integer cached;
        synchronized (CACHE) { cached = CACHE.get(track); }
        int trustedBpm = validBpm(publishedBpm);
        try {
            final Visualizer v = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange(); v.setCaptureSize(range[1]);
            envelope.clear(); previousMagnitudes = null; firstSampleAt = 0L; lastSampleAt = 0L;
            java.util.Arrays.fill(previousBands, 0f);
            java.util.Arrays.fill(bandBaselines, 0f);
            lastBeatAt = 0L; beatAt = 0L; beatCount = 0L; beatDirection = 1;
            confidence = 0f; lastPlaybackState = PlaybackState.STATE_NONE;
            lastMediaPosition = 0L; lastMediaPositionAt = 0L;
            learnedBeatMs = cached == null ? 0f : 60000f / cached;
            stableBpm = cached == null ? 0 : cached;
            if (trustedBpm > 0) {
                stableBpm = trustedBpm;
                cached = trustedBpm;
            }
            learnedBeatMs = cached == null ? 0f : 60000f / cached;
            challengerBpm = 0;
            challengerObservations = 0;
            publishedTempo = trustedBpm > 0;
            java.util.Arrays.fill(tempoSupport, 0f);
            analysisReady = cached != null;
            resultDelivered = false;
            energy = 0f; visualizer = v;
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer ignored, byte[] wave, int rate) { }
                @Override public void onFftDataCapture(Visualizer ignored, byte[] fft, int rate) { sample(fft, rate); }
            }, Visualizer.getMaxCaptureRate(), false, true);
            v.setEnabled(true);
            if (cached == null && !publishedTempo) {
                Main.main().postDelayed(new Runnable() { @Override public void run() {
                    if (visualizer != v) return;
                    int bpm = estimate();
                    if (bpm <= 0) {
                        Main.main().postDelayed(this, 5_000L);
                        return;
                    }
                    int previousStable = stableBpm;
                    addTempoObservation(bpm);
                    stableBpm = consistentTempo();
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
    private static int validBpm(int bpm) {
        return bpm >= 40 && bpm <= 220 ? bpm : 0;
    }
    static boolean estimating() { return visualizer != null && !analysisReady; }
    static float confidence() { return confidence; }
    static float pulse() { return energy; }
    static void clearCache() {
        synchronized (CACHE) { CACHE.clear(); }
        stop();
    }
    static void stop() { Visualizer v = visualizer; visualizer = null; if (v != null) try { v.setEnabled(false); v.release(); } catch (Throwable ignored) { } }
    static synchronized void onPlaybackState(PlaybackState state) {
        if (state == null) return;
        int next = state.getState();
        long position = Math.max(0L, state.getPosition());
        long now = android.os.SystemClock.elapsedRealtime();
        boolean changed = lastPlaybackState != PlaybackState.STATE_NONE
                && next != lastPlaybackState;
        boolean jumped = lastMediaPositionAt != 0L
                && Math.abs(position - lastMediaPosition) > 1500L;
        if (changed || jumped) {
            envelope.clear();
            previousMagnitudes = null;
            java.util.Arrays.fill(previousBands, 0f);
            java.util.Arrays.fill(bandBaselines, 0f);
            firstSampleAt = 0L;
            lastSampleAt = 0L;
            lastBeatAt = 0L;
            beatAt = 0L;
            beatCount = 0L;
            confidence = 0f;
            analysisReady = next != PlaybackState.STATE_PLAYING;
        }
        lastPlaybackState = next;
        lastMediaPosition = position;
        lastMediaPositionAt = now;
    }
    private static synchronized void sample(byte[] fft, int rateMilliHz) {
        if (fft == null || fft.length < 4 || visualizer == null) return;
        int bins = fft.length / 2;
        float sampleRate = rateMilliHz > 0 ? rateMilliHz / 1000f : 44100f;
        float binWidth = sampleRate / (bins * 2f);
        int from = Math.max(1, (int) Math.ceil(LOW_HZ / binWidth));
        int to = Math.min(bins - 1, (int) Math.floor(HIGH_HZ / binWidth));
        if (from > to) return;

        float[] magnitudes = new float[to - from + 1];
        float[] bands = new float[3];
        for (int bin = from; bin <= to; bin++) {
            int offset = bin * 2;
            float magnitude = (float) Math.hypot(fft[offset], fft[offset + 1]);
            magnitudes[bin - from] = (float) Math.log1p(magnitude);
            float hz = bin * binWidth;
            for (int band = 0; band < 3; band++) {
                if (hz >= BAND_EDGES[band] && hz < BAND_EDGES[band + 1]) {
                    bands[band] += magnitude;
                    break;
                }
            }
        }
        float flux = 0f;
        float lowEnergy = 0f;
        for (int band = 0; band < 3; band++) {
            float normalized = previousBands[band] <= 0f ? 0f
                    : Math.max(0f, bands[band] - previousBands[band])
                    / (previousBands[band] + 8f);
            bandBaselines[band] = bandBaselines[band] == 0f ? normalized
                    : bandBaselines[band] * .97f + normalized * .03f;
            flux += BAND_WEIGHTS[band] * normalized;
            lowEnergy += BAND_WEIGHTS[band] * Math.min(1f, bands[band] / 500f);
            previousBands[band] = bands[band];
        }
        previousMagnitudes = magnitudes;
        long now = android.os.SystemClock.uptimeMillis();
        if (firstSampleAt == 0L) firstSampleAt = now;
        lastSampleAt = now;
        float minimumGap = learnedBeatMs > 0f ? learnedBeatMs * .82f : 300f;
        float threshold = 0f;
        for (int band = 0; band < 3; band++) {
            threshold += BAND_WEIGHTS[band] * Math.max(.002f, bandBaselines[band] * 1.8f);
        }
        boolean onsetBeat = false;
        boolean nearExpectedBeat = true;
        if (learnedBeatMs > 0f && lastBeatAt > 0L) {
            float ratio = (now - lastBeatAt) / learnedBeatMs;
            nearExpectedBeat = ratio >= .68f && ratio <= 1.34f;
        }
        if (flux > threshold && now - lastBeatAt >= minimumGap && nearExpectedBeat) {
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
            onsetBeat = true;
        }
        // Once tempo is known, the metrical clock is more reliable than any individual onset.
        // Weak kicks can disappear in a mix, while guitar/piano subdivisions can arrive early.
        // Let onsets correct the phase, but synthesize only missing full-beat events here.
        if (!onsetBeat && learnedBeatMs > 0f && lastBeatAt > 0L) {
            long elapsed = now - lastBeatAt;
            int missed = 0;
            while (elapsed >= learnedBeatMs * .92f && missed++ < 3) {
                lastBeatAt += Math.max(1L, Math.round(learnedBeatMs));
                beatAt = lastBeatAt;
                beatCount++;
                beatDirection = -beatDirection;
                elapsed = now - lastBeatAt;
            }
        }
        energy = Math.min(1f, energy * .65f + lowEnergy * .35f);
        envelope.add(flux);
        if (envelope.size() > 420) envelope.remove(0);
    }
    static long beatAt() { return beatAt; }
    static long beatCount() { return beatCount; }
    static int beatDirection() { return beatDirection; }
    private static void addTempoObservation(int bpm) {
        if (bpm < 40 || bpm > 220) return;
        for (int candidate = Math.max(40, bpm - 2); candidate <= Math.min(220, bpm + 2); candidate++) {
            tempoSupport[candidate] += 1f / (1f + Math.abs(candidate - bpm));
        }
    }
    private static int mostConsistentTempo() {
        int best = stableBpm;
        float score = best >= 40 && best <= 220 ? tempoSupport[best] : -1f;
        for (int bpm = 40; bpm <= 220; bpm++) {
            if (tempoSupport[bpm] > score + .15f) {
                score = tempoSupport[bpm];
                best = bpm;
            }
        }
        return best;
    }
    private static int consistentTempo() {
        int candidate = mostConsistentTempo();
        if (stableBpm == 0 || candidate == stableBpm) {
            challengerBpm = 0;
            challengerObservations = 0;
            return candidate;
        }
        float currentSupport = tempoSupport[Math.max(40, Math.min(220, stableBpm))];
        float candidateSupport = tempoSupport[candidate];
        if (candidateSupport < currentSupport + 1.25f) {
            challengerBpm = 0;
            challengerObservations = 0;
            return stableBpm;
        }
        if (Math.abs(candidate - challengerBpm) <= 2) {
            challengerObservations++;
        } else {
            challengerBpm = candidate;
            challengerObservations = 1;
        }
        if (challengerObservations < 3) return stableBpm;
        challengerBpm = 0;
        challengerObservations = 0;
        return candidate;
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
        float secondScore = -.01f;
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
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                best = bpm;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }
        // A kick pattern can make the half-time period correlate slightly better. Prefer its
        // beat-level double when it remains coherent, which is the useful visual tempo.
        if (best > 0 && best < 105) {
            int lo = Math.max(50, best * 2 - 8), hi = Math.min(200, best * 2 + 8);
            int doubleBest = 0;
            float doubleScore = -Float.MAX_VALUE;
            for (int bpm = lo; bpm <= hi; bpm++) {
                if (scores[bpm] > doubleScore) { doubleScore = scores[bpm]; doubleBest = bpm; }
            }
            if (doubleBest > 0 && doubleScore >= bestScore * .35f) {
                best = doubleBest;
                bestScore = doubleScore;
            }
        }
        if (best >= 140) {
            int half = 0;
            float slowGrid = 0f;
            for (int candidate = 75; candidate <= 120; candidate++) {
                float score = beatGridScore(onset, mean, samplesPerSecond, candidate, n);
                if (score > slowGrid) {
                    slowGrid = score;
                    half = candidate;
                }
            }
            if (half > 0) {
                float fastGrid = beatGridScore(onset, mean, samplesPerSecond, best, n);
                // A genuinely fast beat has comparable accents on its short grid. Guitar,
                // arpeggios and hi-hat subdivisions instead tend to alternate strong and weak
                // attacks; their longer grid is the musical beat.
                if (slowGrid > fastGrid * 1.30f) {
                    best = half;
                    bestScore = Math.max(bestScore, slowGrid);
                }
            }
        }
        confidence = bestScore <= 0f ? 0f
                : Math.min(1f, Math.max(0f, (bestScore - secondScore) * 3f));
        // Keep the established score floor for compatibility with sparse but periodic material.
        // Confidence is retained for future UI gating; adjacent BPM bins can be nearly tied when
        // a short window quantizes the same pulse into neighboring lags.
        return bestScore >= .10f ? best : 0;
    }
    private static float beatGridScore(float[] onset, float mean, float rate,
                                       int bpm, int n) {
        float period = rate * 60f / bpm;
        if (period < 2f) return 0f;
        float total = 0f;
        int count = 0;
        for (float phase = 0f; phase < period; phase += Math.max(1f, period / 8f)) {
            float score = 0f;
            int hits = 0;
            for (float expected = phase; expected < n; expected += period) {
                int centre = Math.round(expected);
                float peak = 0f;
                for (int offset = -2; offset <= 2; offset++) {
                    int index = centre + offset;
                    if (index >= 0 && index < n) {
                        peak = Math.max(peak, Math.max(0f, onset[index] - mean));
                    }
                }
                score += peak;
                hits++;
            }
            if (hits > 0) total = Math.max(total, score / hits);
        }
        return total;
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
