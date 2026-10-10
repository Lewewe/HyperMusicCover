package com.os4.musiccover;

import android.content.Intent;
import android.os.SystemClock;

/** Keep the artwork itself cached while the compact player shows the normal wallpaper. */
final class CoverBackdrop {
    private static volatile long decision;
    private static android.view.View dimLayer;
    private static float dimTarget = Float.NaN;
    private static boolean nativeWallpaper;

    static boolean hidden() {
        return Main.sHidePlayerBackground;
    }

    static boolean nativeWallpaperScene() {
        return Main.coverModeOn() && hidden() && LockLyrics.compactWithoutLyrics()
                && !LockLyrics.hasLyrics();
    }

    static void sync(boolean force) {
        syncClock();
        updateDim();
        boolean wallpaper = nativeWallpaperScene();
        if (force || wallpaper != nativeWallpaper) {
            nativeWallpaper = wallpaper;
            StatusBarArtwork.refresh();
        }
        boolean next = hidden();
        long previous = decision;
        if (!force && previous != 0L && ((previous & 1L) != 0L) == next) return;
        long sequence = Math.max((previous >>> 1) + 1L, SystemClock.uptimeMillis());
        decision = sequence << 1 | (next ? 1L : 0L);
        Intent out = CoverPush.wallpaperIntent("backdrop");
        putState(out);
        if (Main.sAppCtx != null) ProbeGuard.send(Main.sAppCtx, out);
        if (Main.sVideoWallpaper && Main.sCover != null)
            Main.sCover.setVisibility(next ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
    }

    /** A lyricless compact player leaves the native wallpaper and clock unobstructed. */
    private static void syncClock() {
        if (!Main.coverModeOn()) return;
        boolean nativeScene = nativeWallpaperScene();
        boolean held = LockHold.heldBy(LockHold.Owner.COVER);
        if (nativeScene && held) {
            LockHold.give(LockHold.Owner.COVER, Main.screenOnCached());
        } else if (!nativeScene && !held) {
            LockHold.take(LockHold.Owner.COVER, Main.screenOnCached(), "wallpaper artwork");
        }
    }

    private static void updateDim() {
        if (dimLayer == null && !Main.sHidePlayerBackground) return;
        android.view.ViewGroup layer = CoverPush.coverLayer();
        if (layer == null) return;
        android.view.ViewGroup parent = layer;
        android.view.View below = null;
        int rootId = layer.getResources().getIdentifier("keyguard_root_view", "id", "com.android.systemui");
        // AOD scales and clips the keyguard root. A sibling covers the original wallpaper bounds.
        for (Object current = layer; rootId != 0 && current instanceof android.view.View;
                current = ((android.view.View) current).getParent()) {
            android.view.View view = (android.view.View) current;
            if (view.getId() == rootId && view.getParent() instanceof android.view.ViewGroup) {
                parent = (android.view.ViewGroup) view.getParent();
                below = view;
                break;
            }
        }
        if (dimLayer == null || dimLayer.getParent() != parent) {
            if (dimLayer != null && dimLayer.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) dimLayer.getParent()).removeView(dimLayer);
            dimLayer = new android.view.View(parent.getContext()) {
                @Override public void draw(android.graphics.Canvas canvas) {
                    // The outer host also survives unlock; never dim the unlocked shade.
                    if (Main.keyguardLocked()) super.draw(canvas);
                }
            };
            dimLayer.setTag("HMCWallpaperDim");
            dimLayer.setBackgroundColor(android.graphics.Color.BLACK);
            dimLayer.setClickable(false);
            dimLayer.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            dimLayer.setAlpha(0f);
            int index = below != null ? parent.indexOfChild(below) : parent.getChildCount();
            if (below == null) for (int i = 0; i < parent.getChildCount(); i++) {
                if (parent.getChildAt(i) instanceof CoverCardLayer) { index = i; break; }
            }
            parent.addView(dimLayer, index, new android.view.ViewGroup.LayoutParams(-1, -1));
            dimTarget = Float.NaN;
        }
        boolean dimScene = Main.coverModeOn() && (!LockLyrics.wantsCompactArtwork()
                || LockLyrics.hasLyrics() && LockLyrics.wantsWindow());
        float alpha = Main.sHidePlayerBackground && dimScene ? Main.sWallpaperDim / 100f : 0f;
        if (alpha == dimTarget) return;
        dimTarget = alpha;
        dimLayer.animate().cancel();
        if (Main.screenOnCached()) dimLayer.animate().alpha(alpha).setDuration(240L).start();
        else dimLayer.setAlpha(alpha);
    }

    static void putState(Intent out) {
        long state = decision;
        out.putExtra("backdrophidden", (state & 1L) != 0L);
        out.putExtra("backdropseq", state >>> 1);
    }
}
