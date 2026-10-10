package com.os4.musiccover;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;

/** System playback loopback that leaves speaker/headphone routing and volume untouched. */
final class InternalAudioCapture {
    interface Listener { void onFft(InternalAudioCapture source, byte[] fft, int rate); }
    private static final int RATE = 16000;
    private final AudioManager manager;
    private Object policy;
    private AudioRecord record;
    private volatile boolean running;

    private InternalAudioCapture(AudioManager manager) { this.manager = manager; }

    static InternalAudioCapture start(Context context, Listener listener) throws Exception {
        if (context == null) throw new IllegalStateException("No SystemUI context");
        InternalAudioCapture capture = new InternalAudioCapture(context.getSystemService(AudioManager.class));
        try {
            Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
            Object ruleBuilder = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder")
                    .getConstructor().newInstance();
            Xp.callMethod(ruleBuilder, "addRule", new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).build(), 1);
            // System-only playback capture is limited to 16 kHz mono by Android.
            Xp.callMethod(ruleBuilder, "allowPrivilegedPlaybackCapture", true);
            Object rule = Xp.callMethod(ruleBuilder, "build");
            AudioFormat format = new AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            Object mixBuilder = Class.forName("android.media.audiopolicy.AudioMix$Builder")
                    .getConstructor(ruleClass).newInstance(rule);
            Xp.callMethod(mixBuilder, "setFormat", format);
            Xp.callMethod(mixBuilder, "setRouteFlags", 3); // LOOP_BACK | RENDER keeps normal playback.
            Object mix = Xp.callMethod(mixBuilder, "build");
            Object policyBuilder = Class.forName("android.media.audiopolicy.AudioPolicy$Builder")
                    .getConstructor(Context.class).newInstance(context);
            Xp.callMethod(policyBuilder, "addMix", mix);
            capture.policy = Xp.callMethod(policyBuilder, "build");
            if ((int) Xp.callMethod(capture.manager, "registerAudioPolicy", capture.policy) != 0)
                throw new IllegalStateException("Playback policy registration failed");
            capture.record = (AudioRecord) Xp.callMethod(capture.policy, "createAudioRecordSink", mix);
            if (capture.record == null || capture.record.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("Playback sink unavailable");
            capture.record.startRecording();
            if (capture.record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Playback capture did not start");
            capture.running = true;
            new Thread(() -> capture.read(listener), "MCPlaybackSpectrum").start();
            return capture;
        } catch (Throwable t) {
            capture.dispose();
            throw new IllegalStateException("Internal playback capture unavailable", t);
        }
    }

    void stop() {
        running = false;
        try { record.stop(); } catch (Throwable ignored) { }
    }
    boolean isRunning() { return running; }

    private void read(Listener listener) {
        short[] pcm = new short[PcmSpectrum.SIZE];
        PcmSpectrum spectrum = new PcmSpectrum();
        try {
            int filled = 0;
            while (running) {
                int count = record.read(pcm, filled, pcm.length - filled, AudioRecord.READ_BLOCKING);
                if (!running || count < 0) break;
                if (count == 0) continue;
                filled += count;
                if (filled == pcm.length) {
                    listener.onFft(this, spectrum.transform(pcm), RATE * 1000);
                    filled = 0;
                }
            }
        } catch (Throwable t) { if (running) Xp.w("Playback spectrum read failed: " + t); }
        finally { running = false; dispose(); }
    }

    private void dispose() {
        if (record != null) { try { record.release(); } catch (Throwable ignored) { } }
        if (policy != null) {
            try { Xp.callMethod(manager, "unregisterAudioPolicy", policy); } catch (Throwable ignored) { }
        }
    }
}
