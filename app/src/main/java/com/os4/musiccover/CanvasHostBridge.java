package com.os4.musiccover;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Optional extension handshake; without a ready Canvas the normal cover path is untouched. */
final class CanvasHostBridge {
    private static String activeTrack = "";
    private static boolean autoCollapsed, verified, canvasReady;
    private static long lastFrame;
    private static Context sceneContext;
    private static Boolean lastScene;
    private static Boolean lastVisible;
    private static boolean clockOwned;
    private static long resumeUntil;
    private static boolean backdropHeld;
    private static String backdropTrack = "";
    private static boolean fallbackBackdrop;
    private static boolean backdropAllowed = true;
    private static boolean showInPill;
    private static boolean frozenInAod;
    static boolean keepsClockInAod() {
        return !MiniPlayerRuntime.canvasPlayerInPill()
                && (canvasReady || frozenInAod || fallbackBackdrop);
    }
    static boolean hasBackgroundScene() { return canvasReady || fallbackBackdrop; }
    static void userSelectedScene() { autoCollapsed = false; }
    static boolean keepPaletteBackdrop() {
        if (MiniPlayerRuntime.canvasPlayerInPill())
            return showInPill && canvasReady && backdropAllowed && Main.screenOnCached();
        if (frozenInAod && !Main.screenOnCached()) return true;
        // Static artwork can remain in AOD even though the video surface is removed.
        if (fallbackBackdrop && (backdropAllowed || !Main.screenOnCached())) return true;
        return Main.screenOnCached() && backdropAllowed && (active() || nativeClockScene());
    }
    static void backdropRetained() {
        backdropHeld = true;
        backdropTrack = Main.canvasBackdropKey();
    }
    static boolean backgroundOnly() {
        return active() || nativeClockScene() || fallbackBackdrop;
    }
    static boolean nativeClockScene() { return clockOwned && !Main.coverModeOn(); }
    static void waking() {
        if (clockOwned && (!activeTrack.isEmpty() || fallbackBackdrop)) resumeUntil = android.os.SystemClock.uptimeMillis() + 5000L;
    }
    private static void syncClock() {
        // Screen-off removes the video, but the clock's AOD flight must finish on its own.
        // The artwork fallback is still the same expanded player scene after Canvas ends.
        boolean keep = !Main.coverModeOn() && !MiniPlayerRuntime.canvasPlayerInPill()
                && (canvasReady && backdropAllowed && Main.screenOnCached()
                || fallbackBackdrop && backdropAllowed && Main.screenOnCached()
                || clockOwned && (!Main.screenOnCached()
                    || android.os.SystemClock.uptimeMillis() < resumeUntil));
        if (clockOwned == keep) return;
        // Keep the outgoing owner until the cover has taken the clock in this same transition.
        if (!keep && Main.coverModeOn() && !LockHold.heldBy(LockHold.Owner.COVER)) return;
        clockOwned = keep;
        ClockCollapse.refreshArtworkSize();
        if (keep) LockHold.take(LockHold.Owner.CANVAS, Main.screenOnCached(), "canvas");
        else LockHold.give(LockHold.Owner.CANVAS, Main.screenOnCached());
    }
    static void sceneChanged() {
        syncClock();
        if (!canvasReady || sceneContext == null) return;
        boolean next = Main.coverModeOn() && (!LockLyrics.wantsCompactArtwork() || LockLyrics.wantsWindow());
        boolean visible = showInPill || !MiniPlayerRuntime.canvasPlayerInPill();
        if (lastScene != null && lastScene == next && lastVisible != null && lastVisible == visible) return;
        lastScene = next;
        lastVisible = visible;
        sceneContext.sendBroadcast(new Intent("com.os4.canvas.SCENE_CHANGED").setPackage("com.android.systemui"));
    }
    static boolean active() { return canvasReady && !MiniPlayerRuntime.canvasPlayerInPill() && Main.screenOnCached() && android.os.SystemClock.uptimeMillis() - lastFrame < 2500L; }
    static Bundle frame(Context context, Intent intent) {
        boolean ready = intent.getBooleanExtra("ready", false);
        String track = intent.getStringExtra("track");
        Bundle out = new Bundle();
        try {
            if (!verified) {
                verified = context.getPackageManager().checkSignatures("com.yzc26623.HyperMusicCoverEnhanced", "com.yzc26623.HyperCanvas") == android.content.pm.PackageManager.SIGNATURE_MATCH;
                if (!verified) return out;
            }
            if (ready && (Main.miniPlayerSession() == null || !"com.spotify.music".equals(Main.miniPlayerSession().getPackageName()))) return out;
            sceneContext = context.getApplicationContext();
            boolean wasReady = canvasReady;
            showInPill = intent.getBooleanExtra("showInPill", false);
            frozenInAod = intent.getBooleanExtra("frozenInAod", false);
            // A track without video retains the normal artwork backdrop, not a tappable cover.
            backdropAllowed = intent.getBooleanExtra("backdropAllowed", true);
            if (ready) fallbackBackdrop = false;
            else if (intent.getBooleanExtra("artworkFallback", false))
                fallbackBackdrop = wasReady || fallbackBackdrop || backdropHeld;
            else if (!intent.getBooleanExtra("temporary", false)) fallbackBackdrop = false;
            canvasReady = ready;
            if (ready || !intent.getBooleanExtra("temporary", false)) resumeUntil = 0L;
            else if (clockOwned && backdropAllowed && Main.screenOnCached() && resumeUntil == 0L) {
                // A buffering session can disappear briefly between adjacent tracks.
                resumeUntil = android.os.SystemClock.uptimeMillis() + 1500L;
            }
            lastFrame = android.os.SystemClock.uptimeMillis();
            if (wasReady != canvasReady) {
                Main.refreshCanvasControls();
                Main.recolorClock();
            }
            if (ready && track != null && track.startsWith("spotify:track:")) {
                if (!track.equals(activeTrack)) {
                    activeTrack = track;
                    if (Main.coverModeOn() && !LockLyrics.wantsCompactArtwork()) {
                        Main.miniPlayerLeaveCover();
                        // Automatic Canvas entry is a player scene, not a pill-to-cover request.
                        MiniPlayerRuntime.forgetRestoreScene();
                        autoCollapsed = true;
                    }
                } else if (Main.coverModeOn() && !LockLyrics.wantsCompactArtwork()) autoCollapsed = false;
            } else if (!intent.getBooleanExtra("temporary", false)) {
                activeTrack = "";
                if (autoCollapsed && !fallbackBackdrop && !Main.coverModeOn() && Main.screenOn()
                        && !MiniPlayerRuntime.canvasPlayerInPill()) Main.miniPlayerEnterCover();
                autoCollapsed = false;
            }
            sceneChanged();
            String backdropIdentity = Main.canvasBackdropKey();
            if ((ready || fallbackBackdrop)
                    && (backdropAllowed && Main.screenOnCached()
                        || fallbackBackdrop && !Main.screenOnCached()) && !Main.coverModeOn()
                    && (!MiniPlayerRuntime.canvasPlayerInPill() || ready && showInPill)
                    && (!backdropHeld || !backdropIdentity.equals(backdropTrack))) {
                // The glass clock samples the wallpaper behind the video, so retain music there.
                if (Main.prepareCanvasBackdrop()) {
                    backdropRetained();
                    backdropTrack = backdropIdentity;
                    CoverPush.pushArtAsync(true, false);
                }
            } else if (backdropHeld && !keepPaletteBackdrop()
                    && (!backdropAllowed || !Main.screenOnCached()
                    || MiniPlayerRuntime.canvasPlayerInPill()
                    || !ready && !fallbackBackdrop
                    && (!Main.screenOnCached() || !intent.getBooleanExtra("temporary", false)))) {
                backdropHeld = false;
                backdropTrack = "";
                if (!Main.coverModeOn()) CoverPush.pushArtAsync(false, false);
            }
            out.putBoolean("canvasBouncer", Main.bouncerShown());
            out.putBoolean("canvasVisible", showInPill || !MiniPlayerRuntime.canvasPlayerInPill());
            out.putBoolean("canvasLarge", Main.coverModeOn() && (!LockLyrics.wantsCompactArtwork() || LockLyrics.wantsWindow()));
        } catch (Throwable t) { Xp.w("Canvas extension handshake failed: " + t); }
        return out;
    }
}
