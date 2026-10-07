package com.os4.musiccover;

import android.app.Application;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;

/** Opt-in Java AudioTrack scalar capture. No PCM is queued, copied, persisted or transmitted. */
public final class PlaybackPcmCapture {
    private PlaybackPcmCapture() {}

    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String CONTROL = "com.os4.musiccover.PCM_LEASE";
    private static final String ENERGY = "com.os4.musiccover.PCM_ENERGY";
    private static final String CONTROL_PERMISSION = "android.permission.STATUS_BAR";
    private static final long PERIOD_MS = 75;
    private static final String[] PLAYERS = {
            "com.apple.android.music", // Already scoped for AppleLyrics.
            "com.spotify.music", // Already in the module's scope list.
            "com.netease.cloudmusic", "com.tencent.qqmusic", "com.kugou.android",
            "com.luna.music", "com.miui.player"
    };
    private static final PlaybackAudioState STATE = new PlaybackAudioState();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean CAPTURING = new AtomicBoolean();
    private static final ThreadLocal<Boolean> IN_WRITE = new ThreadLocal<>();
    private static final AtomicLong NEXT_SAMPLE = new AtomicLong();
    private static final AtomicLong IDS = new AtomicLong();
    private static Context context;
    private static Handler worker;
    private static Handler mainHandler;
    private static volatile Runnable onSample;
    private static final AtomicBoolean NOTIFY_PENDING = new AtomicBoolean();
    private static final Runnable NOTIFY_SAMPLE = new Runnable() {
        @Override public void run() {
            NOTIFY_PENDING.set(false);
            Runnable callback = onSample;
            if (callback != null) callback.run();
        }
    };
    private static Bundle sendOptions;
    private static String playerPackage;
    private static int systemUiUid = -1;
    private static boolean playerReady;
    private static long controlRevision;
    private static volatile long playerSession;
    private static volatile long playerUntil;
    // Seqlock mailbox: one producer at a time, no locks, queue operations or allocations in write.
    private static volatile long mailboxVersion;
    private static volatile long mailboxSession, mailboxAt, mailboxEnergy;
    private static long sentVersion;
    // SystemUI fields are accessed under the class monitor, not from an audio write.
    private static String selected;
    private static int selectedUid = -1;
    private static Signature[] selectedSigners;
    private static long selectedSession, selectedUntil, lastLeaseSent;

    public static boolean supports(String pkg) {
        for (String player : PLAYERS) if (player.equals(pkg)) return true;
        return false;
    }

    /** Called only from Main.onPackageLoaded; never hooks SystemUI AudioTrack writes. */
    static void install(String pkg) {
        if (!supports(pkg) || !INSTALLED.compareAndSet(false, true)) return;
        try {
            Xp.hook(Application.class.getDeclaredMethod("attach", Context.class),
                    (XposedInterface.Hooker) chain -> {
                        Object result = chain.proceed();
                        try {
                            Context ctx = (Context) chain.getArgs().get(0);
                            if (pkg.equals(ctx.getPackageName())) initializePlayer(ctx, pkg);
                        } catch (Throwable ignored) {
                            // Instrumentation must never change Application.attach's outcome.
                        }
                        return result;
                    });
        } catch (Throwable ignored) {
            // Unsupported framework: inactive, with original playback untouched.
        }
    }

    /**
     * Renderer heartbeat: call while awake, visible, playing and user-enabled (e.g. every 500ms).
     * Call with enabled=false immediately on pause, hide, AOD or detach. No autonomous renewal:
     * if the renderer disappears, both local energy and player capture expire within 1500ms.
     * Returns false for unsupported packages, unavailable/unauthenticated IPC, or release.
     */
    public static synchronized boolean setActive(Context ctx, String activePackage, boolean enabled) {
        try {
            if (!enabled || !supports(activePackage)) {
                release();
                return false;
            }
            if (!initializeSystemUi(ctx)) {
                release();
                return false;
            }
            long now = SystemClock.elapsedRealtime();
            if (!activePackage.equals(selected) || selectedUntil <= now) {
                release();
                PackageInfo info = signedPackage(context, activePackage);
                if (info.applicationInfo == null || info.applicationInfo.uid == Process.myUid()) return false;
                selected = activePackage;
                selectedUid = info.applicationInfo.uid;
                selectedSigners = info.signingInfo.getApkContentsSigners();
                selectedSession = nextId();
            }
            selectedUntil = now + PlaybackAudioState.LEASE_MS;
            STATE.activate(selectedSession, selectedUntil, now);
            if (lastLeaseSent == 0 || now - lastLeaseSent >= 500) {
                sendLease(selected, selectedSession, selectedUntil);
                lastLeaseSent = now;
            }
            return true;
        } catch (Throwable ignored) {
            release();
            return false;
        }
    }

