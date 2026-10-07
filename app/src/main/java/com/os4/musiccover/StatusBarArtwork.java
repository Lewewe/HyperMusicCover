package com.os4.musiccover;

import android.graphics.Bitmap;
import android.view.View;

import java.lang.ref.WeakReference;

/** Uses the OEM keyguard tint pipeline with the music backdrop's brightness. */
final class StatusBarArtwork {
    private static WeakReference<View> sView = new WeakReference<>(null);
    private static volatile double sLuminance = Double.NaN;
    private static volatile int sStripHeight;
    private static boolean sInstalled;

    private StatusBarArtwork() {}

    static void install(ClassLoader loader) {
        if (sInstalled) return;
        sInstalled = true;
        try {
            Class<?> cls = Xp.findClass(
                    "com.android.systemui.statusbar.phone.MiuiKeyguardStatusBarView", loader);
            cls.getDeclaredField("mLightLockScreenWallpaper");
            Xp.hookAll(cls, "updateIconsAndTextColors", chain -> {
                View view = (View) chain.getThisObject();
                remember(view);
                if (!ownsColors()) return chain.proceed();
                Object wallpaperLight = Xp.getObjectField(view, "mLightLockScreenWallpaper");
                Xp.setObjectField(view, "mLightLockScreenWallpaper",
                        StatusBarContrast.darkIcons(sLuminance));
                try {
                    // Native code updates signal icons, battery, carrier and alarm together.
                    return chain.proceed();
                } finally {
                    // Preserve the latest real wallpaper decision for exit/editor/AOD.
                    Xp.setObjectField(view, "mLightLockScreenWallpaper", wallpaperLight);
                }
            });
            Xp.hookAll(cls, "onAttachedToWindow", chain -> {
                Object result = chain.proceed();
                remember((View) chain.getThisObject());
                refresh();
                return result;
            });
            Xp.log("MCStatus: keyguard artwork contrast installed");
        } catch (Throwable t) {
            Xp.log("MCStatus: native keyguard tint unavailable: " + t);
        }
    }

    private static void remember(View view) {
        sView = new WeakReference<>(view);
        if (view.getHeight() > 0) sStripHeight = view.getHeight();
    }

    private static boolean ownsColors() {
        MiniPlayerScene scene = MiniPlayerScene.INSTANCE;
        return StatusBarContrast.ownsColors(Main.sCoverMode, Main.keyguardLocked(),
                scene.getBlocksMiniPlayer() || scene.getControlCenterIsActive()
                        || scene.getKeyguardGoingAway(),
                scene.getAodActive(), sLuminance);
    }

    /** Called on the existing composition worker, once per new composed artwork. */
    static void measure(Bitmap background) {
        try {
            int height = sStripHeight;
            if (height <= 0) height = Math.round(32f * Main.sAppCtx.getResources()
                    .getDisplayMetrics().density);
            height = Math.min(background.getHeight(), Math.max(1, height));
            double total = 0;
            int samples = 0;
            int dx = Math.max(1, background.getWidth() / 64);
            int dy = Math.max(1, height / 8);
            for (int y = 0; y < height; y += dy) {
                for (int x = dx / 2; x < background.getWidth(); x += dx) {
                    total += StatusBarContrast.luminance(background.getPixel(x, y));
                    samples++;
                }
            }
            sLuminance = samples == 0 ? Double.NaN : total / samples;
        } catch (Throwable t) {
            sLuminance = Double.NaN;
            Xp.log("MCStatus: backdrop sample unavailable: " + t);
        }
        refresh();
    }

    private static final Runnable UPDATE = () -> {
        View view = sView.get();
        if (view == null || !view.isAttachedToWindow()) return;
        try {
            Xp.callMethod(view, "updateIconsAndTextColors");
        } catch (Throwable t) {
            Xp.log("MCStatus: keyguard tint refresh failed: " + t);
        }
    };

    /** Coalesced event updates; no frame callback or idle polling. */
    static void refresh() {
        Main.main().removeCallbacks(UPDATE);
        Main.main().post(UPDATE);
    }

    static String describe() {
        View view = sView.get();
        String state = "view=" + (view != null) + " luma=" + sLuminance
                + " owns=" + ownsColors() + " dark=" + StatusBarContrast.darkIcons(sLuminance);
        if (view != null) {
            try {
                state += " nativeWallpaperLight=" + Xp.getObjectField(view, "mLightLockScreenWallpaper")
                        + " appliedDark=" + Xp.getObjectField(view, "mIsDark");
            } catch (Throwable ignored) {}
        }
        return state;
    }
}
