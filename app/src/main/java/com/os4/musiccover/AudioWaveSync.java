package com.os4.musiccover;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.util.List;

/** Estimates Bluetooth display delay without changing audio routing or delaying BPM analysis. */
final class AudioWaveSync {
    private static volatile AudioWaveSync instance;
    private static volatile boolean enabled = true;
    private static volatile int adjustment;
    private final AudioManager manager;
    private final HandlerThread thread = new HandlerThread("MCWaveTiming");
    private final Handler handler;
    private final SpectrumDelayBuffer<AudioSpectrumCapture.Lease[]> queue = new SpectrumDelayBuffer<>(64);
    private volatile boolean running = true;
    private volatile int delayMs, reportedMs;
    private volatile boolean bluetooth;
    private boolean scheduled;
    private final AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { requestRefresh(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { requestRefresh(); }
    };
    private final AudioManager.AudioPlaybackCallback playback = new AudioManager.AudioPlaybackCallback() {
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configurations) {
            long signature = 0;
            for (AudioPlaybackConfiguration configuration : configurations) {
                try {
                    if ((int) Xp.callMethod(configuration, "getPlayerState") == 2
                            && configuration.getAudioAttributes().getUsage() == AudioAttributes.USAGE_MEDIA)
                        signature += (int) Xp.callMethod(configuration, "getPlayerInterfaceId");
                } catch (Throwable ignored) { }
            }
            AudioSpectrumCapture.notePlaybackEpoch(signature);
            requestRefresh();
        }
    };

    private AudioWaveSync(Context context) {
        manager = context.getSystemService(AudioManager.class);
        thread.start(); handler = new Handler(thread.getLooper());
        try { manager.registerAudioDeviceCallback(devices, handler); } catch (Throwable ignored) { }
        try { manager.registerAudioPlaybackCallback(playback, handler); } catch (Throwable ignored) { }
        handler.post(refresh);
        handler.postDelayed(health, 1000L);
    }
    static void start(Context context) { if (instance == null && context != null) instance = new AudioWaveSync(context); }
    static void stop() {
        AudioWaveSync old = instance; instance = null;
        if (old != null) old.close();
    }
    static void configure(boolean value, int offset) {
        enabled = value; adjustment = Math.max(-250, Math.min(250, offset));
        AudioWaveSync current = instance;
        if (current != null) current.requestRefresh();
    }
    static boolean enqueue(Object source, byte[] data, int rate, AudioSpectrumCapture.Lease[] targets) {
        AudioWaveSync current = instance;
        if (current == null || !current.running || current.delayMs == 0) return false;
        synchronized (current.queue) {
            if (!current.running || current.delayMs == 0) return false;
            current.queue.offer(source, data, rate, SystemClock.uptimeMillis() + current.delayMs, targets);
            current.schedule();
        }
        return true;
    }
    private void schedule() {
        long due = queue.nextAt();
        if (!scheduled && due >= 0 && running) {
            scheduled = true;
            handler.postAtTime(pump, due);
        }
    }
    private final Runnable pump = new Runnable() {
        @Override public void run() {
            while (running) {
                SpectrumDelayBuffer.Frame<AudioSpectrumCapture.Lease[]> frame;
                synchronized (queue) {
                    frame = queue.poll(SystemClock.uptimeMillis());
                    if (frame == null) { scheduled = false; schedule(); return; }
                }
                // Never call the capture owner while holding the queue lock.
                AudioSpectrumCapture.deliverDelayed(frame.source, frame.data, frame.rate, frame.targets);
            }
        }
    };
    private final Runnable refresh = this::refreshRoute;
    private void requestRefresh() {
        if (!running) return;
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, 150L);
        handler.postDelayed(refresh, 1200L);
    }
    private void refreshRoute() {
        if (!running) return;
        boolean bt = false;
        int latency = 0;
        try {
            List<?> routes = (List<?>) Xp.callMethod(manager, "getAudioDevicesForAttributes", attributes);
            for (Object route : routes) if (isBluetooth((int) Xp.callMethod(route, "getType"))) bt = true;
            if (bt) latency = (int) Xp.callMethod(manager, "getOutputLatency", AudioManager.STREAM_MUSIC);
        } catch (Throwable ignored) { }
        bluetooth = bt; reportedMs = latency;
        int next = estimateDelay(bt && enabled, latency, adjustment);
        synchronized (queue) {
            if (Math.abs(next - delayMs) >= 10 || (next == 0) != (delayMs == 0)) {
                delayMs = next;
                queue.clear(); handler.removeCallbacks(pump); scheduled = false;
            }
        }
    }
    static boolean isBluetooth(int type) { return type == 7 || type == 8 || type == 26 || type == 27 || type == 30; }
    static int estimateDelay(boolean bluetooth, int reported, int adjustment) {
        if (!bluetooth) return 0;
        // Allow roughly one PCM half-window and one display frame for processing.
        // Android's HAL estimate is not an end-to-end measurement of the earphones.
        int estimate = reported > 0 && reported <= 2000 ? reported : 180;
        return Math.max(0, Math.min(1000, estimate - 32 + adjustment));
    }
    private final Runnable health = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { AudioSpectrumCapture.checkHealth(); }
            catch (Throwable t) { Xp.w("Playback spectrum health check failed: " + t); }
            handler.postDelayed(this, 1000L);
        }
    };
    private void close() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        synchronized (queue) { queue.clear(); scheduled = false; }
        try { manager.unregisterAudioDeviceCallback(devices); } catch (Throwable ignored) { }
        try { manager.unregisterAudioPlaybackCallback(playback); } catch (Throwable ignored) { }
        thread.quitSafely();
    }
    static String describe() {
        AudioWaveSync current = instance;
        return current == null ? "bluetoothSync inactive" : "bluetoothSync enabled=" + enabled
                + " routed=" + current.bluetooth + " reportedMs=" + current.reportedMs + " delayMs=" + current.delayMs;
    }
}
