package com.os4.musiccover;

import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/** A native Notes button beside the track details, owned only by the lock-screen card. */
final class LyricsButton {
    private static ViewGroup host;
    private static ImageView button;
    private static ValueAnimator animator;
    private static float progress;
    private static boolean requested;
    private static int tint;
    private static final float SIZE_DP = 40f;
    private static final float GAP_DP = 6f;

    private LyricsButton() {}

    static float update(View card, boolean show) {
        if (!(card instanceof ViewGroup)) return 0f;
        if (host != card) release();
        if (button == null && show) {
            host = (ViewGroup) card;
            button = new ImageView(card.getContext());
            button.setId(View.generateViewId());
            button.setImageDrawable(new MiuixNotesDrawable());
            button.setScaleType(ImageView.ScaleType.FIT_CENTER);
            float density = card.getResources().getDisplayMetrics().density;
            int pad = Math.round(8f * density);
            button.setPadding(pad, pad, pad, pad);
            String language = card.getResources().getConfiguration().getLocales().get(0).getLanguage();
            button.setContentDescription("zh".equals(language) ? "显示歌词" : "Show lyrics");
            button.setOnClickListener(v -> Main.openLyricsFromButton());
            button.setAlpha(0f);
            button.setVisibility(View.INVISIBLE);
            int size = Math.round(SIZE_DP * density);
            host.addView(button, new ViewGroup.LayoutParams(size, size));
        }
        if (button == null) return 0f;
        if (requested != show) {
            requested = show;
            button.setClickable(show);
            button.setFocusable(show);
            button.setImportantForAccessibility(show ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if (animator != null) animator.cancel();
            button.setVisibility(View.VISIBLE);
            animator = ValueAnimator.ofFloat(progress, show ? 1f : 0f);
            animator.setDuration(show ? 150L : 110L);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                ImageView view = button;
                if (view == null) return;
                view.setAlpha(progress);
                float radius = requested ? (1f - progress) * 5f
                        * view.getResources().getDisplayMetrics().density : 0f;
                view.setRenderEffect(radius < 0.5f ? null : RenderEffect.createBlurEffect(
                        radius, radius, Shader.TileMode.CLAMP));
                if (!requested && progress == 0f) view.setVisibility(View.GONE);
                if (host != null) host.postInvalidateOnAnimation();
            });
            animator.start();
        }
        return (SIZE_DP + GAP_DP) * card.getResources().getDisplayMetrics().density * progress;
    }

    static void place(View card, TextView title, TextView artist) {
        if (button == null || host != card || title == null || progress <= 0f) return;
        Layout layout = title.getLayout();
        if (layout == null || layout.getLineCount() == 0) return;
        float density = card.getResources().getDisplayMetrics().density;
        int size = Math.round(SIZE_DP * density);
        float titleX = position(card, title, true) + title.getPaddingLeft() + layout.getLineLeft(0);
        float top = position(card, title, false);
        float bottom = artist != null && artist.isShown()
                ? position(card, artist, false) + artist.getHeight() : top + title.getHeight();
        if (!Float.isFinite(titleX) || !Float.isFinite(top) || !Float.isFinite(bottom)) return;
        int left = Math.round(titleX - (SIZE_DP + GAP_DP) * density);
        int y = Math.round((top + bottom - size) / 2f);
        if (button.getLeft() != left || button.getTop() != y || button.getWidth() != size) {
            button.layout(left, y, left + size, y + size);
        }
        int color = title.getCurrentTextColor();
        if (tint != color) {
            tint = color;
            button.setImageTintList(ColorStateList.valueOf(color));
        }
    }

    private static float position(View card, View view, boolean horizontal) {
        float value = 0f;
        while (view != null && view != card) {
            value += horizontal ? view.getLeft() + view.getTranslationX()
                    : view.getTop() + view.getTranslationY();
            if (!(view.getParent() instanceof View)) return Float.NaN;
            view = (View) view.getParent();
        }
        return view == card ? value : Float.NaN;
    }

    static void release() {
        if (animator != null) animator.cancel();
        animator = null;
        if (host != null && button != null) host.removeView(button);
        host = null;
        button = null;
        requested = false;
        progress = 0f;
        tint = 0;
    }
}
