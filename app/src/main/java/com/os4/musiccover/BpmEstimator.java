package com.os4.musiccover;

import android.media.session.PlaybackState;

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
    private static volatile AudioSpectrumCapture.Lease visualizer;
    private static volatile long generation;
    private static volatile long captureGeneration;
    private static volatile String requestedTrack = "";
    private static android.os.Handler worker;
    private static Runnable analysis;
    private static String track = "";
    /** Low-band onset envelope; no audio samples are retained. */
    private static final BpmSampleWindow envelope = new BpmSampleWindow(420, WINDOW_MS);
    private static volatile float energy;
    private static final float[] previousBands = new float[3];
    private static final float[] bandBaselines = new float[3];
    private static volatile long lastBeatAt;
    private static volatile long beatAt;
    private static volatile long beatCount;
    private static volatile int beatDirection = 1;
    private static volatile float learnedBeatMs;
    private static volatile boolean analysisReady;
    private static volatile float confidence;
    private static int lastPlaybackState = PlaybackState.STATE_NONE;
    private static long lastMediaPosition;
    private static long lastMediaPositionAt;
    private static float lastPlaybackSpeed = 1f;
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

    private static synchronized android.os.Handler worker() {
        if (worker == null) {
            android.os.HandlerThread thread = new android.os.HandlerThread("MCBpm",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            worker = new android.os.Handler(thread.getLooper());
        }
        return worker;
    }

    static boolean runningFor(String key) { return key != null && key.equals(requestedTrack); }
    static boolean capturing() { return visualizer != null && captureGeneration == generation && !requestedTrack.isEmpty(); }

    static void start(String key, final Callback cb) { start(key, 0, cb); }

    static void start(String key, int publishedBpm, final Callback cb) {
        stop();
        final long token = ++generation;
        requestedTrack = key == null ? "" : key;
        final String wanted = requestedTrack;
        worker().post(() -> startOnWorker(wanted, publishedBpm, cb, token));
    }

    private static void deliver(Callback cb, int bpm, long token) {
        Main.main().post(() -> {
            if (token == generation && !requestedTrack.isEmpty()) cb.onEstimated(bpm);
        });
    }

    private static void releaseCapture() {
        if (analysis != null) worker().removeCallbacks(analysis);
        analysis = null;
        AudioSpectrumCapture.Lease old = visualizer;
        visualizer = null;
        if (old != null) {
            try { old.release(); } catch (Throwable ignored) { }
        }
        envelope.clear();
        energy = 0f;
        lastBeatAt = beatAt = beatCount = 0L;
        beatDirection = 1;
        analysisReady = false;
        confidence = 0f;
    }

    private static void startOnWorker(String key, int publishedBpm, Callback cb, long token) {
        if (token != generation || key.isEmpty()) return;
        releaseCapture();
        track = key;
        Integer cached;
        synchronized (CACHE) { cached = CACHE.get(track); }
        int trustedBpm = validBpm(publishedBpm);
        AudioSpectrumCapture.Lease created = null;
        try {
            final AudioSpectrumCapture.Lease v = AudioSpectrumCapture.acquire((source, fft, rate) -> {
                if (!BpmCompanionPolicy.captureCallback(token, generation, source, visualizer)) return;
                if (android.os.Looper.myLooper() == worker().getLooper()) {
                    sample(fft, rate);
                } else if (fft != null) {
                    byte[] owned = fft.clone();
                    worker().post(() -> {
                        if (BpmCompanionPolicy.captureCallback(token, generation, source, visualizer)) sample(owned, rate);
                    });
                }
            });
            created = v;
            java.util.Arrays.fill(previousBands, 0f);
            java.util.Arrays.fill(bandBaselines, 0f);
            lastPlaybackState = PlaybackState.STATE_NONE;
            lastMediaPosition = 0L;
            lastMediaPositionAt = 0L;
            lastPlaybackSpeed = 1f;
            stableBpm = trustedBpm > 0 ? trustedBpm : cached == null ? 0 : cached;
            learnedBeatMs = stableBpm > 0 ? 60000f / stableBpm : 0f;
            challengerBpm = challengerObservations = 0;
            publishedTempo = trustedBpm > 0;
            java.util.Arrays.fill(tempoSupport, 0f);
            analysisReady = stableBpm > 0;
            resultDelivered = false;
            if (token != generation) { v.release(); return; }
            captureGeneration = token;
            visualizer = v;
            resultDelivered = stableBpm > 0;
            // Wake the renderer once capture starts, even before the first tempo is known.
            deliver(cb, stableBpm, token);
            if (!publishedTempo) {
                analysis = new Runnable() {
                    @Override public void run() {
                        if (token != generation || visualizer != v) return;
                        int bpm = estimate();
                        if (token != generation || visualizer != v) return;
                        if (bpm > 0) {
                            int previousStable = stableBpm;
                            addTempoObservation(bpm);
                            stableBpm = consistentTempo();
                            synchronized (CACHE) {
                                if (token != generation) return;
                                CACHE.put(key, stableBpm);
                            }
                            learnedBeatMs = 60000f / stableBpm;
                            analysisReady = true;
                            if (!resultDelivered || stableBpm != previousStable) {
                                resultDelivered = true;
                                deliver(cb, stableBpm, token);
                            }
                        }
                        worker().postDelayed(this, 5_000L);
                    }
                };
                worker().postDelayed(analysis, 5_000L);
            }
        } catch (Throwable t) {
            if (created != null && created != visualizer) {
                try { created.release(); } catch (Throwable ignored) { }
            }
            releaseCapture();
            Xp.w("BPM visualizer unavailable: " + t);
            if (token == generation) deliver(cb, 0, token);
        }
    }
    private static int validBpm(int bpm) {
        return bpm >= 40 && bpm <= 220 ? bpm : 0;
    }
    static boolean estimating() { return capturing() && !analysisReady; }
    static float confidence() { return capturing() ? confidence : 0f; }
    static float pulse() { return capturing() ? energy : 0f; }
    static void clearCache() {
        stop();
        synchronized (CACHE) { CACHE.clear(); }
    }
    static void stop() {
        if (requestedTrack.isEmpty() && visualizer == null) return;
        requestedTrack = "";
        final long token = ++generation;
        energy = 0f;
        lastBeatAt = beatAt = beatCount = 0L;
        beatDirection = 1;
        analysisReady = false;
        confidence = 0f;
        if (worker != null) worker().post(() -> {
            if (token == generation) releaseCapture();
        });
    }

    static void onPlaybackState(PlaybackState state) {
        final long token = generation;
        if (state == null || requestedTrack.isEmpty()) return;
        worker().post(() -> {
            if (token == generation) playbackOnWorker(state);
        });
    }

    private static void playbackOnWorker(PlaybackState state) {
        if (state == null) return;
        int next = state.getState();
        long now = android.os.SystemClock.elapsedRealtime();
        long position = Math.max(0L, state.getPosition());
        if (next == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0L) {
            position += (long) (Math.max(0L, now - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        boolean changed = lastPlaybackState != PlaybackState.STATE_NONE
                && next != lastPlaybackState;
        boolean jumped = lastMediaPositionAt != 0L
                && Math.abs(position - lastMediaPosition - (lastPlaybackState == PlaybackState.STATE_PLAYING
                        ? (long) ((now - lastMediaPositionAt) * lastPlaybackSpeed) : 0L)) > 1500L;
        if (changed || jumped) {
            envelope.clear();
            java.util.Arrays.fill(previousBands, 0f);
            java.util.Arrays.fill(bandBaselines, 0f);
            lastBeatAt = 0L;
            beatAt = 0L;
            beatCount = 0L;
            confidence = 0f;
            analysisReady = next != PlaybackState.STATE_PLAYING;
        }
        lastPlaybackState = next;
        lastMediaPosition = position;
        lastMediaPositionAt = now;
        lastPlaybackSpeed = state.getPlaybackSpeed();
    }
    private static void sample(byte[] fft, int rateMilliHz) {
        if (fft == null || fft.length < 4 || visualizer == null) return;
        int bins = fft.length / 2;
        float sampleRate = rateMilliHz > 0 ? rateMilliHz / 1000f : 44100f;
        float binWidth = sampleRate / (bins * 2f);
        int from = Math.max(1, (int) Math.ceil(LOW_HZ / binWidth));
        int to = Math.min(bins - 1, (int) Math.floor(HIGH_HZ / binWidth));
        if (from > to) return;

        float[] bands = new float[3];
        for (int bin = from; bin <= to; bin++) {
            int offset = bin * 2;
            float magnitude = (float) Math.hypot(fft[offset], fft[offset + 1]);
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
        long now = android.os.SystemClock.uptimeMillis();
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
        envelope.add(flux, now);
    }
    static long beatAt() { return capturing() ? beatAt : 0L; }
    static long beatCount() { return capturing() ? beatCount : 0L; }
    static int beatDirection() { return capturing() ? beatDirection : 1; }
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
    private static int estimate() {
        float[] onset = envelope.samples();
        return estimateTempo(onset, envelope.sampleRate());
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
