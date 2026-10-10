package com.os4.musiccover;

import android.media.audiofx.Visualizer;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import java.util.ArrayList;

/** One playback capture shared by the BPM companion and all live music waves. */
final class AudioSpectrumCapture {
    interface Listener { void onFft(Lease source, byte[] fft, int rate); }
    private static final ArrayList<Lease> clients = new ArrayList<>();
    private static Visualizer effect;
    private static InternalAudioCapture playback;
    private static int fftPeak;
    private static long nonzeroSamples;
    private static long lastFftMs, lastSignalMs, lastRestartMs, epoch, lastSignalEpoch;
    private static long playbackSignature = Long.MIN_VALUE;
    private static int recoveries;
    private static boolean activeMedia;
    private static MediaController stateController;
    private static PlaybackState liveState;
    private static long stateCheckedAt;

    /** Native island/media data can retain PAUSED across an automatic track change. */
    static boolean mediaPlaying(String packageName, boolean fallback) {
        MediaController controller = Main.miniPlayerSession();
        if (controller == null || (packageName != null && !packageName.equals(controller.getPackageName())))
            return fallback;
        long now = SystemClock.uptimeMillis();
        // All display consumers call this on the UI thread. Avoid a Binder query per frame.
        if (controller != stateController || now - stateCheckedAt >= 200L) {
            stateController = controller;
            stateCheckedAt = now;
            try { liveState = controller.getPlaybackState(); }
            catch (Throwable ignored) { liveState = null; }
        }
        return liveState == null ? fallback : liveState.getState() == PlaybackState.STATE_PLAYING;
    }

    static final class Lease {
        private final Listener listener;
        private final boolean visual;
        private volatile boolean closed;
        private Lease(boolean visual, Listener listener) { this.visual = visual; this.listener = listener; }
        void release() {
            synchronized (AudioSpectrumCapture.class) {
                if (closed) return;
                closed = true;
                clients.remove(this);
                if (clients.stream().noneMatch(client -> client.visual)) AudioWaveSync.stop();
                if (clients.isEmpty()) {
                    InternalAudioCapture oldPlayback = playback;
                    playback = null;
                    if (oldPlayback != null) oldPlayback.stop();
                    Visualizer old = effect;
                    effect = null;
                    if (old != null) {
                        try { old.setEnabled(false); } catch (Throwable ignored) { }
                        try { old.release(); } catch (Throwable ignored) { }
                    }
                    fftPeak = 0;
                }
            }
        }
    }

    /** Call off the UI thread; samples are ephemeral and never recorded to disk. */
    static synchronized Lease acquire(Listener listener) {
        return acquire(false, listener);
    }

    static synchronized Lease acquire(boolean visual, Listener listener) {
        if (effect == null && playback == null) {
            try { playback = InternalAudioCapture.start(Main.appContext(), AudioSpectrumCapture::dispatch); }
            catch (Throwable t) { Xp.w("Using output-mix spectrum fallback: " + t); }
            if (playback == null) openOutputMix();
            lastFftMs = lastSignalMs = SystemClock.uptimeMillis();
            playbackSignature = Long.MIN_VALUE;
        }
        Lease client = new Lease(visual, listener);
        clients.add(client);
        if (visual) AudioWaveSync.start(Main.appContext());
        return client;
    }

    private static void openOutputMix() {
        Visualizer created = new Visualizer(0);
        try {
            check(created.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED));
            check(created.setCaptureSize(Visualizer.getCaptureSizeRange()[1]));
            check(created.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                public void onWaveFormDataCapture(Visualizer source, byte[] data, int rate) { }
                public void onFftDataCapture(Visualizer source, byte[] data, int rate) { dispatch(source, data, rate); }
            }, Visualizer.getMaxCaptureRate(), false, true));
            effect = created;
            check(created.setEnabled(true));
        } catch (Throwable t) {
            effect = null;
            created.release();
            throw t;
        }
    }

    private static void dispatch(Object source, byte[] data, int rate) {
        Lease[] targets;
        boolean internal;
        synchronized (AudioSpectrumCapture.class) {
            if (source != effect && source != playback) return;
            internal = source == playback;
            lastFftMs = SystemClock.uptimeMillis();
            fftPeak = 0;
            for (int bin = 2; bin < data.length; bin++) fftPeak = Math.max(fftPeak, Math.abs((int) data[bin]));
            if (fftPeak > 0) {
                nonzeroSamples++; lastSignalMs = lastFftMs; lastSignalEpoch = epoch;
            }
            targets = clients.toArray(new Lease[0]);
        }
        boolean delayed = internal && AudioWaveSync.enqueue(source, data, rate, targets);
        for (Lease client : targets) if (!client.closed && (!delayed || !client.visual)) {
            try { client.listener.onFft(client, data, rate); }
            catch (Throwable t) { Xp.w("Audio spectrum consumer failed: " + t); }
        }
    }

    static void deliverDelayed(Object source, byte[] data, int rate, Lease[] targets) {
        synchronized (AudioSpectrumCapture.class) { if (source != playback) return; }
        for (Lease client : targets) if (!client.closed && client.visual) {
            try { client.listener.onFft(client, data, rate); }
            catch (Throwable t) { Xp.w("Delayed spectrum consumer failed: " + t); }
        }
    }

    static synchronized void notePlaybackEpoch(long signature) {
        if (playbackSignature != Long.MIN_VALUE && playbackSignature != signature) epoch++;
        playbackSignature = signature;
        activeMedia = signature != 0;
    }

    static synchronized void checkHealth() {
        if (clients.isEmpty() || playback == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastRestartMs < 3000L) return;
        boolean stalled = !playback.isRunning() || now - lastFftMs > 1500L;
        boolean lostAfterTransition = epoch != lastSignalEpoch && now - lastSignalMs > 2500L;
        if (!stalled && !lostAfterTransition) return;
        if (!stalled) {
            MediaController controller = Main.miniPlayerSession();
            PlaybackState state = controller == null ? null : controller.getPlaybackState();
            if (!activeMedia && (state == null || state.getState() != PlaybackState.STATE_PLAYING)) return;
        }
        lastRestartMs = now;
        lastSignalEpoch = epoch; // One recovery per playback transition, not repeated retries during silence.
        InternalAudioCapture old = playback;
        playback = null; old.stop();
        try { playback = InternalAudioCapture.start(Main.appContext(), AudioSpectrumCapture::dispatch); recoveries++; }
        catch (Throwable t) {
            Xp.w("Playback capture recovery failed: " + t);
            try { openOutputMix(); } catch (Throwable fallback) { Xp.w("Spectrum fallback failed: " + fallback); }
        }
        lastFftMs = lastSignalMs = now;
    }

    static synchronized String describe() {
        return "audioCapture source=" + (playback != null ? "internal-playback" : effect != null ? "output-mix" : "none")
                + " fftPeak=" + fftPeak + " nonzeroSamples=" + nonzeroSamples + " clients=" + clients.size()
                + " recoveries=" + recoveries + " session=" + (stateController == null ? "none" : stateController.getPackageName())
                + " state=" + (liveState == null ? -1 : liveState.getState()) + "\n" + AudioWaveSync.describe();
    }

    private static void check(int status) {
        if (status != Visualizer.SUCCESS) throw new IllegalStateException("Audio spectrum status " + status);
    }
}