    public static void stop(Context ctx) {
        setActive(ctx, null, false);
    }

    /** Renderer wakeup, delivered/coalesced on main; clear on detach or capture release. */
    public static void setOnSample(Runnable listener) {
        onSample = listener;
    }

    /** Packed scalar snapshot; decode using PlaybackAudioState.rms/peak. */
    public static long sample() {
        return STATE.sample(SystemClock.elapsedRealtime());
    }

    public static float currentEnergy() {
        return STATE.currentEnergy(SystemClock.elapsedRealtime());
    }

    /** True for fresh accepted PCM, including silence; activation alone is not enough. */
    public static boolean hasFreshSample() {
        return STATE.hasFreshSample(SystemClock.elapsedRealtime());
    }

    private static long nextId() {
        long candidate = SystemClock.elapsedRealtimeNanos();
        return IDS.updateAndGet(previous -> Math.max(previous + 1, candidate));
    }

    private static PackageInfo signedPackage(Context ctx, String pkg) throws Exception {
        PackageInfo info = ctx.getPackageManager().getPackageInfo(pkg,
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (info.signingInfo == null || info.signingInfo.getApkContentsSigners().length == 0)
            throw new SecurityException("No signing identity");
        return info;
    }

    private static boolean sender(BroadcastReceiver receiver, Intent intent, String pkg, int uid) {
        // Never trust Intent extras or Binder.getCallingUid in an asynchronous broadcast.
        return pkg.equals(intent.getPackage()) && uid >= 0
                && receiver.getSentFromUid() == uid;
    }

    private static void createWorker() {
        HandlerThread thread = new HandlerThread("MusicCoverPcm", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
        sendOptions = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
    }

    private static synchronized boolean initializeSystemUi(Context ctx) throws Exception {
        if (ctx == null || !SYSTEM_UI.equals(ctx.getPackageName())) return false;
        if (context != null) return SYSTEM_UI.equals(context.getPackageName());
        PackageManager pm = ctx.getPackageManager();
        if (pm.getPackageUid(SYSTEM_UI, 0) != Process.myUid()
                || pm.checkSignatures(SYSTEM_UI, "android") != PackageManager.SIGNATURE_MATCH
                || ctx.checkSelfPermission(CONTROL_PERMISSION) != PackageManager.PERMISSION_GRANTED)
            return false;
        Context app = ctx.getApplicationContext();
        context = app != null ? app : ctx;
        createWorker();
        mainHandler = new Handler(Looper.getMainLooper());
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    try {
                        final Context sourceContext;
                        final String source;
                        final int uid;
                        final Signature[] signers;
                        final long session, until;
                        synchronized (PlaybackPcmCapture.class) {
                            long now = SystemClock.elapsedRealtime();
                            if (!ENERGY.equals(intent.getAction()) || selected == null
                                    || !sender(this, intent, SYSTEM_UI, selectedUid)
                                    || !selected.equals(getSentFromPackage())
                                    || selectedUntil <= now
                                    || intent.getLongExtra("session", 0) != selectedSession) return;
                            sourceContext = context;
                            source = selected;
                            uid = selectedUid;
                            signers = selectedSigners;
                            session = selectedSession;
                            until = selectedUntil;
                        }
                        // PackageManager can block on Binder. Never hold the renderer's monitor
                        // across this worker-side verification, even for an authenticated sample.
                        PackageInfo info = signedPackage(sourceContext, source);
                        if (info.applicationInfo == null || info.applicationInfo.uid != uid
                                || !java.util.Arrays.equals(signers,
                                info.signingInfo.getApkContentsSigners())) return;
                        synchronized (PlaybackPcmCapture.class) {
                            long now = SystemClock.elapsedRealtime();
                            // Stop, switching sources or renewing a lease may have raced the lookup.
                            if (context != sourceContext || !source.equals(selected)
                                    || selectedUid != uid || selectedSigners != signers
                                    || selectedSession != session || selectedUntil != until
                                    || until <= now) return;
                            if (STATE.accept(session, intent.getLongExtra("at", 0),
                                    intent.getLongExtra("energy", 0), now) && onSample != null
                                    && NOTIFY_PENDING.compareAndSet(false, true)) {
                                mainHandler.post(NOTIFY_SAMPLE);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }, new IntentFilter(ENERGY), null, worker, Context.RECEIVER_EXPORTED);
            return true;
        } catch (Throwable failure) {
            context = null;
            worker.getLooper().quitSafely();
            worker = null;
            throw failure;
        }
    }

    private static synchronized void release() {
        String old = selected;
        long token = selectedSession;
        selected = null;
        selectedUid = -1;
        selectedSigners = null;
        selectedSession = selectedUntil = lastLeaseSent = 0;
        STATE.clear();
        if (old != null) {
            try { sendLease(old, token, 0); } catch (Throwable ignored) {}
        }
    }

    private static void sendLease(String pkg, long session, long until) {
        Intent intent = new Intent(CONTROL).setPackage(pkg)
                .putExtra("session", session).putExtra("until", until)
                .putExtra("revision", nextId());
        // Control is permission-gated at the player receiver. Data is gated to this privileged receiver.
        context.sendBroadcast(intent, null, sendOptions);
    }

    private static synchronized void initializePlayer(Context ctx, String pkg) throws Exception {
        if (playerReady) return;
        PackageManager pm = ctx.getPackageManager();
        PackageInfo info = signedPackage(ctx, pkg);
        if (info.applicationInfo == null || info.applicationInfo.uid != Process.myUid()
                || pm.getPackageUid(SYSTEM_UI, 0) == Process.myUid()
                || pm.checkSignatures(SYSTEM_UI, "android") != PackageManager.SIGNATURE_MATCH) return;
        systemUiUid = pm.getPackageUid(SYSTEM_UI, 0);
        Context app = ctx.getApplicationContext();
        context = app != null ? app : ctx;
        playerPackage = pkg;
        createWorker();
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                try {
                    long now = SystemClock.elapsedRealtime();
                    long revision = intent.getLongExtra("revision", 0);
                    long session = intent.getLongExtra("session", 0);
                    long until = intent.getLongExtra("until", 0);
                    if (!CONTROL.equals(intent.getAction())
                            || !sender(this, intent, playerPackage, systemUiUid)
                            || !SYSTEM_UI.equals(getSentFromPackage())
                            || revision <= controlRevision || session <= 0
                            || (until != 0 && (until <= now || until - now > PlaybackAudioState.LEASE_MS))) return;
                    controlRevision = revision;
                    // Publish expiry last; a write also verifies session/expiry after sampling.
                    playerUntil = 0;
                    playerSession = session;
                    playerUntil = until;
                    worker.removeCallbacks(PUBLISH);
                    if (until > now) worker.post(PUBLISH);
                } catch (Throwable ignored) {}
            }
        }, new IntentFilter(CONTROL), CONTROL_PERMISSION, worker, Context.RECEIVER_EXPORTED);
        playerReady = true;
        installWrites();
    }

    private static final Runnable PUBLISH = new Runnable() {
        @Override public void run() {
            try {
                long now = SystemClock.elapsedRealtime();
                long version = mailboxVersion;
                long token = mailboxSession, at = mailboxAt, energy = mailboxEnergy;
                if ((version & 1) == 0 && version != sentVersion && version == mailboxVersion
                        && playerUntil > now && token == playerSession
                        && at > 0 && at <= now && now - at <= PlaybackAudioState.STALE_MS) {
                    sentVersion = version;
                    Intent intent = new Intent(ENERGY).setPackage(SYSTEM_UI)
                            .putExtra("session", token).putExtra("at", at).putExtra("energy", energy);
                    context.sendBroadcast(intent, CONTROL_PERMISSION, sendOptions);
                }
            } catch (Throwable ignored) {
            } finally {
                if (playerUntil > SystemClock.elapsedRealtime()) worker.postDelayed(this, PERIOD_MS);
            }
        }
    };

    private static void installWrites() {
        for (Method method : AudioTrack.class.getDeclaredMethods()) {
            if (!method.getName().equals("write") || method.getReturnType() != int.class) continue;
            Class<?>[] params = method.getParameterTypes();
            boolean buffer = params.length >= 3 && params[0] == ByteBuffer.class
                    && params[1] == int.class && params[2] == int.class
                    && (params.length == 3 || (params.length == 4 && params[3] == long.class));
            boolean array = (params.length == 3 || params.length == 4)
                    && (params[0] == byte[].class || params[0] == short[].class || params[0] == float[].class)
                    && params[1] == int.class && params[2] == int.class
                    && (params.length == 3 || params[3] == int.class);
            if (!buffer && !array) continue;
            try {
                Xp.hook(method, (XposedInterface.Hooker) chain -> {
                    boolean leased = false;
                    try { leased = playerUntil > SystemClock.elapsedRealtime(); } catch (Throwable ignored) {}
                    if (!leased) return chain.proceed();
                    // Guard the ORIGINAL call too: public overloads may delegate to other hooked writes.
                    boolean owner = false;
                    int offset = -1, requested = 0;
                    Object input = null;
                    try {
                        if (!Boolean.TRUE.equals(IN_WRITE.get())) {
                            IN_WRITE.set(Boolean.TRUE);
                            owner = true;
                            if (playerUntil > SystemClock.elapsedRealtime()) {
                                List<Object> args = chain.getArgs();
                                input = args.get(0);
                                offset = buffer ? ((ByteBuffer) input).position() : (Integer) args.get(1);
                                requested = (Integer) args.get(buffer ? 1 : 2);
                            }
                        }
                    } catch (Throwable ignored) {}
                    try {
                        Object result = chain.proceed(); // Exactly once; original exceptions propagate.
                        try {
                            if (owner && input != null && result instanceof Integer) {
                                int accepted = (Integer) result;
                                if (accepted > 0 && accepted <= requested)
                                    capture((AudioTrack) chain.getThisObject(), input, offset, accepted);
                            }
                        } catch (Throwable ignored) {
                            // Callback failure must not turn a successful write into a failed one.
                        }
                        return result;
                    } finally {
                        if (owner) {
                            try { IN_WRITE.set(Boolean.FALSE); } catch (Throwable ignored) {}
                        }
                    }
                });
            } catch (Throwable ignored) {
                // One unavailable overload does not disable the others.
            }
        }
    }

    private static void capture(AudioTrack track, Object input, int offset, int accepted) {
        long now = SystemClock.elapsedRealtime();
        long until = playerUntil, token = playerSession;
        // Playback eligibility belongs to the renderer's lease. getPlayState() takes an Android
        // monitor, so do not query it (or audio attributes/format objects) on this write path.
        if (until <= now || token <= 0 || track.isOffloadedPlayback()
                || !CAPTURING.compareAndSet(false, true)) return;
        try {
            if (now < NEXT_SAMPLE.get()) return;
            int encoding = track.getAudioFormat();
            // Invalid/incomplete PCM must not masquerade as fresh silence or throttle valid writes.
            if (!PlaybackAudioState.canMeasure(input, offset, accepted, encoding)) return;
            long next = NEXT_SAMPLE.get();
            if (now < next || !NEXT_SAMPLE.compareAndSet(next, now + PERIOD_MS)) return;
            long energy = PlaybackAudioState.measure(input, offset, accepted, encoding);
            long after = SystemClock.elapsedRealtime();
            if (playerUntil != until || playerSession != token || until <= after) return;
            mailboxVersion++;
            mailboxSession = token;
            mailboxAt = after;
            mailboxEnergy = energy;
            mailboxVersion++;
        } finally {
            CAPTURING.set(false);
        }
    }
}
