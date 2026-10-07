package com.os4.musiccover;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.Collections;
import java.util.List;

/**
 * The lock screen's lyrics: one view, drawn by hand, between the collapsed clock and the card.
 *
 * Every animated value belongs to a LINE, never to a slot on screen. The first version kept nine
 * TextViews and handed each its neighbour's text on a line change, so brightness, size and
 * weight stayed with the slot while the words moved - the line arriving at the focus snapped to
 * full brightness and the one leaving snapped dark, and only the far rows animated at all.
 * Here a line carries its own scroll position, emphasis, blur and word fill from the moment it
 * is parsed until the song changes.
 *
 * - Position: every line springs toward the scroll target, but only once its own start delay has
 *   passed - the lines below the focus set off one after another, so the stack flows up rather
 *   than moving as a slab. The spring is also a little softer further from the focus.
 * - Emphasis: one 0..1 per line. Brightness and scale both derive from it. The text is laid out
 *   once - the weight never changes, because a weight change re-wraps the line.
 * - Depth: a line that is not being sung is blurred by its distance from the one that is, drawn
 *   as a blurred bitmap made once per line and distance. A line is sharp while it moves and while it has any
 *   emphasis, and goes out of focus once it has settled - which keeps the motion clean and means
 *   no line ever switches between the blurred and the word-by-word path mid-blur.
 * - Words: normal modes keep the full line visible and lift safe grapheme clusters as the karaoke
 *   fill passes, easing them back to baseline. Eye-candy reveals timed clusters and flies each word
 *   into place. A long note also glows and swells while it lasts.
 *   is at the line's brightness and the rest at UNSUNG of it, through a soft-edged mask.
 * - Edges: a line fades as it approaches the top or bottom of the band.
 *
 * Frames are only asked for while something moves. Otherwise the view waits for its own
 * pre-draw, which fires whenever anything else in the keyguard window animates, and for
 * LockLyrics' tick, which wakes it at the next line start.
 */
final class LyricView extends View {

    private static final String TAG = "[MCLyric] ";

    // ---- the look. Sizes are sp/dp; nothing here is a pixel.
    private static final float TEXT_SP = 25f;
    private static final float TRANS_SP = 15f;
    /** Online translation sits below native romanisation/source translation when both exist. */
    private static final float ONLINE_TRANS_SP = 12.5f;
    private static final float ONLINE_TRANS_GAP_DP = 2f;
    /** The background vocal under a line: smaller, dimmer, and lifting less than the lead. */
    private static final float BG_SP = 17f;
    private static final int BG_WEIGHT = 500;
    private static final float BG_GAP_DP = 3f;
    private static final float BG_ALPHA = 0.72f;
    private static final float BG_LIFT = 0.6f;
    private static final int TRANS_WEIGHT = 500;
    /** Between one line's last row (or its translation) and the next line. */
    private static final float GAP_DP = 22f;
    private static final float RAPID_GAP_DP = 8f;
    private static final float TRANS_GAP_DP = 5f;
    /**
     * A line that is not being sung - and the unsung part of the one that is, which Apple draws at
     * the same level (measured: the arriving line's text is 146 on a 34 ground before its first
     * word and 146 after it is sharp, the same as a settled inactive line).
     */
    private static final float INACTIVE = 0.40f;
    /** The unsung words of the singing line, as a share of its emphasis above INACTIVE. None. */
    private static final float UNSUNG = 0f;
    /** Lines already sung, above the focus, are further back than the ones to come. */
    private static final float ABOVE_ALPHA = 0.6f;
    /** How saturated the dim text's tint may be; Apple's reads as a pale version of the cover. */
    private static final float TINT_SAT = 0.28f;
    private static final float TRANS_ALPHA = 0.62f;
    private static final float INACTIVE_SCALE = 0.97f;
    /** Half the width of the soft edge between sung and unsung, in text sizes. */
    private static final float FEATHER_EM = 0.45f;
    /**
     * Short on purpose: the top and bottom lines settle inside this zone, and at 44dp, on top of
     * their blur and the inactive level, they all but vanished.
     */
    private static final float EDGE_FADE_DP = 26f;
    /**
     * Where the singing line's top sits, as a fraction down the band.
     *
     * Only a fraction of a row now, not of the block: anchorY lays the rows out from the band's
     * middle, so this decides how many of them sit above the focus - which is what the depth blur
     * is measured from, and so how much of the stack above the singing line is in focus - and no
     * longer where the block is. It can be turned without touching the margins. On this band,
     * 0.23 holds one line above the focus, 0.40 two, 0.50 three.
     */
    private static final float ANCHOR = 0.40f;
    /**
     * The room between the lyrics and the clock, and between them and the media card.
     *
     * One number for both ends. The band is what is left of the screen between the clock's ink
     * and the card's top, and the lyrics are a whole number of rows laid out from its middle, so
     * two different numbers here came out as one margin visibly wider than the other: on
     * 2026-09-17 the first row sat 52dp below the clock with the last 37dp above the card.
     */
    /** A band shorter than this many rows of text is not worth showing lyrics in. */
    private static final float MIN_BAND_ROWS = 2.4f;
    /**
     * How long the block takes to slide to a new centre, once the band or the rows in it change.
     *
     * The centring is a step function: it asks how many of the rows fit between the clock and the
     * card, and one row more or less moves the block half a row at once. The band crosses those
     * thresholds while it is still moving - on the way into cover mode the clock is collapsing and
     * the card rising, so the count goes 4, 5, 6 as the room appears - and each crossing used to
     * land in a single frame. Same when a line long enough to wrap scrolls into the block: it is
     * two rows tall where its neighbours are one, so the block's height and its centre move
     * together. Easing the correction turns both into a slide.
     *
     * Short enough to read as the block settling rather than as the lyrics lagging behind: the
     * band's own ends are not eased at all, so everything the clock and the card do is still
     * tracked frame for frame.
     */
    private static final float TAU_ANCHOR = 0.09f;

    // ---- depth
    /**
     * Blur radius per row of distance beyond the first, up to BLUR_MAX_ROWS. The lines right
     * next to the singing one stay sharp: blurring the next line as well left it unreadable
     * behind the edge fade (recording 02:23).
     */
    private static final float BLUR_NEXT_DP = 0.9f;
    private static final float BLUR_DP_PER_ROW = 1.6f;
    private static final int BLUR_MAX_ROWS = 4;
    /** A line going out of focus does it fast; one coming in clears a little slower. */
    private static final float TAU_BLUR_IN = 0.05f;
    private static final float TAU_BLUR_OUT = 0.07f;
    /**
     * How much brighter than SDR white the singing words are drawn when HDR is on - Apple
     * Music's highlight, asked to be obvious. The window's headroom is set above this.
     */
    private static final float HDR_GAIN = 3f;
    /** The window is switched to HDR this long before a glow starts, so it is ready for it. */
    private static final int HDR_ARM_MS = 250;
    private static final android.graphics.ColorSpace EXTENDED =
            android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.EXTENDED_SRGB);
    private static final float BLUR_ALPHA_PER_DP = 0.12f;

    // ---- words
    /** Base lift for background syllables and the lead's local cluster wave. */
    private static final float LIFT_DP = 1.8f;
    /** Plain karaoke lifts each timed word once, then leaves it raised after it is sung. */
    private static final int BASIC_WORD_RISE_MS = 180;
    /** A small hold makes the animated-mode lift read as sung, followed by a soft settle. */
    private static final int LIFT_HOLD_MS = 60;
    private static final int LIFT_RETURN_MS = 280;
    /** Karaoke letters ease in over a short, softly blurred offset. */
    private static final int KARAOKE_LETTER_FLY_MS = 250;
    /** Eye-candy's deliberately theatrical entrance: a word arrives with a visible overshoot. */
    private static final int EYE_WORD_FLY_MS = 420;
    /** A syllable at least this long is a held note, and glows. */
    private static final int GLOW_MIN_MS = 1000;
    private static final float GLOW_DP = 9f;
    private static final float GLOW_ALPHA = 0.55f;
    private static final float GLOW_SWELL = 0.045f;
    /** How long a held note's glow takes to go after the note ends. */
    private static final int GLOW_TAIL_MS = 380;

    // ---- the motion: AMLL's (amll-dev/applemusic-like-lyrics, lyric-player/base), which is
    // what Apple's is taken to be. Not a bouncing spring - an over-damped one (ratio ~1.1) - and
    // what makes it read as water is the ripple: every line sets off a little after the one
    // above it, top of the view downwards.
    /** Normal play: stiffness between these, faster the closer the two lines are in time. */
    private static final float K_MIN = 170f, K_MAX = 220f;
    private static final int IV_MIN = 100, IV_MAX = 800;
    /** Damping is sqrt(stiffness) times this, mass 1. */
    private static final float DAMPING_MULT = 2.2f;
    /** A seek or an interlude: slower and softer. */
    private static final float K_SLOW = 90f, C_SLOW = 15f;
    /** The spring a seek travels on: softer than a line change, since the distance is arbitrary. */
    private static final float K_SEEK = 120f, C_SEEK = 24f;
    /**
     * The fastest the lyrics ever scroll, in band-heights per second.
     *
     * A spring's force grows with distance, so without a cap a seek across a whole song reaches
     * its target within a frame or two - technically animated, indistinguishable from a cut. The
     * cap is what turns that into a scroll you can follow, and it is in band-heights rather than
     * pixels so it means the same thing on every screen. An ordinary line change never reaches
     * it: one line's height carries a peak of a few hundred pixels a second against a cap in the
     * thousands, so this costs the normal animation nothing.
     */
    private static final float SEEK_SPEED_BANDS = 8f;
    /** The ripple: 50ms more per line from the top of the view, shrinking past the focus. */
    private static final float RIPPLE_MS = 50f;
    private static final float RIPPLE_DECAY = 1f / 1.05f;
    /** The stack moves to a line this long before its first word (measured 1.07s). */
    private static final long LEAD_MS = 1000L;
    /** Emphasis arrives quickly and leaves in one frame: the sung line drops out at once. */
    private static final float TAU_EMPH_IN = 0.08f;
    private static final float TAU_EMPH_OUT = 0.016f;
    /** AMLL's scale spring (stiffness 100, damping 25) settles in about this time constant. */
    private static final float TAU_SCALE = 0.15f;
    private static final float TAU_SHOW = 0.18f;
    /** Rising while growing out of the island: the pop's own alpha spring does the fading. */
    private static final float TAU_POP_SHOW = 0.03f;
    /** Out faster than in: the clock starts growing into the lyrics' space at once. */
    private static final float TAU_HIDE = 0.06f;
    /**
     * How far the lyrics float up into place as they appear, and back down as they leave.
     *
     * A wake already moves them: the band is measured from the clock's live ink, so on the way
     * in from the AOD they ride the collapse down into place. A two-finger switch has no such
     * movement behind it - cover mode is already on and the clock is already small - so the
     * lyrics simply materialised where they were going to be. This gives the switch the same
     * arrival, and the same departure in reverse.
     *
     * Driven by `show` rather than by a timer of its own, so the movement and the fade are the
     * same event: in on TAU_SHOW, out on TAU_HIDE, and an arrival interrupted half way turns
     * around from where it is instead of from the far end.
     */
    private static final float FLOAT_DP = 26f;

    /*
     * The lyrics and the mini player's island, both ways: ColorOS's capsule-to-immersive entry
     * for its multi-line lyrics and its way back (SystemUIPlugin w6.i). In, the block starts on
     * the island's centre at 0.6 of its size, transparent and blurred, and springs home (w6.f.k);
     * out, it shrinks to 0.1 into where the island will be, fading and blurring (w6.f.g). Each
     * property is its own spring, stepped per frame, so an arrival or a departure turned round
     * half way goes back from where it is with the speed it has. (response, bounce) as PageSpring.
     */
    private static final float POP_SCALE_IN = 0.6f, POP_SCALE_OUT = 0.1f;
    private static final float[] POP_IN_MOVE = {0.52f, 0.21f}, POP_IN_ALPHA = {0.1f, 0f},
            POP_IN_SCALE = {0.5f, 0.2f}, POP_IN_BLUR = {0.2f, 0f};
    private static final float[] POP_OUT_MOVE = {0.35f, 0f}, POP_OUT_ALPHA = {0.25f, 0f},
            POP_OUT_SCALE = {0.3f, 0f}, POP_OUT_BLUR = {0.3f, 0f};
    /** ColorOS blurs from and to 200px on its ~3.5 density. */
    private static final float POP_BLUR_DP = 57f;
    /** A departure is over once it is this faint, or after this long whatever it looks like. */
    private static final float POP_OUT_GONE = 0.004f;
    private static final long POP_OUT_MAX_MS = 1200L;
    private static final int POP_NONE = 0, POP_IN = 1, POP_OUT = 2, POP_HOLD = 3;

    /** A gap between lines at least this long gets the interlude dots. */
    private static final int LULL_MS = 4000;

    // ---- interlude dots, in text sizes and milliseconds (see drawDots)
    private static final float DOT_EM = 0.25f;
    private static final float DOT_GAP_EM = 0.43f;
    private static final float DOT_CENTER_EM = 0.9f;
    private static final float DOTS_SLOT_EM = 1.8f;
    private static final float DOT_DIM = 0.18f;
    private static final float DOT_RAMP = 0.7f;
    /**
     * The breath: the whole group swells and shrinks about its centre by this much either way.
     * It was +-13.5% of each dot's radius on a 4.2s cosine - too slow and too small to read as
     * breathing (user, 2026-09-16).
     */
    private static final float DOT_BREATH = 0.2f;
    /** About this long a breath, stretched so a whole number of them fits the interlude. */
    private static final long DOT_BREATH_MS = 2400L;
    /** The share of a breath spent swelling; the shrink is slower, like breathing out. */
    private static final float DOT_INHALE = 0.42f;
    /** How long the breath takes to reach its full depth once the interlude starts. */
    private static final long DOT_BREATH_IN_MS = 700L;
    private static final long DOT_APPEAR_MS = 1200L;
    /** Out: a small swell, then shrinking to nothing, ending this long before the scroll. */
    private static final long DOT_EXIT_MS = 480L;
    private static final long DOT_EXIT_LEAD_MS = 120L;
    private static final float DOT_EXIT_BACK = 1.7f;

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint transPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint onlineTransPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bgPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int tintSrc = -1;
    private float tintR = 1f, tintG = 1f, tintB = 1f;
    private final Matrix gradMatrix = new Matrix();
    private final Matrix shimmerMatrix = new Matrix();
    private final TextPaint shimmerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient shimmerShader;
    private long shimmerHi, shimmerColorHi, shimmerGlintHi, shimmerGlintLo;
    private long shimmerColorLo, shimmerLo;
    private int rowLeadIndex = -1;
    private float pulseScale = 1f;
    private float pcmLevel, pcmTransient;
    private boolean audioLeaseRunning;
    private final Runnable audioChanged = new Runnable() {
        @Override public void run() {
            if (audioWanted()) kick();
            else stopAudio();
        }
    };
    private final Runnable audioHeartbeat = new Runnable() {
        @Override public void run() {
            if (!audioWanted()) {
                stopAudio();
                return;
            }
            PlaybackPcmCapture.setActive(getContext(), LockLyrics.activePlayerPackage(), true);
            kick();
            postDelayed(this, 500L);
        }
    };
    private final LinearGradient[] grads = new LinearGradient[3];
    private final long[] gradHi = {-1L, -1L, -1L}, gradLo = {-1L, -1L, -1L};
    /** The row being drawn: where its gradient crosses (NaN = no gradient) and its two levels. */
    private float rowAt = Float.NaN, rowFeather, rowSungA, rowUnsungA;
    /** Reused output for the eye-candy word-flight transform (offset, alpha, blur). */
    private final float[] eyeWordMotion = new float[4];

    private final float density;
    private float textPx;
    private final float liftPx, glowPx;
    /** Room around a line for its glow and lift, and around a blurred node for the blur. */
    private int wordPad;
    private final int blurPad;
    private float sidePx;
    /** The typography and width currently represented by the layouts and blur cache. */
    private LyricStyle layoutStyle;

    // ---- content, rebuilt when the lines or the width change
    private int version = -1;
    private List<LyricLine> lines = Collections.emptyList();
    private int layoutWidth = -1;
    private StaticLayout[] main = new StaticLayout[0];
    private StaticLayout[] trans = new StaticLayout[0];
    private StaticLayout[] onlineTrans = new StaticLayout[0];
    private StaticLayout[] bgLay = new StaticLayout[0];
    /** Top of each line in content coordinates, and its full height with translation. */
    private float[] base = new float[0];
    private float[] height = new float[0];
    private float[][] charX = new float[0][];
    private float[][] charXBg = new float[0][];
    private int[][] clusterEnd = new int[0][];
    private int[][] clusterEndBg = new int[0][];
    /**
     * Each line's blurred picture, at the radius its distance asks for. A bitmap, not a
     * RenderNode with a blur effect: that was the first version, and on this phone the node
     * drew nothing at all - every line vanished the moment it settled (recording 02:52).
     */
    /**
     * Indexed by line and distance - 1, kept for as long as the line is near the focus. It used
     * to be three slots a line with the farthest evicted, and a line's distance walks 4, 3, 2, 1,
     * 0, 2, 3 - so every line change threw pictures away that the next one asked for again: a
     * dozen blurs, bitmaps and texture uploads landing in the middle of every scroll.
     */
    private Bitmap[][] blurBmp = new Bitmap[0][];
    /** Pictures asked of the blur thread and not back yet, as line * 8 + distance. */
    private final java.util.HashSet<Integer> blurPending = new java.util.HashSet<>();
    /** Bumped by every rebuild, so a picture made for the previous layout is thrown away. */
    private int buildGen;
    /** The lines and width a layout is on its way for, or -1 with none in the air. */
    private int wantVersion = -1, wantWidth = -1;
    /** The translation and romanisation switches the layout in the air is for (LockLyrics.below()). */
    private int wantTrans;
    /** The alignment pref the layout in the air is for; only meaningful with a version above. */
    private int wantAlign;
    private LyricStyle wantStyle;
    /** The translation and romanisation switches the layout now in use was made under. */
    private int builtTrans = LockLyrics.BELOW_TRANS;
    private boolean builtRapidGroups;
    /** The alignment pref the layout now in use was made under; see LockLyrics.sAlign. */
    private int builtAlign = LockLyrics.ALIGN_LEFT;
    /** Diagnostics: how long the last layout took on its thread. */
    private long layoutMs;
    /** From this distance on the blur is wide enough to be made at a quarter of the resolution. */
    private static final int BLUR_QUARTER_ROWS = 3;
    private final android.graphics.RectF bmpDst = new android.graphics.RectF();
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // ---- per-line animated state
    private float[] scroll = new float[0];
    private float[] vel = new float[0];
    /** What the line is springing to, what it will spring to, and when it switches. */
    private float[] aim = new float[0];
    private float[] nextAim = new float[0];
    private long[] aimAt = new long[0];
    private float[] emph = new float[0];
    /** A line-timed line's brightness: on while it is sung. */
    private float[] lit = new float[0];
    private float[] scale = new float[0];
    private float[] blur = new float[0];
    /** Translation fade-in starts per line: -1 pending first visible draw, <=0 settled/none. */
    private long[] transFadeAt = new long[0];
    /** Where the interlude slot before each line sits; NaN where the gap is short. */
    private float[] dotsTop = new float[0];

    /** The line the stack is on, the line whose interlude is showing (-1), and both as one key. */
    private int focus = -1;
    private int simultaneousDropped = -1;
    private float simultaneousTop;
    private float simultaneousTopWant;
    private boolean simultaneousTopSet;
    private int simultaneousFirst = -1;
    private int simultaneousSecond = -1;
    private int simultaneousExitLine = -1;
    private long simultaneousExitAt;
    private float simultaneousExitFrom;
    private static final long SIMULTANEOUS_HANDOFF_MS = 180L;
    private int dotsFor = -1;
    private RapidLyricGroups rapidGroups = new RapidLyricGroups(Collections.emptyList());
    /** The scroll anchor may stay on a group's first line while the singing focus advances. */
    private int readingFirst = -1, readingLast = -1;
    /** The scroll spring for the current move, set per move like AMLL's policy. */
    private float springK = K_SLOW, springC = C_SLOW;
    private boolean dotsWasShowing;
    private int focusKey = Integer.MIN_VALUE;
    private int ms;
    private float show;
    /**
     * The island pop (POP_*): which way it is going, when a departure began, and whether this
     * showing is the pop's - that takes the float out for all of it, arrival to departure, and
     * lasts until the page has faded to nothing. HOLD is an arrival cut short by anything but
     * the island: the springs stop where they are and the page fades as any other does.
     */
    private int popMode = POP_NONE;
    private boolean popped;
    private long popOutAt;
    private final float[] popPoint = new float[2];
    /** Offsets from home in this view's pixels, the scale, the alpha and the blur radius. */
    private final Spring popX = new Spring(), popY = new Spring(), popS = new Spring(1f),
            popA = new Spring(1f), popB = new Spring();
    private float popToX, popToY, popBlur;
    private float bandTop, bandBottom;
    private final float[] bandBounds = new float[2];
    private boolean bandOk;
    /**
     * The centring correction in force this frame, and the one the geometry is asking for, both
     * off the uncentred anchor and both in this view's pixels. Equal at rest; they differ only
     * while the block is sliding to a new centre (see TAU_ANCHOR).
     */
    private float anchorFix, anchorFixWant;

    private long lastStep;

    private boolean looping;
    private final int[] loc = new int[2];

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            noteFrameGap(now());
            looping = false;
            if (step()) invalidate();
            if (needsFrames()) {
                looping = true;
                postOnAnimation(this);
            } else {
                lastLoopFrame = 0L;
            }
        }
    };

    private final ViewTreeObserver.OnPreDrawListener preDraw =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() { android.os.Trace.beginSection("MC lyricPreDraw"); try {
                    if (step()) invalidate();
                    if (!looping && needsFrames()) kick();
                    return true;
                } finally { android.os.Trace.endSection(); } }
            };

    /** For LyricWindow, which adds the same pre-draw to the keyguard's tree. */
    ViewTreeObserver.OnPreDrawListener preDrawListener() {
        return preDraw;
    }

    LyricView(Context ctx) {
        super(ctx);
        density = getResources().getDisplayMetrics().density;
        liftPx = LIFT_DP * density;
        glowPx = GLOW_DP * density;
        blurPad = (int) Math.ceil((BLUR_NEXT_DP + BLUR_DP_PER_ROW * BLUR_MAX_ROWS) * density * 2f);
        paint.setColor(0xFFFFFFFF);
        transPaint.setColor(0xFFFFFFFF);
        onlineTransPaint.setColor(0xFFFFFFFF);
        bgPaint.setColor(0xFFFFFFFF);
        applyPaintStyle(LockLyrics.sStyle);
        // No frame rate is asked for here. The keyguard window renders at 60Hz on this 120Hz
        // panel unless the screen is touched, and neither setRequestedFrameRate on this view nor
        // a 120Hz vote on the window's own layer moved HyperOS off that (SurfaceFlinger dumps,
        // 2026-09-16). The user's call: the touch boost is enough, do not hold the panel up.
        // Touches go through to the lock screen: the double tap and the swipes are the OEM's.
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The screen's short side, which the text column is as wide as - see LyricStyle.sidePx. */
    private int shortSide() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.min(dm.widthPixels, dm.heightPixels);
    }

    private void applyPaintStyle(LyricStyle style) {
        layoutStyle = style;
        textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, style.sizeSp,
                getResources().getDisplayMetrics());
        wordPad = (int) Math.ceil(glowPx * 1.6f + liftPx + textPx * GLOW_SWELL);
        sidePx = style.sidePx(getWidth(), shortSide(), density, textPx);
        paint.setTextSize(textPx);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, style.weight, false));
        transPaint.setTextSize(textPx * TRANS_SP / TEXT_SP);
        transPaint.setTypeface(Typeface.create(Typeface.DEFAULT, TRANS_WEIGHT, false));
        onlineTransPaint.setTextSize(textPx * ONLINE_TRANS_SP / TEXT_SP);
        onlineTransPaint.setTypeface(Typeface.create(Typeface.DEFAULT, TRANS_WEIGHT, false));
        bgPaint.setTextSize(textPx * BG_SP / TEXT_SP);
        bgPaint.setTypeface(Typeface.create(Typeface.DEFAULT, BG_WEIGHT, false));
    }

    /** Something outside changed - a line start, the song, the setting. Wakes the loop. */
    void kick() {
        if (looping) return;
        looping = true;
        postOnAnimation(frame);
    }

    /**
     * A frame now even if nothing moves in it: the AOD's still mode lets the display up for this
     * one, and a frame drawn while it was held down may never have reached the panel.
     */
    void redraw() {
        invalidate();
        kick();
    }

    /**
     * The playback time after pos at which the stack next moves, or -1 if it will not. AOD still
     * mode only wakes for the same line transitions and interlude handoffs as the original
     * renderer; word animation is deliberately not scheduled in AOD.
     */
    long nextMoveAfter(int pos) {
        int n = lines.size();
        if (n == 0 || dotsTop.length != n) return -1L;
        int idx = indexAt(pos);
        if (idx < 0) idx = 0;
        long best = Long.MAX_VALUE;
        for (int k = Math.max(0, idx); k < n; k++) {
            long t = switchAt(k);
            if (t > pos && t < best) best = t;
            if (k > 0 && !Float.isNaN(dotsTop[k])) {
                long end = lines.get(k - 1).end;
                if (end > pos && end < best) best = end;
            }
        }
        return best == Long.MAX_VALUE ? -1L : best;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDraw);
        lastStep = 0L;
        LockLyrics.startTick();
        kick();
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        removeCallbacks(frame);
        stopAudio();
        looping = false;
        lastLoopFrame = 0L;
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ state

    /**
     * One step of everything. Returns whether anything visible changed. Safe to call twice in a
     * frame - the animation callback and the pre-draw both do - because the second call has no
     * time to integrate.
     */
    private boolean step() {
        // The frame's own vsync time, not the clock at the moment this runs: how late the main
        // thread gets to a frame varies by a few ms with whatever else the keyguard is doing, and
        // stepping on the wall clock put that jitter straight into the scroll and the word fill.
        long now = now();
        if (now == lastStep) return false;
        float dt = lastStep == 0L ? 0f : Math.min(0.05f, (now - lastStep) / 1000f);
        lastStep = now;

        boolean changed = followAodDim();
        changed |= followHostAlpha();
        changed |= followBouncer(dt);
        // Before the band below, which reads this view's scale.
        changed |= followSwipeZoom();
        int why = 0;
        // The AOD's still mode: every frame here is one the display was let up for, and the next
        // is a line away, so nothing eases - each value is put where it is heading. See
        // LockLyrics.still().
        boolean still = LockLyrics.still();
        // Leaving the lock screen, in the window (LyricWindow): the keyguard is gone and the
        // desktop is what is behind, so there is nothing left to fade against - the page goes on
        // the frame the lock screen does rather than after it (user, 2026-09-29: "解锁的时候歌词
        // 会有一瞬间的残留"). Gone is also "a leaving hook has fired", not only the keyguard
        // reading unlocked: that reading lags the start of the OEM's exit animation, and the lag
        // is the residue. As a child of the keyguard the lock screen takes the view with it.
        boolean gone = LyricWindow.owns(this) && LockLyrics.lockScreenGone();
        pageWanted = LockLyrics.wantsWindow();
        float dtTo = still || gone ? Float.POSITIVE_INFINITY : dt;
        // Not before the first layout: a width of zero would wrap every line a character a row.
        // Leaving, the lines are frozen like the band below: switching the lyrics off empties
        // them, and laying the empty set out at once cut the lines off in one frame instead of
        // letting them fade.
        if (getWidth() > 0 && (LockLyrics.version() != version || getWidth() != layoutWidth
                // The translation switch changes the layout, not just the drawing: it takes a
                // row out from under every line, so it is asked for the same way a new lyric set
                // is. Compared against what the layout in use was built with, not the field, or
                // the request would still look outstanding the moment it landed.
                || LockLyrics.below() != builtTrans
                // The alignment is in the layout too, and asked for the same way: against what
                // the layout in use was built with, not against the field.
                || LockLyrics.sAlign != builtAlign
                || LockLyrics.sRapidGroups != builtRapidGroups
                || !LockLyrics.sStyle.sameLayout(layoutStyle))
                && (LockLyrics.wantsAttached() || show == 0f)) {
            if (layOut()) {
                changed = true;
                why |= 1;
            }
        }
        // Leaving, the band is frozen where it was: following the clock as it grows would drag
        // the fading lines up through it.
        if (LockLyrics.wantsShown() && updateBand()) {
            changed = true;
            why |= 2;
        }

        // The block's centre, slid to rather than cut to. Snapped whenever there is nothing to
        // slide: hidden or not yet shown, so each arrival starts from the right place instead of
        // easing up from wherever the last one left off, and whenever there is no block to
        // centre, so a correction from the previous song cannot outlive it.
        anchorFixWant = anchorFixTarget();
        float before = anchorFix;
        if (dt <= 0f || still || show < 0.02f || !centring()) {
            anchorFix = anchorFixWant;
        } else {
            anchorFix = approach(anchorFix, anchorFixWant, dt, TAU_ANCHOR);
            // Land on it exactly: a correction that keeps closing by a hundredth of a pixel keeps
            // the view invalidating forever, and nothing here is drawn at that resolution anyway.
            if (Math.abs(anchorFixWant - anchorFix) < 0.01f) anchorFix = anchorFixWant;
        }
        if (anchorFix != before) {
            changed = true;
            why |= 512;
        }

        float showTo = showTarget();
        if (turnPop(now, showTo, still || gone)) changed = true;
        // Going into the island, the page stays up and the pop's alpha is its fade; coming out
        // of it, `show` only carries the card's progress and the clock's alpha, so it goes
        // straight there instead of easing.
        if (popMode != POP_OUT) {
            // Back from under the control centre: as quick as the way out, not the slow
            // arrival a song's first lines get (#52).
            if (centreHeld && showTo <= show) centreHeld = showTo == 0f;
            float tauShow = showTo < show ? TAU_HIDE : popMode == POP_IN ? TAU_POP_SHOW
                    : centreHeld ? TAU_HIDE : TAU_SHOW;
            float s = approach(show, showTo, dtTo, tauShow);
            if (Math.abs(s - showTo) < 0.004f) s = showTo;
            if (s != show) {
                show = s;
                changed = true;
                why |= 4;
            }
        }
        if (stepPop(now, dt, showTo)) {
            changed = true;
            why |= 4;
        }
        trace(now, gone);
        changed |= followAudio(dt);
        if (show == 0f && showTo == 0f && !LockLyrics.wantsAttached()) {
            // Faded out with nothing to come back for: leave the keyguard's tree or take the
            // window down, and let the frozen lines and their pictures go if the switch emptied
            // them. wantsAttached() and not
            // wantsWindow(): a page that faded out because the phone was unlocked still has the
            // cover waiting for it behind the lock screen (see LockLyrics.wantsWindow).
            if (LockLyrics.lines().isEmpty() && !lines.isEmpty()) layOut();
            post(new Runnable() {
                @Override
                public void run() {
                    LockLyrics.detach(LyricView.this);
                }
            });
            return changed;
        }
        if (lines.isEmpty()) return changed;
        // Borrowed translations fading in: a frame each until they are all the way in.
        if (transRevealAt != 0L) {
            if (now - transRevealAt >= TRANS_REVEAL_MS) transRevealAt = 0L;
            changed = true;
        }

        // The position moves on every step while playing, and that alone is NOT a change: it
        // used to be, so every pre-draw invalidated, which drew the next frame, whose pre-draw
        // invalidated again - the whole keyguard window redrawn at the refresh rate for as long
        // as music played. SystemUI's main thread went to GC for 27s in two minutes and was
        // killed for an ANR (2026-09-16 02:51). Only what moves redraws.
        ms = smoothPosition(now);
        int n = lines.size();
        int idx = indexAt(ms);
        updateSimultaneousDrop(n);
        int simultaneousNow = simultaneousCount();
        if (simultaneousNow >= 2) {
            simultaneousExitLine = -1;
        } else if (simultaneousExitLine < 0 && simultaneousFirst >= 0
                && simultaneousSecond >= 0 && simultaneousNow == 1) {
            int remaining = sungNow(simultaneousFirst) ? simultaneousFirst : simultaneousSecond;
            float groupScale = simultaneousScale();
            float gap = GAP_DP * density;
            simultaneousExitLine = remaining;
            simultaneousExitFrom = simultaneousTop
                    + (remaining == simultaneousSecond
                    ? (height[simultaneousFirst] + gap) * groupScale : 0f);
            simultaneousExitAt = now + SIMULTANEOUS_HANDOFF_MS;
        }

        // Which line the stack is on, which can be ahead of the one being sung: Apple scrolls to
        // the next line about a second before its first word (measured 1.07s), once the line
        // before has finished. A long gap scrolls to the interlude dots instead, as soon as the
        // line before it ends.
        int sf, dots = -1;
        if (idx < 0) {
            sf = 0;
            if (!Float.isNaN(dotsTop[0]) && ms < switchAt(0)) dots = 0;
        } else {
            sf = idx;
            int next = idx + 1;
            if (next < n) {
                if (ms >= switchAt(next)) {
                    sf = next;
                } else if (!Float.isNaN(dotsTop[next]) && ms >= lines.get(idx).end) {
                    sf = next;
                    dots = next;
                }
            }
        }
        int key = dots >= 0 ? -(dots + 1) : sf;
        int oldReadingFirst = readingFirst, oldReadingLast = readingLast;
        readingRange(sf, dots);
        if (key != focusKey || oldReadingFirst != readingFirst || oldReadingLast != readingLast) {
            // Whether this was a seek is a question about the playhead, not about how many lines
            // it crossed. Counting lines got it wrong in both directions and inconsistently
            // inside one drag: eight lines is seconds of a fast song and minutes of a slow one,
            // so dragging the progress bar animated a long scroll when the lines happened to be
            // dense and cut straight to the target when they happened to be sparse.
            // First lines of a song go straight to their place - there is nothing on screen for
            // them to travel from. A seek travels: see the spring picked for it below.
            boolean first = focus < 0;
            boolean seek = !first && now - jumpedAt < SEEK_WINDOW_MS;
            if (seek) simultaneousDropped = -1;
            focus = sf;
            dotsFor = dots;
            focusKey = key;
            focusChangedAt = now;
            float to = dots >= 0 ? dotsTop[dots] : base[readingFirst];
            if (first) {
                snap(to);
                // A freshly laid out song has nowhere to slide from, and the correction it needs
                // is not the one the song before it left behind: taken, not eased into.
                anchorFix = anchorFixWant = anchorFixTarget();
            } else if (seek) {
                // A drag scrolls there rather than cutting, however far it went - watching the
                // lyrics travel is what makes a seek legible, and the direction it travels says
                // which way the playhead moved.
                //
                // Two things separate this from an ordinary line change. There is no ripple: the
                // per-line delay exists to make one line hand over to the next, and across
                // twenty lines it reads as the list coming apart. And the spring is softer,
                // because the distance here is the distance between two arbitrary points in the
                // song rather than one line's height - what stops it from being a teleport is
                // the speed cap in the integrator, and a softer spring hands over to that cap
                // and back more gently.
                springK = K_SEEK;
                springC = C_SEEK;
                dotsWasShowing = dots >= 0;
                for (int i = 0; i < n; i++) {
                    aimAt[i] = now;
                    nextAim[i] = to;
                }
            } else {
                // The spring for this move: slow into and out of an interlude, otherwise stiffer
                // the shorter the gap from the line before.
                boolean slow = dots >= 0 || dotsWasShowing || sf == 0;
                dotsWasShowing = dots >= 0;
                if (slow) {
                    springK = K_SLOW;
                    springC = C_SLOW;
                } else {
                    int iv = lines.get(sf).start - lines.get(sf - 1).start;
                    iv = Math.max(IV_MIN, Math.min(IV_MAX, iv));
                    float ratio = 1f - (iv - IV_MIN) / (float) (IV_MAX - IV_MIN);
                    ratio = (float) Math.pow(ratio, 0.2);
                    springK = K_MIN + ratio * (K_MAX - K_MIN);
                    springC = (float) Math.sqrt(springK) * DAMPING_MULT;
                }
                // The ripple, counted from the first line whose target is on screen.
                float anchor = anchorY();
                float delay = 0f, step = RIPPLE_MS;
                for (int i = 0; i < n; i++) {
                    aimAt[i] = now + Math.round(delay);
                    nextAim[i] = to;
                    float y = anchor + base[i] - to;
                    if (y + height[i] >= bandTop) {
                        delay += step;
                        if (i >= sf) step *= RIPPLE_DECAY;
                    }
                }
            }
            prewarmBlur();
            if (LockLyrics.verbose) {
                Xp.log(TAG + (dots >= 0 ? "interlude before " : "line ") + sf + "/" + n
                        + " at " + ms + "ms");
            }
            changed = true;
            why |= 8;
        }

        float simultaneousTarget = simultaneousGroupTop();
        if (Float.isNaN(simultaneousTarget)) {
            // Keep the last duet position through a one-line handoff. Clearing this state here
            // lets a singer arriving immediately after an exit reseed from the ordinary stack,
            // which makes the outgoing/incoming animation snap instead of continuing smoothly.
            // A new song still clears it in layOut().
        } else if (!simultaneousTopSet || still || dt <= 0f) {
            if (!still && dt > 0f && simultaneousCount() >= 2) {
                float seed = simultaneousEntryTop();
                simultaneousTop = Float.isNaN(seed) ? simultaneousTarget : seed;
            } else {
                simultaneousTop = simultaneousTarget;
            }
            simultaneousTopWant = simultaneousTarget;
            simultaneousTopSet = true;
        } else {
            simultaneousTopWant = simultaneousTarget;
            simultaneousTop = approach(simultaneousTop, simultaneousTarget, dt, 0.16f);
            simultaneousTopSet = true;
        }
        if (simultaneousExitLine >= 0 && now < simultaneousExitAt) {
            changed = true;
            why |= 256;
        }

        float target = dotsFor >= 0 ? dotsTop[dotsFor] : base[readingFirst];
        // Band-relative so it means the same on any screen; the fallback is for the frames
        // before the band has been measured.
        float band = bandBottom - bandTop;
        float speedCap = (band > 1f ? band : Math.max(1, getHeight())) * SEEK_SPEED_BANDS;
        int lo = Math.max(0, focus - 6), hi = Math.min(n - 1, focus + 12);
        for (int i = 0; i < n; i++) {
            if (i < lo || i > hi) {
                if (scroll[i] != target || emph[i] != 0f || blur[i] != 0f) {
                    scroll[i] = aim[i] = nextAim[i] = target;
                    vel[i] = 0f;
                    emph[i] = 0f;
                    lit[i] = 0f;
                    scale[i] = INACTIVE_SCALE;
                    blur[i] = 0f;
                }
                dropBlurred(i);
                continue;
            }
            // Position: the line's own ripple delay, then the move's spring.
            boolean started = still || now >= aimAt[i];
            if (aim[i] != nextAim[i] && started) aim[i] = nextAim[i];
            float x = scroll[i] - aim[i];
            if (still) {
                // Cut to, not scrolled to: the ripple and the spring are both left out.
                if (aimAt[i] > now) aimAt[i] = now;
                if (x != 0f || vel[i] != 0f) {
                    scroll[i] = aim[i];
                    vel[i] = 0f;
                    changed = true;
                    why |= 32;
                }
            } else if (dt > 0f && (Math.abs(x) > 0.3f || Math.abs(vel[i]) > 2f)) {
                float left = dt;
                while (left > 0f) {
                    float h = Math.min(left, 1f / 240f);
                    vel[i] += (-springK * x - springC * vel[i]) * h;
                    if (vel[i] > speedCap) {
                        vel[i] = speedCap;
                    } else if (vel[i] < -speedCap) {
                        vel[i] = -speedCap;
                    }
                    x += vel[i] * h;
                    left -= h;
                }
                scroll[i] = aim[i] + x;
                changed = true;
                why |= 16;
            } else if (x != 0f) {
                scroll[i] = aim[i];
                vel[i] = 0f;
                changed = true;
                why |= 32;
            }

            LyricLine l = lines.get(i);
            boolean focused = dotsFor < 0 && i == focus;
            // Emphasis: on the scroll focus, and on a duet's overlapping answer while it is sung.
            // Off in one frame - the line that has been sung drops to the inactive level at once.
            boolean concurrentlySung = simultaneousVisible(i) && !focused;
            boolean on = focused || concurrentlySung
                    || (dotsFor < 0 && i < focus && i >= focus - 2
                    && ms >= l.start && ms < l.end);
            float eTo = on ? 1f : 0f;
            float e = approach(emph[i], eTo, dtTo, eTo > emph[i] ? TAU_EMPH_IN : TAU_EMPH_OUT);
            if (Math.abs(e - eTo) < 0.003f) e = eTo;
            if (e != emph[i]) {
                emph[i] = e;
                changed = true;
                why |= 64;
            }
            // A line-timed line lights when it is sung, not when the stack arrives a second early.
            // Still, it lights with the move: there is no frame a second later to light it in.
            float lTo = on && (still || ms >= l.start) ? 1f : 0f;
            float lv = approach(lit[i], lTo, dtTo, lTo > lit[i] ? TAU_EMPH_IN : TAU_EMPH_OUT);
            if (Math.abs(lv - lTo) < 0.003f) lv = lTo;
            if (lv != lit[i]) {
                lit[i] = lv;
                changed = true;
                why |= 64;
            }
            // Size: the focus at full size, the rest a little smaller, travelling with the scroll.
            boolean sung = simultaneousVisible(i);
            float scTo = focused || sung ? 1f : INACTIVE_SCALE;
            float sc = started ? approach(scale[i], scTo, dtTo, TAU_SCALE) : scale[i];
            if (Math.abs(sc - scTo) < 0.0005f) sc = scTo;
            if (sc != scale[i]) {
                scale[i] = sc;
                changed = true;
                why |= 64;
            }
            // Depth: the line leaving goes out of focus fast, the one arriving clears a little
            // slower (about 100ms and 200ms in the frames).
            float bt = blurTarget(i);
            float b = approach(blur[i], bt, dtTo, bt > blur[i] ? TAU_BLUR_IN : TAU_BLUR_OUT);
            if (Math.abs(b - bt) < 0.05f) b = bt;
            if (b != blur[i]) {
                blur[i] = b;
                changed = true;
                why |= 128;
            }
        }
        if (!still && (wordsLive() || dotsLive())) why |= 256;
        LockLyrics.setGlowing(glowSoon());
        noteWhy(why);
        return changed || (why & 256) != 0;
    }

    private void readingRange(int line, int dots) {
        readingFirst = readingLast = line;
        if (dots >= 0 || !rapidGrouped(line)) return;
        float band = bandBottom - bandTop;
        float fade = Math.min(EDGE_FADE_DP * density, Math.max(0f, band) / 3f);
        long range = rapidGroups.range(line, base, height, Math.max(0f, band - 2f * fade));
        readingFirst = RapidLyricGroups.first(range);
        readingLast = RapidLyricGroups.last(range);
    }

    /** Sung lines in the current fast group remain sharp and readable until the next group. */
    private boolean retained(int line) {
        return LockLyrics.sRapidGroups && dotsFor < 0 && readingLast > readingFirst
                && line >= readingFirst && line <= focus;
    }

    private boolean rapidGrouped(int line) {
        return LockLyrics.sRapidGroups && rapidGroups.grouped(line);
    }

    /**
     * When the stack moves to line i: a second before its first word, but not before the line
     * ahead of it has finished, and never after its own start.
     */
    private long switchAt(int i) {
        LyricLine l = lines.get(i);
        // In a quick group only the highlight advances, exactly when the next line is sung.
        if (rapidGrouped(i) || rapidGrouped(i - 1)) return l.start;
        long early = (long) l.start - LEAD_MS;
        if (i == 0) return early;
        long prevEnd = lines.get(i - 1).end;
        return Math.min(l.start, Math.max(early, prevEnd));
    }

    private float blurFor(int rows) {
        // The neighbours only just soft, so the next line still reads; then clearly out of focus.
        if (rows <= 0) return 0f;
        return (BLUR_NEXT_DP + BLUR_DP_PER_ROW * (Math.min(BLUR_MAX_ROWS, rows) - 1)) * density;
    }

    /** How many rows from the focus a line counts as - lines above count one further. */
    private int rowsFromFocus(int i) {
        if (dotsFor >= 0) return i >= dotsFor ? i - dotsFor + 1 : dotsFor - i + 1;
        if (retained(i)) return 0;
        if (i == focus) return 0;
        return i > focus ? i - focus : focus - i + 1;
    }

    /** The blur a line is heading for: none on the focus, then by distance. */
    private float blurTarget(int i) {
        if (simultaneousVisible(i)) return 0f;
        return blurFor(rowsFromFocus(i));
    }

    private boolean moving(int i) {
        return aim[i] != nextAim[i] || scroll[i] != aim[i] || vel[i] != 0f;
    }

    /**
     * How visible the lyrics should be: the card's own progress into the cover look (so they
     * arrive and leave with the card's thumbnail), and the clock container's alpha (so they go
     * wherever the OEM fades the clock - the bouncer, the shade over the lock screen).
     */
    private float showTarget() {
        if (!LockLyrics.wantsShown() || !bandOk || lines.isEmpty()) return 0f;
        // With the HDR highlight the lyrics are a window of their own above the shade window,
        // and the control centre pulled over the lock screen is drawn in the shade window - so
        // its blur, which takes what is under it, can never reach them: they stood sharp over
        // it (#52). They make way for it instead, and come back as it goes. Without the
        // highlight they are in the keyguard's tree and blurred with it, as before.
        if (LyricWindow.owns(this) && Main.controlCenterShown()) {
            centreHeld = true;
            return 0f;
        }
        float v = clamp01(Main.cardProgress());
        View c = Main.sContainer;
        if (c != null) v *= clamp01(c.getAlpha());
        return v;
    }

    /** The lyrics went out for the control centre and have not come all the way back yet. */
    private boolean centreHeld;

    /** keyguard_info_layer, the view the full AOD dims - the one the lyrics take their alpha from. */
    private View dimSource;

    /**
     * The alpha the full always-on display is currently dimming the keyguard by.
     *
     * The OEM applies that dim to six views by name and this one is not among them: the lyrics
     * live in `keyguard_foreground_layer`, a sibling of `keyguard_info_layer` under the same
     * `constraintLayout` (KeyguardPanelViewController 1239-1262 and 5665-5674). Left alone they
     * would sit at full brightness over a screen that had just darkened itself.
     *
     * Read from that sibling rather than from a constant so the 500ms descent is followed frame
     * for frame, and looked up again whenever it is not attached - the keyguard is rebuilt.
     */
    private float dimTarget() {
        if (!LockLyrics.inHeldAod()) return swipeFade();
        View s = dimSource;
        if (s == null || !s.isAttachedToWindow()) {
            View root = keyguardRoot();
            int id = getContext().getResources()
                    .getIdentifier("keyguard_info_layer", "id", "com.android.systemui");
            s = id == 0 || root == null ? null : root.findViewById(id);
            dimSource = s;
        }
        return s == null ? 1f : s.getTransitionAlpha();
    }

    /**
     * The media card's container, whose fade and zoom a swipe up puts on the media card and not
     * on this layer - see swipeFade and followSwipeZoom.
     */
    private View swipeSource;

    private View swipeSource() {
        View s = swipeSource;
        if (s == null || !s.isAttachedToWindow()) {
            View root = keyguardRoot();
            int id = getContext().getResources()
                    .getIdentifier("shared_notification_container", "id", "com.android.systemui");
            s = id == 0 || root == null ? null : root.findViewById(id);
            swipeSource = s;
        }
        return s;
    }

    /**
     * The swipe's fade, taken from the media card's, as the square card does
     * (CoverCardLayer.followSwipeFade): the OEM fades the media card's and the clock's containers
     * through transitionAlpha, and nothing above this layer (`op alphasweep`, 2026-09-24). Under
     * the pad the lyrics stay and blur instead (followBouncer). Not in the doze: the doze clock
     * is not fading, and a held doze dims through keyguard_info_layer above.
     */
    private float swipeFade() {
        if (ClockCollapse.phase() == ClockCollapse.Phase.AOD) return 1f;
        View s = swipeSource();
        float t = s == null ? 1f : s.getTransitionAlpha();
        // In a window of its own the page is above the pad, not under it (LyricWindow), so
        // staying and blurring would put blurred lines over the digits: it goes with the pad.
        if (LyricWindow.owns(this)) return t * (1f - bouncerP);
        return s == null ? 1f : Math.max(t, bouncerP);
    }

    /**
     * The keyguard's root, where the card's container and the AOD's dim layer are looked up: this
     * view's own as a child of the keyguard, the lock screen window's when this view is a window
     * of its own (LyricWindow), whose tree holds nothing but it.
     */
    private View keyguardRoot() {
        return LyricWindow.owns(this) ? LyricWindow.hostRoot() : getRootView();
    }

    /**
     * The keyguard's own alpha and visibility, which a child inherits and a window does not. See
     * LyricWindow.hostAlpha. 1 as a child, so that path is exactly what it always was.
     */
    private boolean followHostAlpha() {
        float want = LyricWindow.owns(this) ? LyricWindow.hostAlpha() : 1f;
        if (getAlpha() == want) return false;
        setAlpha(want);
        return true;
    }

    /**
     * The swipe's zoom, taken from the media card's. A swipe up scales the media card's container
     * (0.943 about y=1043 in that sweep) and not this layer, so the text kept its size while the
     * clock and the card shrank round it. This view gets the part of the container's scale its
     * own ancestors lack, about the same point on screen - 1 whenever the two agree, which is
     * the doze, where both carry the keyguard's 0.95. The band is laid out through
     * ClockCollapse.unzoomY, which reads this view's own scale too, so the lines still sit
     * against the clock as drawn.
     */
    private boolean followSwipeZoom() {
        float want = 1f, pivotX = getPivotX(), pivotY = getPivotY();
        boolean aod = ClockCollapse.phase() == ClockCollapse.Phase.AOD;
        View s = aod ? null : swipeSource();
        if (!LyricWindow.owns(this)) {
            if (s != null && getParent() instanceof View) {
                View parent = (View) getParent();
                float sc = chainScaleY(s), sp = chainScaleY(parent);
                if (sp > 0f && Math.abs(sc / sp - 1f) > 1e-3f) {
                    want = sc / sp;
                    // The container's pivot on screen, and where that point is in this view.
                    s.getLocationOnScreen(loc);
                    float sx = loc[0] + chainScaleX(s) * s.getPivotX();
                    float sy = loc[1] + sc * s.getPivotY();
                    parent.getLocationOnScreen(loc);
                    float spx = chainScaleX(parent);
                    pivotX = spx > 0f ? (sx - loc[0]) / spx - getLeft() - getTranslationX() : pivotX;
                    pivotY = (sy - loc[1]) / sp - getTop() - getTranslationY();
                }
            }
        } else {
            // A window of its own (LyricWindow): nothing above this view carries any of the
            // keyguard's zoom, so the whole of it is applied here - the card container's, which
            // already includes the keyguard's own since the container is under it, or with no
            // container (the doze) the keyguard's alone, which used to arrive as an ancestor's.
            float sc = s == null ? Float.NaN : chainScaleY(s);
            View host = LyricWindow.host();
            if (!Float.isNaN(sc) && Math.abs(sc - 1f) > 1e-3f) {
                want = sc;
                // The container's pivot on screen, and where that point is in this view.
                s.getLocationOnScreen(loc);
                float sx = loc[0] + chainScaleX(s) * s.getPivotX();
                float sy = loc[1] + sc * s.getPivotY();
                // The WINDOW's origin on screen, not this view's: this view's own location
                // already has its scale about its current pivot in it, and taking the pivot from
                // it fed each frame's pivot into the next - it swung for a few frames and settled
                // at sy/(2-sc) rather than sy. Screen minus window location is the window's
                // offset exactly, both being rounded from the same point.
                getLocationOnScreen(loc);
                getLocationInWindow(winLoc);
                pivotX = sx - (loc[0] - winLoc[0]) - getLeft() - getTranslationX();
                pivotY = sy - (loc[1] - winLoc[1]) - getTop() - getTranslationY();
            } else if (host != null) {
                // The keyguard's own zoom - 0.95 on keyguard_root_view in the doze, and on its
                // way back to 1 through a wake. Uniform, about the column's own centre for x (the
                // lines are centred) and this view's top for y. Where that puts the lines is not
                // a guess: the band is laid out through ClockCollapse.unzoomY, which inverts this
                // view's scale about wherever it puts this view's origin, so the lines still sit
                // against the clock and the card as drawn. The composite of the OEM's scales
                // about their own pivots is one scale about this corner, which is the only
                // difference, and it is in `op lyricstate` (`win=... scale= pivot=`).
                float k = chainScaleY(host);
                if (Math.abs(k - 1f) > 1e-3f) {
                    want = k;
                    pivotX = getWidth() / 2f;
                    pivotY = 0f;
                }
            }
        }
        if (getScaleY() == want && (want == 1f
                || (getPivotX() == pivotX && getPivotY() == pivotY))) {
            return false;
        }
        if (want != 1f) {
            setPivotX(pivotX);
            setPivotY(pivotY);
        }
        setScaleX(want);
        setScaleY(want);
        return true;
    }

    private final int[] winLoc = new int[2];

    private static float chainScaleY(View v) {
        float k = 1f;
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) k *= ((View) p).getScaleY();
        return k;
    }

    private static float chainScaleX(View v) {
        float k = 1f;
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) k *= ((View) p).getScaleX();
        return k;
    }

    /** @return whether it moved, so a dim of the keyguard keeps this view asking for frames */
    private boolean followAodDim() {
        float want = dimTarget();
        if (getTransitionAlpha() == want) return false;
        setTransitionAlpha(want);
        return true;
    }

    /**
     * How far the bouncer's blur has come over the lyrics, 0..1.
     *
     * The OEM blurs the lock screen under the PIN pad, but the lyrics live in
     * keyguard_foreground_layer and are not among what it blurs, and the clock container they
     * take their alpha from stays shown with the pad up (see Main.bouncerShown) - so they stood
     * sharp over a blurred screen. This blurs them itself, eased in and out with the pad.
     */
    private float bouncerP;
    private static final float BOUNCER_BLUR_DP = 24f;
    /**
     * Time constant of the ease, in seconds. Short: the level is the pad's own fade already
     * (Main.bouncerLevel), so this only smooths it. At 80ms it trailed the OEM's blur both ways.
     */
    private static final float BOUNCER_TAU = 0.03f;

    /** @return whether the blur moved, so the pad coming up keeps this view asking for frames */
    private boolean followBouncer(float dt) {
        float want = Main.bouncerLevel();
        if (bouncerP == want) return false;
        // The first step of a frame has no dt; it still has to start moving.
        float k = dt <= 0f ? 0.25f : (float) (1.0 - Math.exp(-dt / BOUNCER_TAU));
        bouncerP += (want - bouncerP) * k;
        if (Math.abs(want - bouncerP) < 0.01f) bouncerP = want;
        applyBlur();
        return true;
    }

    private float blurSet = -1f;

    /** The view's one blur, whichever of the PIN pad and the pop asks for more. */
    private void applyBlur() {
        float r = Math.max(bouncerP * BOUNCER_BLUR_DP * density, popBlur);
        if (r < 0.5f) r = 0f;
        if (r == blurSet) return;
        blurSet = r;
        setRenderEffect(r == 0f ? null : android.graphics.RenderEffect.createBlurEffect(
                r, r, Shader.TileMode.DECAL));
    }

    /** One damped spring, stepped per frame so a turn keeps its speed. */
    private static final class Spring {
        float x, v;

        Spring() {
        }

        Spring(float at) {
            x = at;
        }

        void set(float at) {
            x = at;
            v = 0f;
        }

        /** Semi-implicit Euler in steps of at most 4ms: the stiffest (0.1s) is w*h = 0.25. */
        void step(float to, float dt, float[] rb) {
            if (dt <= 0f) return;
            double w = 2 * Math.PI / rb[0];
            double k = w * w, c = 2 * Math.max(0.0, 1.0 - rb[1]) * w;
            int n = Math.max(1, (int) Math.ceil(dt / 0.004f));
            float h = dt / n;
            for (int i = 0; i < n; i++) {
                v += (float) (-k * (x - to) - c * v) * h;
                x += v * h;
            }
        }

        /** Within eps of `to` and moving less than eps a frame: snapped there. */
        boolean settle(float to, float eps) {
            if (Math.abs(x - to) >= eps || Math.abs(v) * 0.016f >= eps) return false;
            set(to);
            return true;
        }
    }

    /**
     * Where the pop goes next: out of the island on an arrival from it, into it on a departure
     * towards it, and round again either way. Screen points from LockLyrics, each taken once.
     *
     * @return whether anything changed
     */
    private boolean turnPop(long now, float showTo, boolean cut) {
        if (cut) {
            // Unlocked, or the AOD's still frames: nothing animates there, the page is simply cut.
            if (popMode == POP_NONE) return false;
            endPop("cut");
            return true;
        }
        switch (popMode) {
            case POP_NONE:
                if (showTo > 0f && show < 0.02f && bandOk && LockLyrics.takePopOrigin(popPoint)) {
                    popFrom(popPoint);
                    return true;
                }
                if (showTo == 0f && show > 0.02f && bandOk && LockLyrics.takePopTarget(popPoint)) {
                    popInto(now, popPoint);
                    return true;
                }
                return false;
            case POP_IN:
                if (showTo > 0f) return false;
                if (LockLyrics.takePopTarget(popPoint)) {
                    popInto(now, popPoint);
                } else {
                    popMode = POP_HOLD;
                    Xp.log(TAG + "island pop held: left another way");
                }
                return true;
            case POP_HOLD:
                if (showTo > show) {
                    popMode = POP_IN;
                    LockLyrics.takePopOrigin(popPoint);
                    Xp.log(TAG + "island pop resumed");
                    return true;
                }
                if (showTo == 0f && LockLyrics.takePopTarget(popPoint)) {
                    popInto(now, popPoint);
                    return true;
                }
                return false;
            case POP_OUT:
                if (showTo == 0f) return false;
                // Turned round on its way into the island: home from where it is, as fast as it
                // is going. The entry's own origin is this one's, already on screen.
                LockLyrics.takePopOrigin(popPoint);
                popMode = POP_IN;
                Xp.log(TAG + "island pop turned back out at s=" + r2(popS.x) + " a=" + r2(popA.x));
                return true;
            default:
                return false;
        }
    }

    /** The home the springs are measured from: the band's centre, in this view's pixels. */
    private float homeY() {
        return (bandTop + bandBottom) / 2f;
    }

    /** An arrival from the island: everything put on it, then sprung home. */
    private void popFrom(float[] onScreen) {
        getLocationOnScreen(loc);
        popX.set(onScreen[0] - loc[0] - getWidth() / 2f);
        popY.set(onScreen[1] - loc[1] - homeY());
        popS.set(POP_SCALE_IN);
        popA.set(0f);
        popB.set(POP_BLUR_DP * density);
        popMode = POP_IN;
        popped = true;
        applyPop();
        Xp.log(TAG + "island pop out of y=" + Math.round(onScreen[1])
                + " band " + Math.round(bandTop) + ".." + Math.round(bandBottom));
    }

    /** A departure into the island, from wherever the block is and at whatever speed. */
    private void popInto(long now, float[] onScreen) {
        getLocationOnScreen(loc);
        popToX = onScreen[0] - loc[0] - getWidth() / 2f;
        popToY = onScreen[1] - loc[1] - homeY();
        popOutAt = now;
        popMode = POP_OUT;
        popped = true;
        Xp.log(TAG + "island pop into y=" + Math.round(onScreen[1]) + " from s=" + r2(popS.x)
                + " a=" + r2(popA.x));
    }

    private void endPop(String why) {
        if (popMode == POP_OUT) show = 0f;
        popMode = POP_NONE;
        popX.set(0f);
        popY.set(0f);
        popS.set(1f);
        popA.set(1f);
        popB.set(0f);
        applyPop();
        Xp.log(TAG + "island pop done: " + why);
    }

    /** @return whether the pop moved this frame */
    private boolean stepPop(long now, float dt, float showTo) {
        if (popMode == POP_NONE) {
            // The showing is over: the next is a new one, from the island or not.
            if (popped && show == 0f) popped = false;
            return false;
        }
        if (popMode == POP_HOLD) {
            if (show > 0f) return false;
            endPop("faded");
            return true;
        }
        if (popMode == POP_IN) {
            popX.step(0f, dt, POP_IN_MOVE);
            popY.step(0f, dt, POP_IN_MOVE);
            popS.step(1f, dt, POP_IN_SCALE);
            popA.step(1f, dt, POP_IN_ALPHA);
            popB.step(0f, dt, POP_IN_BLUR);
            float px = 0.5f;
            boolean home = popX.settle(0f, px) & popY.settle(0f, px) & popS.settle(1f, 0.001f)
                    & popA.settle(1f, 0.002f) & popB.settle(0f, 0.3f);
            applyPop();
            if (home && show >= showTo) {
                popMode = POP_NONE;
                Xp.log(TAG + "island pop landed");
            }
            return true;
        }
        popX.step(popToX, dt, POP_OUT_MOVE);
        popY.step(popToY, dt, POP_OUT_MOVE);
        popS.step(POP_SCALE_OUT, dt, POP_OUT_SCALE);
        popA.step(0f, dt, POP_OUT_ALPHA);
        popB.step(POP_BLUR_DP * density, dt, POP_OUT_BLUR);
        applyPop();
        if (popA.x <= POP_OUT_GONE && popA.v <= 0f) endPop("in the island");
        else if (now - popOutAt > POP_OUT_MAX_MS) endPop("timed out");
        return true;
    }

    private void applyPop() {
        popBlur = Math.max(0f, popB.x);
        applyBlur();
    }

    private boolean needsFrames() {
        if (!isAttachedToWindow()) return false;
        if (show != showTarget()) return true;
        if (popMode == POP_IN || popMode == POP_OUT) return true;
        // The keyguard dimming around us is a movement like any other, and so is the keyguard
        // coming back: without this the loop would stop the moment the words settled and leave
        // the lyrics at whatever alpha the AOD had put them at - dimmed on a lit screen, or at
        // full brightness on one that has just dimmed itself. Asked in both directions, because
        // dimTarget() is 1 outside the AOD and the frame that wakes the keyguard may change
        // nothing else.
        if (getTransitionAlpha() != dimTarget()) return true;
        if (bouncerP != Main.bouncerLevel()) return true;
        // The block sliding to a new centre is a movement like any other, and the slowest one
        // here: without this the loop would stop the moment the springs settled and leave the
        // correction half way.
        if (anchorFix != anchorFixWant) return true;
        ClockCollapse.Phase p = ClockCollapse.phase();
        if (p == ClockCollapse.Phase.ENTER || p == ClockCollapse.Phase.EXIT) return true;
        if (lines.isEmpty() || focus < 0 || show == 0f) return false;
        int n = lines.size();
        int lo = Math.max(0, focus - 6), hi = Math.min(n - 1, focus + 12);
        for (int i = lo; i <= hi; i++) {
            if (moving(i)) return true;
            float e = emph[i];
            if (e != 0f && e != 1f) return true;
            float lv = lit[i];
            if (lv != 0f && lv != 1f) return true;
            if (scale[i] != 1f && scale[i] != INACTIVE_SCALE) return true;
            if (now() < aimAt[i]) return true;
            if (blur[i] != blurTarget(i)) return true;
        }
        // Playback effects use vsync frames while the display is active. AOD still mode instead
        // uses its scheduled single-frame checkpoints, including Eye-candy's throttled word steps.
        if (LockLyrics.still()) return false;
        return wordsLive() || dotsLive() || audioLive() || translationLive(now());
    }

    /** Inside a frame, that frame's vsync time; outside one, the uptime clock it is based on. */
    private static long now() {
        return android.view.animation.AnimationUtils.currentAnimationTimeMillis();
    }

    private float smoothMs;
    private long smoothAt;

    /**
     * The session's position at this frame's time, eased rather than jumped when a fresh read of
     * the session disagrees with the extrapolation by a little. The tick re-reads it every second
     * and players round and batch what they report, so the raw number steps by tens of ms now
     * and then - a visible hitch in the word fill. A real jump (a seek) is still taken at once.
     */
    private int smoothPosition(long now) {
        float raw = LockLyrics.positionMs() + (now - SystemClock.uptimeMillis());
        if (smoothAt == 0L || !LockLyrics.playing()) {
            // Paused counts too: the progress bar can be dragged while paused, and that is still
            // a seek even though nothing is advancing between frames to compare against.
            if (smoothAt != 0L && Math.abs(raw - smoothMs) > JUMP_MS) {
                jumpedAt = now;
            }
            smoothMs = raw;
        } else {
            float pred = smoothMs + (now - smoothAt);
            float err = raw - pred;
            if (Math.abs(err) > JUMP_MS) {
                jumpedAt = now;
                smoothMs = raw;
            } else {
                smoothMs = pred + err * Math.min(1f, (now - smoothAt) / 300f);
            }
        }
        smoothAt = now;
        return smoothMs < 0f ? 0 : Math.round(smoothMs);
    }

    /** More than playback alone can explain between two reads: somebody moved the playhead. */
    private static final float JUMP_MS = 250f;

    /**
     * How long after a jump a focus change still counts as part of it.
     *
     * Not just the one frame the jump was noticed on. A drag lands the position first and the
     * focus catches up a frame or two later, and a seek whose scroll animates because the focus
     * moved one frame too late is exactly the inconsistency this window closes.
     */
    private static final long SEEK_WINDOW_MS = 250L;

    /** When the playhead last moved by more than playing could account for. */
    private long jumpedAt = Long.MIN_VALUE;

    /** The interlude dots are up, or about to be. */
    private boolean dotsLive() {
        // Not while paused: nothing about them moves then, and this kept the whole keyguard
        // window redrawing at the refresh rate for as long as the pause lasted.
        return dotsFor >= 0 && show > 0f && LockLyrics.playing();
    }

    /** A held note is glowing now, or is about to - the window's HDR mode follows this. */
    private boolean glowSoon() {
        if (!LockLyrics.sHdr || focus < 0 || show == 0f || !LockLyrics.playing()) return false;
        for (int i = 0; i < lines.size(); i++) {
            LyricLine l = lines.get(i);
            if (!l.hasWords() || emph[i] <= 0f || !simultaneousVisible(i)) continue;
            if (!l.hasDisplayWords() || emph[i] <= 0f) continue;
            for (int k = 0; k < l.sylStart.length; k++) {
                int s = l.sylStart[k], end = l.sylEnd[k];
                if (end - s >= GLOW_MIN_MS && ms >= s - HDR_ARM_MS && ms < end + GLOW_TAIL_MS) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A singing line's words are moving: the fill, the lift, a held note's glow. */
    private boolean wordsLive() {
        if (!LockLyrics.playing() || LockLyrics.inHeldAod() || focus < 0 || show == 0f) return false;
        int tail = AliveLyricsEffects.enabled(LockLyrics.sAliveFx)
                ? Math.max(LIFT_HOLD_MS + LIFT_RETURN_MS, GLOW_TAIL_MS) : 0;
        if (AliveLyricsEffects.eyeCandy(LockLyrics.sAliveFx)) {
            tail = Math.max(tail, EYE_WORD_FLY_MS);
        }
        for (int i = 0; i < lines.size(); i++) {
            LyricLine l = lines.get(i);
            if (l.hasWords() && emph[i] > 0f && simultaneousVisible(i)
                    && ms >= l.start && ms < l.end + tail) {
                return true;
            }
        }
        return false;
    }

    private boolean pulseWanted() {
        return AliveLyricsEffects.enabled(LockLyrics.sAliveFx)
                && LockLyrics.playing() && Main.screenOnCached()
                && !LockLyrics.still() && !LockLyrics.inHeldAod() && LockLyrics.wantsShown()
                && isAttachedToWindow() && isShown() && getWindowVisibility() == View.VISIBLE
                && show > 0.01f && audioVisible() && !lines.isEmpty();
    }

    private boolean audioWanted() {
        return pulseWanted() && LockLyrics.sAudioReactive
                && PlaybackPcmCapture.supports(LockLyrics.activePlayerPackage());
    }

    private boolean audioVisible() {
        View view = this;
        while (view != null) {
            if (view.getAlpha() * view.getTransitionAlpha() <= 0.01f) return false;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return true;
    }

    private void stopAudio() {
        removeCallbacks(audioHeartbeat);
        PlaybackPcmCapture.setOnSample(null);
        audioLeaseRunning = false;
        pulseScale = 1f;
        pcmLevel = pcmTransient = 0f;
        PlaybackPcmCapture.stop(getContext());
    }

    /** Stop PCM capture and clear its visual state immediately. */
    void audioReactiveDisabled() {
        stopAudio();
        kick();
    }

    private boolean followAudio(float dt) {
        float before = pulseScale;
        float beforeLevel = pcmLevel, beforeTransient = pcmTransient;
        boolean capture = audioWanted();
        if (!LockLyrics.sAudioReactive) {
            if (audioLeaseRunning) stopAudio();
            pulseScale = 1f;
            pcmLevel = pcmTransient = 0f;
            return before != 1f || beforeLevel != 0f || beforeTransient != 0f;
        }
        if (!capture && audioLeaseRunning) stopAudio();
        if (!pulseWanted()) {
            pulseScale = 1f;
            pcmLevel = pcmTransient = 0f;
        } else {
            if (capture && !audioLeaseRunning) {
                audioLeaseRunning = true;
                PlaybackPcmCapture.setOnSample(audioChanged);
                post(audioHeartbeat);
            }
            boolean fresh = capture && PlaybackPcmCapture.hasFreshSample();
            long sample = fresh ? PlaybackPcmCapture.sample() : 0L;
            float rms = fresh ? PlaybackAudioState.rms(sample) : 0f;
            float peak = fresh ? PlaybackAudioState.peak(sample) : 0f;
            float levelTarget = fresh ? AliveLyricsEffects.audioLevel(rms) : 0f;
            float transientTarget = fresh ? AliveLyricsEffects.audioTransient(rms, peak) : 0f;
            pcmLevel = approach(pcmLevel, levelTarget, dt,
                    levelTarget > pcmLevel ? 0.045f : 0.18f);
            pcmTransient = approach(pcmTransient, transientTarget, dt,
                    transientTarget > pcmTransient ? 0.025f : 0.14f);

            float target = 1f;
            if (focus >= 0 && dotsFor < 0) {
                if (fresh) {
                    LyricLine line = lines.get(focus);
                    if (line.hasWords()) {
                        // Word timing anchors the effect locally; avoid scaling the entire line.
                        target = 1f;
                    } else {
                        target = AliveLyricsEffects.audioScale(true, true, false,
                                pcmLevel, pcmTransient);
                    }
                } else {
                    LyricLine line = lines.get(focus);
                    int mode = AliveLyricsEffects.enabled(LockLyrics.sAliveFx)
                            ? LockLyrics.sAliveFx : AliveLyricsEffects.SUBTLE;
                    target = AliveLyricsEffects.breathingScale(mode, true, true, false,
                            ms, line.start, line.end, line.hasWords() ? line.sungChars(ms) : 0f);
                }
            }
            pulseScale = approach(pulseScale, target, dt, target > pulseScale ? 0.06f : 0.22f);
            if (Math.abs(target - pulseScale) < 0.00001f) pulseScale = target;
        }
        return before != pulseScale || Math.abs(beforeLevel - pcmLevel) > 0.0001f
                || Math.abs(beforeTransient - pcmTransient) > 0.0001f;
    }

    private boolean audioLive() {
        return LockLyrics.sAudioReactive && pulseWanted() && focus >= 0 && dotsFor < 0
                && (!audioWanted() || !PlaybackPcmCapture.hasFreshSample()
                || pulseScale > 1.00001f || pcmLevel > 0.001f || pcmTransient > 0.001f);
    }

    /** A translation that has started fading in still needs frames until it settles. */
    private boolean translationLive(long now) {
        if (!AliveLyricsEffects.enabled(LockLyrics.sAliveFx) || LockLyrics.still()
                || !LockLyrics.playing()) return false;
        for (long at : transFadeAt) {
            if (AliveLyricsEffects.translationNeedsFrame(LockLyrics.sAliveFx, true, false, at, now)) {
                return true;
            }
        }
        return false;
    }

    private static float approach(float v, float to, float dt, float tau) {
        if (dt <= 0f) return v;
        return v + (to - v) * (1f - (float) Math.exp(-dt / tau));
    }

    private int indexAt(int t) {
        int lo = 0, hi = lines.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).start <= t) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    private void snap(float target) {
        for (int i = 0; i < scroll.length; i++) {
            scroll[i] = aim[i] = nextAim[i] = target;
            vel[i] = 0f;
            emph[i] = 0f;
            lit[i] = 0f;
            scale[i] = INACTIVE_SCALE;
            blur[i] = 0f;
        }
    }

    /**
     * New lines, or a new width: lay every line out once. Returns whether the new layout is in
     * place already - only an empty one is; the rest is laid out on a thread of its own and put
     * in when it comes back, with the old lines standing until then.
     *
     * It used to be done right here in the frame, and a whole song is a StaticLayout per line,
     * per background vocal and per translation, all with the balanced breaker - the frame the
     * lyrics were meant to start fading in on, switched on or on a new song, was the one that
     * stalled.
     */
    private boolean layOut() {
        final int v = LockLyrics.version();
        final int width = getWidth();
        final int transOn = LockLyrics.below();
        final int alignOn = LockLyrics.sAlign;
        final LyricStyle style = LockLyrics.sStyle;
        final List<LyricLine> ls = LockLyrics.lines();
        if (!ls.isEmpty() && v == wantVersion && width == wantWidth && transOn == wantTrans
                && alignOn == wantAlign && style.sameLayout(wantStyle)) return false;
        final float buildTextPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                style.sizeSp, getResources().getDisplayMetrics());
        final float buildSidePx = style.sidePx(width, shortSide(), density, buildTextPx);
        // Copies, including the requested typography: the current paints keep drawing the old
        // layout until the replacement is ready. A rapid slider drag cannot mix both styles.
        final TextPaint p = new TextPaint(paint);
        p.setTextSize(buildTextPx);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, style.weight, false));
        final TextPaint bp = new TextPaint(bgPaint);
        bp.setTextSize(buildTextPx * BG_SP / TEXT_SP);
        final TextPaint tp = new TextPaint(transPaint);
        tp.setTextSize(buildTextPx * TRANS_SP / TEXT_SP);
        final TextPaint op = new TextPaint(onlineTransPaint);
        op.setTextSize(buildTextPx * ONLINE_TRANS_SP / TEXT_SP);
        if (ls.isEmpty()) {
            wantVersion = wantWidth = -1;
            wantTrans = transOn;
            wantAlign = alignOn;
            wantStyle = null;
            apply(build(v, width, ls, p, bp, tp, op, transOn, alignOn, style,
                    buildTextPx, buildSidePx));
            return true;
        }
        wantVersion = v;
        wantWidth = width;
        wantTrans = transOn;
        wantAlign = alignOn;
        wantStyle = style;
        layoutHandler().post(new Runnable() {
            @Override
            public void run() {
                final Built b = build(v, width, ls, p, bp, tp, op, transOn, alignOn,
                        style, buildTextPx, buildSidePx);
                post(new Runnable() {
                    @Override
                    public void run() {
                        // Overtaken by a newer request: that one's answer is the one to wait for.
                        if (v != wantVersion || width != wantWidth || transOn != wantTrans
                                || alignOn != wantAlign
                                || !style.sameLayout(wantStyle)) return;
                        wantVersion = wantWidth = -1;
                        wantStyle = null;
                        // Stale by the time it landed, or asked for and then frozen by the lyrics
                        // being switched off: the next step asks again when it is due.
                        if (v != LockLyrics.version() || width != getWidth()
                                || transOn != LockLyrics.below()
                                || alignOn != LockLyrics.sAlign
                                || !style.sameLayout(LockLyrics.sStyle)
                                || !LockLyrics.wantsAttached()) {
                            return;
                        }
                        apply(b);
                        invalidate();
                        kick();
                    }
                });
            }
        });
        return false;
    }

    /** One layout of the lines, made wherever build ran. */
    private static final class Built {
        int version, width, w;
        LyricStyle style;
        /** The translation and romanisation switches this layout was made under; see LockLyrics.below(). */
        int transOn;
        /** The alignment pref this layout was made under; see LockLyrics.sAlign. */
        int align;
        boolean rapidGroups;
        List<LyricLine> lines;
        StaticLayout[] main, trans, onlineTrans, bgLay;
        float[] base, height, dotsTop;
        float[][] charX, charXBg;
        int[][] clusterEnd, clusterEndBg;
        long tookMs;
    }

    /**
     * Where a line settles in its column: the user's choice (one of LockLyrics' ALIGN_
     * constants), except that a duet's second voice keeps to the edge against the other singer.
     * Left and right swap for it rather than converge - the two voices are told apart by being
     * on opposite sides - and the centre is the one answer with no opposite.
     */
    private static Layout.Alignment alignFor(boolean opposite, int align) {
        if (align == LockLyrics.ALIGN_CENTER) return Layout.Alignment.ALIGN_CENTER;
        boolean right = align == LockLyrics.ALIGN_RIGHT;
        if (opposite) right = !right;
        return right ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
    }

    /** Touches nothing of the view's but its constants, so it can run off the UI thread. */
    private Built build(int v, int width, List<LyricLine> ls, TextPaint p, TextPaint bp,
                        TextPaint tp, TextPaint op, int transOn, int alignOn, LyricStyle style,
                        float buildTextPx, float buildSidePx) {
        long t0 = SystemClock.uptimeMillis();
        Built b = new Built();
        b.version = v;
        b.width = width;
        b.style = style;
        b.transOn = transOn;
        b.rapidGroups = LockLyrics.sRapidGroups;
        b.align = alignOn;
        b.lines = ls;
        int n = ls.size();
        int w = Math.max(1, width - Math.round(2f * buildSidePx));
        b.w = w;
        b.main = new StaticLayout[n];
        b.trans = new StaticLayout[n];
        b.onlineTrans = new StaticLayout[n];
        b.bgLay = new StaticLayout[n];
        b.base = new float[n];
        b.height = new float[n];
        b.dotsTop = new float[n];
        b.charX = new float[n][];
        b.charXBg = new float[n][];
        b.clusterEnd = new int[n][];
        b.clusterEndBg = new int[n][];
        float y = 0f;
        float gap = GAP_DP * density;
        RapidLyricGroups groups = b.rapidGroups
                ? new RapidLyricGroups(ls) : null;
        for (int i = 0; i < n; i++) {
            LyricLine l = ls.get(i);
            // A long gap before this line holds the interlude dots, in a slot of their own.
            long gapStart = i == 0 ? 0L : ls.get(i - 1).end;
            if (l.start - gapStart >= LULL_MS) {
                b.dotsTop[i] = y;
                y += DOTS_SLOT_EM * buildTextPx + gap;
            } else {
                b.dotsTop[i] = Float.NaN;
            }
            Layout.Alignment align = alignFor(l.opposite, alignOn);
            String displayText = l.displayText();
            b.main[i] = StaticLayout.Builder.obtain(displayText, 0, displayText.length(), p, w)
                    .setAlignment(align)
                    .setIncludePad(false)
                    .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
                    .build();
            float h = b.main[i].getHeight();
            // The characters' places too, which the first draw of a word-timed line used to
            // measure one getPrimaryHorizontal at a time on the UI thread.
            if (l.hasWords()) {
                b.charX[i] = charXOf(b.main[i], l);
                b.clusterEnd[i] = clusterEndsOf(l.text);
            }
            if (l.hasDisplayWords()) b.charX[i] = charXOf(b.main[i], l);
            if (l.bg != null) {
                b.bgLay[i] = StaticLayout.Builder.obtain(l.bg.text, 0, l.bg.text.length(), bp, w)
                        .setAlignment(align)
                        .setIncludePad(false)
                        .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
                        .build();
                h += BG_GAP_DP * density + b.bgLay[i].getHeight();
                if (l.bg.hasWords()) {
                    b.charXBg[i] = charXOf(b.bgLay[i], l.bg);
                    b.clusterEndBg[i] = clusterEndsOf(l.bg.text);
                }
            }
            // Left out of the layout entirely when the switch is off, rather than laid out and
            // skipped in the draw: the rows it would have taken are most of a line's height, and
            // a gap there would leave every line floating with a hole under it.
            if ((transOn & LockLyrics.BELOW_TRANS) != 0) {
                String nativeSecondary = LockLyrics.sOnlineTranslateMode == LockLyrics.TR_MODE_OFF
                        ? l.translation : null;
                String onlineSecondary = l.onlineTranslation;
                if (nativeSecondary != null && l.roma != null
                        && (transOn & LockLyrics.BELOW_ROMA) != 0) {
                    nativeSecondary += "\n" + l.roma;
                }
                if (onlineSecondary != null && l.roma != null
                        && (transOn & LockLyrics.BELOW_ROMA) != 0) {
                    onlineSecondary += "\n" + l.roma;
                }
                if (nativeSecondary != null) {
                    b.trans[i] = StaticLayout.Builder.obtain(nativeSecondary, 0,
                                    nativeSecondary.length(), tp, w)
                            .setAlignment(align)
                            .setIncludePad(false)
                            .build();
                    h += TRANS_GAP_DP * density + b.trans[i].getHeight();
                    if (onlineSecondary != null) {
                        b.onlineTrans[i] = StaticLayout.Builder.obtain(onlineSecondary, 0,
                                        onlineSecondary.length(), op, w)
                                .setAlignment(align)
                                .setIncludePad(false)
                                .build();
                        h += ONLINE_TRANS_GAP_DP * density + b.onlineTrans[i].getHeight();
                    }
                } else if (onlineSecondary != null) {
                    // With no native secondary, use the normal translation row and size.
                    b.trans[i] = StaticLayout.Builder.obtain(onlineSecondary, 0,
                                    onlineSecondary.length(), tp, w)
                            .setAlignment(align)
                            .setIncludePad(false)
                            .build();
                    h += TRANS_GAP_DP * density + b.trans[i].getHeight();
                }
            }
            String under = l.under(transOn);
            if (under != null && b.trans[i] == null && b.onlineTrans[i] == null) {
                b.trans[i] = StaticLayout.Builder.obtain(under, 0, under.length(), tp, w)
                        .setAlignment(align)
                        .setIncludePad(false)
                        .build();
                h += TRANS_GAP_DP * density + b.trans[i].getHeight();
            }
            b.base[i] = y;
            b.height[i] = h;
            float nextGap = groups != null && i + 1 < n
                    && groups.grouped(i) && groups.grouped(i + 1)
                    ? RAPID_GAP_DP * density : gap;
            y += h + nextGap;
        }
        b.tookMs = SystemClock.uptimeMillis() - t0;
        return b;
    }

    /** Puts a finished layout in, and starts every line's animated state over. UI thread. */
    private void apply(Built b) {
        int oldVersion = version;
        // The same lines with translations borrowed after they went up (LyricSource
        // .borrowTranslations): nothing to start over, so the lines carry on where they are.
        boolean reveal = gainsTranslations(lines, b.lines) && b.width == layoutWidth
                && b.align == builtAlign && focus >= 0 && scroll.length == b.lines.size();
        float[] oldBase = base;
        applyPaintStyle(b.style);
        version = b.version;
        builtTrans = b.transOn;
        builtRapidGroups = b.rapidGroups;
        builtAlign = b.align;
        lines = b.lines;
        rapidGroups = new RapidLyricGroups(lines);
        layoutWidth = b.width;
        layoutMs = b.tookMs;
        buildGen++;
        blurPending.clear();
        int n = lines.size();
        main = b.main;
        trans = b.trans;
        onlineTrans = b.onlineTrans;
        bgLay = b.bgLay;
        charXBg = b.charXBg;
        base = b.base;
        height = b.height;
        charX = b.charX;
        clusterEnd = b.clusterEnd;
        clusterEndBg = b.clusterEndBg;
        dotsTop = b.dotsTop;
        blurBmp = new Bitmap[n][BLUR_MAX_ROWS];
        if (reveal) {
            readingRange(focus, dotsFor);
            revealTranslations(oldBase);
            Xp.log(TAG + "translations borrowed into " + n + " lines, sliding them in");
            return;
        }
        transRevealAt = 0L;
        scroll = new float[n];
        vel = new float[n];
        aim = new float[n];
        nextAim = new float[n];
        aimAt = new long[n];
        emph = new float[n];
        lit = new float[n];
        scale = new float[n];
        java.util.Arrays.fill(scale, INACTIVE_SCALE);
        blur = new float[n];
        transFadeAt = new long[n];
        boolean fxOn = AliveLyricsEffects.enabled(LockLyrics.sAliveFx) && !LockLyrics.still()
                && oldVersion >= 0;
        for (int i = 0; i < n; i++) transFadeAt[i] = b.trans[i] != null && fxOn ? -1L : 0L;
        focus = -1;
        simultaneousDropped = -1;
        simultaneousTop = simultaneousTopWant = 0f;
        simultaneousTopSet = false;
        simultaneousFirst = simultaneousSecond = simultaneousExitLine = -1;
        simultaneousExitAt = 0L;
        dotsFor = -1;
        readingFirst = readingLast = -1;
        focusKey = Integer.MIN_VALUE;
        if (LockLyrics.verbose || n > 0) {
            Xp.log(TAG + "view laid out " + n + " lines at width " + b.w + " in " + b.tookMs
                    + "ms");
        }
    }

    /**
     * When translations arrived under lines already on screen, for their fade; 0 for none.
     * See revealTranslations.
     */
    private long transRevealAt;
    private static final long TRANS_REVEAL_MS = 450L;

    /** Whether `next` is `now` again, the same words at the same times, with translations added. */
    private static boolean gainsTranslations(List<LyricLine> now, List<LyricLine> next) {
        if (now.isEmpty() || now.size() != next.size()) return false;
        boolean gained = false;
        for (int i = 0; i < now.size(); i++) {
            LyricLine a = now.get(i), b = next.get(i);
            if (a.start != b.start || !a.text.equals(b.text)) return false;
            if (a.translation != null && !a.translation.equals(b.translation)) return false;
            if (a.roma != null && !a.roma.equals(b.roma)) return false;
            if (a.translation == null && b.translation != null) gained = true;
            if (a.roma == null && b.roma != null) gained = true;
        }
        return gained;
    }

    /**
     * Translations borrowed for the lines already up, slid in rather than cut in (#62).
     *
     * The new layout is taller wherever a translation went in, so every line's place moves. Each
     * line is held where it was drawn - its scroll moved by exactly as much as its place did -
     * and then handed the new target the way a line change hands it over: the slow spring, the
     * ripple from the first line on screen. The translations fade in under them meanwhile.
     */
    private void revealTranslations(float[] oldBase) {
        int n = lines.size();
        long now = now();
        if (dotsFor >= 0 && Float.isNaN(dotsTop[dotsFor])) dotsFor = -1;
        float to = dotsFor >= 0 ? dotsTop[dotsFor] : base[readingFirst];
        springK = K_SLOW;
        springC = C_SLOW;
        float anchor = anchorY();
        float delay = 0f;
        for (int i = 0; i < n; i++) {
            float d = base[i] - oldBase[i];
            scroll[i] += d;
            aim[i] += d;
            nextAim[i] = to;
            aimAt[i] = now + Math.round(delay);
            float y = anchor + base[i] - to;
            if (y + height[i] >= bandTop && y <= bandBottom) delay += RIPPLE_MS;
        }
        transRevealAt = now;
        prewarmBlur();
        invalidate();
        kick();
    }

    /** How far in the borrowed translations have faded, 1 once they have or when none were. */
    private float transReveal() {
        if (transRevealAt == 0L) return 1f;
        float p = (now() - transRevealAt) / (float) TRANS_REVEAL_MS;
        if (p >= 1f) return 1f;
        float q = 1f - Math.max(0f, p);
        return 1f - q * q * q;
    }

    private static android.os.Handler sLayoutHandler;

    /**
     * Not the blur thread: that one runs at background priority behind a queue of pictures, and
     * the lyrics wait on this one to appear at all.
     */
    private static synchronized android.os.Handler layoutHandler() {
        if (sLayoutHandler == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("MCLyricLayout");
            t.start();
            sLayoutHandler = new android.os.Handler(t.getLooper());
        }
        return sLayoutHandler;
    }

    /** Where the band between the clock and the card is, in this view's coordinates. */
    private boolean updateBand() {
        // Two getLocationOnScreen walks a frame are not free, and this runs from the keyguard's
        // pre-draw: nothing to show, nothing to measure.
        if (lines.isEmpty() && show == 0f) return false;
        // Both edges as drawn, in this view's unzoomed frame: a swipe zooms the clock's and the
        // card's containers and not this layer. See ClockCollapse.contentBottomFor.
        float clock = ClockCollapse.contentBottomFor(this);
        boolean ok = false;
        float top = bandTop, bottom = bandBottom;
        if (!Float.isNaN(clock) && isAttachedToWindow()) {
            getLocationOnScreen(loc);
            float me = loc[1];
            float floor = bandFloor();
            if (LockLyrics.sStyle.writeBand(clock, floor, density, textPx, bandBounds)) {
                top = bandBounds[0] - me;
                bottom = bandBounds[1] - me;
                ok = bottom - top >= MIN_BAND_ROWS * textPx - 0.01f;
            }
        }
        boolean changed = ok != bandOk
                || Math.abs(top - bandTop) >= 0.5f || Math.abs(bottom - bandBottom) >= 0.5f;
        bandOk = ok;
        if (ok) {
            bandTop = top;
            bandBottom = bottom;
        }
        return changed;
    }

    /**
     * The band's own lower edge, not the card view: the media card's top edge, wherever the card
     * is this frame, whichever of LockLyrics.bandBottomOnScreen()'s routes answers it.
     *
     * It used to be KEPT rather than measured: the lit lock screen's edge was remembered and both
     * the doze and the first second of the wake were placed on that number instead of on the card.
     * That was for a route flip that no longer exists - the fallbacks answered a different edge
     * from the live route, so measuring through a doze moved the band by a whole card and the
     * lyrics jumped on every sleep and every wake (Xiaomi 17 log, 2026-09-24: `band anchored to
     * the live card position` 80ms after every AOD entry).
     *
     * What the holding still costs is the real difference, and it is the one the user sees: the
     * doze's media card is the compact one, and the stack is bottom-anchored, so its top sits
     * lower than the lock screen card's. Measured on the Xiaomi 17, 2026-09-28: 1803 against 1652,
     * 414 tall against 557 - 151px of empty screen the lyrics leave between themselves and the
     * card when the band is held at the lock screen's edge. So the doze measures, and the lyrics
     * follow the card in and out exactly as they follow it while it is up.
     */
    private float bandFloor() {
        return ClockCollapse.unzoomY(this, LockLyrics.bandBottomOnScreen());
    }

    /** Whether there is a block of rows to centre: a measured band, and a focus line inside it. */
    private boolean centring() {
        int n = lines.size();
        return bandOk && focus >= 0 && focus < n && base.length == n && height.length == n;
    }

    /**
     * How far the block wants to sit from the band's uncentred anchor, in this view's pixels.
     *
     * A band holds a whole number of rows and its ends are fixed by the clock and the card, so
     * pinning the singing line to a fraction of the band left the remainder wherever the rows
     * happened to fall - under the first row when the band held only just enough of them, above
     * the last when it did not. Laying the rows the band holds out from the band's middle splits
     * that remainder between the two margins rather than leaving all of it at one of them.
     *
     * Measured off `base` and `height` rather than counted in pitches: a wrapped line is two rows
     * tall and a line with an interlude slot above it sits further down again, so the block is
     * whatever the band holds, not a number of pitch-sized slots. It is measured against the
     * uncentred anchor, and that anchor is a fraction of the band, so the answer is the same for
     * a whole line's travel: this cannot fight the spring, and it changes only when the band or
     * the rows under it do.
     *
     * A correction, not a position, because it is the part of the placement that steps and the
     * band is the part that moves: step() eases this and leaves the band alone.
     */
    private float anchorFixTarget() {
        if (!centring()) return 0f;
        if (simultaneousCount() > 1) return 0f;
        float bandH = bandBottom - bandTop;
        float anchor = bandTop + ANCHOR * bandH;
        if (dotsFor < 0 && readingFirst >= 0 && readingLast > readingFirst) {
            // Reserve the fitting group's space so its first line does not jump at every word.
            float blockH = base[readingLast] - base[readingFirst] + height[readingLast];
            return bandTop + (bandH - blockH) / 2f - anchor;
        }
        int n = lines.size();
        int stageFirst = -1, stageLast = -1, anchorIndex = -1;
        int nearbyLo = Math.max(0, focus - 8), nearbyHi = Math.min(n - 1, focus + 8);
        for (int i = nearbyLo; i <= nearbyHi; i++) {
            LyricLine candidate = lines.get(i);
            boolean singing = ms >= candidate.start && ms < candidate.end
                    && !simultaneousSuppressed(i);
            if (!singing) continue;
            if (i == focus || i > anchorIndex) anchorIndex = i;
            if (stageFirst < 0) stageFirst = i;
            stageFirst = Math.min(stageFirst, i);
            stageLast = Math.max(stageLast, i);
        }
        if (stageFirst < 0) {
            stageFirst = stageLast = focus;
            anchorIndex = focus;
        }
        float stageH = base[stageLast] - base[stageFirst] + height[stageLast];
        return bandTop + (bandH - stageH) / 2f + base[anchorIndex] - base[stageFirst] - anchor;
    }

    /**
     * Where the singing line's layout top goes this frame. Read by the ripple when a line changes
     * and by every draw, always off the value step() settled on for this frame, so the two cannot
     * disagree about where the block is.
     */
    private float anchorY() {
        return bandTop + ANCHOR * (bandBottom - bandTop) + anchorFix;
    }

    private boolean sungNow(int i) {
        return dotsFor < 0 && i >= 0 && i < lines.size()
                && ms >= lines.get(i).start && ms < lines.get(i).end;
    }

    private void updateSimultaneousDrop(int n) {
        int active = 0;
        int oldest = -1;
        int lo = Math.max(0, focus - 12), hi = Math.min(n - 1, focus + 12);
        for (int i = lo; i <= hi; i++) {
            if (!sungNow(i)) continue;
            if (oldest < 0) oldest = i;
            active++;
        }
        if (active == 0) {
            simultaneousDropped = -1;
        } else if (active >= 3 && simultaneousDropped < 0) {
            simultaneousDropped = oldest;
        } else if (simultaneousDropped >= 0
                && (simultaneousDropped >= n
                || ms >= lines.get(simultaneousDropped).end)) {
            simultaneousDropped = -1;
        }
    }

    /** The stage shows the two latest active rows; older overlapping rows are intentionally hidden. */
    private boolean simultaneousVisible(int i) {
        if (!sungNow(i) || simultaneousSuppressed(i)) return false;
        int latest = -1, previous = -1;
        int lo = Math.max(0, focus - 12), hi = Math.min(lines.size() - 1, focus + 12);
        for (int n = lo; n <= hi; n++) {
            if (!sungNow(n)) continue;
            previous = latest;
            latest = n;
        }
        return i == latest || i == previous;
    }

    private boolean simultaneousSuppressed(int i) {
        // Once three voices overlap, keep the oldest one out for the rest of its phrase. This
        // prevents it returning through the normal stack when the middle voice ends first.
        return i == simultaneousDropped && sungNow(i);
    }

    private int simultaneousCount() {
        int count = 0;
        int lo = Math.max(0, focus - 12), hi = Math.min(lines.size() - 1, focus + 12);
        for (int i = lo; i <= hi; i++) if (simultaneousVisible(i)) count++;
        return count;
    }

    private float simultaneousScale() {
        if (simultaneousCount() < 2) return 1f;
        int lo = Math.max(0, focus - 12), hi = Math.min(lines.size() - 1, focus + 12);
        float total = 0f;
        float gap = GAP_DP * density;
        int count = 0;
        for (int i = lo; i <= hi; i++) {
            if (!simultaneousVisible(i)) continue;
            total += height[i];
            count++;
        }
        if (count > 1) total += (count - 1) * gap;
        float usable = Math.max(1f, bandBottom - bandTop - 8f * density);
        return total <= usable ? 1f : Math.max(0.64f, usable / total);
    }

    private float simultaneousHeight(int i) {
        return sungNow(i) && simultaneousCount() > 1
                ? height[i] * simultaneousScale() : height[i];
    }

    private float simultaneousGroupTop() {
        if (simultaneousCount() < 2) return Float.NaN;
        int lo = Math.max(0, focus - 12), hi = Math.min(lines.size() - 1, focus + 12);
        float total = 0f;
        float gap = GAP_DP * density;
        int count = 0;
        int first = -1, second = -1;
        for (int i = lo; i <= hi; i++) {
            if (!simultaneousVisible(i)) continue;
            if (first < 0) first = i;
            else second = i;
            total += height[i];
            count++;
        }
        simultaneousFirst = first;
        simultaneousSecond = second;
        if (count > 1) total += (count - 1) * gap;
        return bandTop + (bandBottom - bandTop - total * simultaneousScale()) * 0.5f;
    }

    /** Start a new duet at the existing singer's current stack position, not at its final center. */
    private float simultaneousEntryTop() {
        int lo = Math.max(0, focus - 12), hi = Math.min(lines.size() - 1, focus + 12);
        float gap = GAP_DP * density;
        float groupScale = simultaneousScale();
        for (int i = lo; i <= hi; i++) {
            if (!simultaneousVisible(i)) continue;
            float current = anchorY() + base[i] - scroll[i];
            float before = 0f;
            for (int j = lo; j < i; j++) {
                if (simultaneousVisible(j)) before += (height[j] + gap) * groupScale;
            }
            return current - before;
        }
        return Float.NaN;
    }

    /**
     * Explicitly packs concurrently sung rows. The normal lyric stack must not be used here:
     * its scroll target is intentionally based on one focus line and would displace a second
     * singer as soon as the focus changes.
     */
    private float simultaneousY(int index) {
        if (!sungNow(index)) return Float.NaN;
        if (simultaneousCount() < 2) {
            if (index != simultaneousExitLine || now() >= simultaneousExitAt) return Float.NaN;
            float p = clamp01((now() - (simultaneousExitAt - SIMULTANEOUS_HANDOFF_MS))
                    / (float) SIMULTANEOUS_HANDOFF_MS);
            float target = anchorY() + base[index] - scroll[index];
            return simultaneousExitFrom + (target - simultaneousExitFrom) * p;
        }
        int lo = Math.max(0, focus - 12), hi = Math.min(lines.size() - 1, focus + 12);
        float gap = GAP_DP * density;
        float total = 0f;
        for (int i = lo; i <= hi; i++) {
            if (sungNow(i)) total += height[i] + gap;
        }
        if (total > 0f) total -= gap;
        float groupScale = simultaneousScale();
        float targetTop = simultaneousTopSet ? simultaneousTop
                : bandTop + (bandBottom - bandTop - total * groupScale) * 0.5f;
        float y = targetTop;
        for (int i = lo; i <= hi; i++) {
            if (!simultaneousVisible(i)) continue;
            if (i == index) return y;
            y += (height[i] + gap) * groupScale;
        }
        return Float.NaN;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        LockLyrics.noteStill("draw");
        long t0 = System.nanoTime();
        drawLyrics(canvas);
        long took = System.nanoTime() - t0;
        drawNsSum += took;
        if (took > drawNsMax) drawNsMax = took;
    }

    private void drawLyrics(Canvas canvas) {
        drawCount++;
        if (show <= 0.003f || lines.isEmpty() || focus < 0 || main.length != lines.size()) return;
        float bandH = bandBottom - bandTop;
        if (bandH <= 0f) return;
        // The float, added to the anchor so the lines, their dots and their edge fades all move
        // as one block: a line's alpha is taken from its own position against the band's edges,
        // and offsetting the canvas instead would have left those alphas describing where the
        // line was going to be rather than where it is.
        // Grown out of the island instead: the pop is the arrival's movement, all of it.
        float anchor = anchorY() + (popped ? 0f : (1f - show) * FLOAT_DP * density);
        float vis = show * clamp01(popA.x);
        float side = sidePx;
        float fade = Math.min(EDGE_FADE_DP * density, bandH / 3f);
        int n = lines.size();
        int revealThrough = AliveLyricsEffects.eyeCandy(LockLyrics.sAliveFx)
                ? indexAt(ms) : n - 1;
        updateTint();

        int save = canvas.save();
        if (popped) {
            // The band as one piece, its clip and edge fades with it, as ColorOS moves the lyric
            // list's view: between the island's centre and home, about the band's centre.
            canvas.translate(popX.x, popY.x);
            float sc = Math.max(0f, popS.x);
            canvas.scale(sc, sc, getWidth() / 2f, homeY());
        }
        canvas.clipRect(0f, bandTop, getWidth(), bandBottom);
        for (int i = Math.max(0, focus - 12); i < n; i++) {
            // The interlude slot above this line, if it has one: its dots move with the line, and
            // sit there dim from the start rather than leaving a hole until their turn.
            if (!Float.isNaN(dotsTop[i])) {
                float dy = anchor + dotsTop[i] - scroll[i];
                if (dy > bandBottom) break;
                float dh = DOTS_SLOT_EM * textPx;
                float dEdge = Math.min(clamp01((dy - bandTop) / fade),
                        clamp01((bandBottom - (dy + dh)) / fade));
                float da = vis * dEdge;
                if (dotsFor >= 0 ? i < dotsFor : i <= focus) da *= ABOVE_ALPHA;
                if (da > 0.003f) drawDots(canvas, i, side, dy, da);
            }
            // Eye-candy never previews a future lyric line before its own timestamp.
            if (i > revealThrough) break;
            if (simultaneousSuppressed(i)) continue;
            float simultaneousTop = simultaneousY(i);
            float y = Float.isNaN(simultaneousTop)
                    ? anchor + base[i] - scroll[i] : simultaneousTop;
            if (y > bandBottom) break;
            float renderedHeight = simultaneousHeight(i);
            if (y + renderedHeight < bandTop) continue;
            // Faded by the row's own top against the top edge and its own bottom against the
            // bottom edge, so a line is already gone by the time it would be cut.
            float edge = Math.min(clamp01((y - bandTop) / fade),
                    clamp01((bandBottom - (y + renderedHeight)) / fade));
            float a = vis * edge;
            // Lines already sung, above the focus, sit further back than the ones to come.
            if ((dotsFor >= 0 ? i < dotsFor : i < focus) && !sungNow(i)) {
                a *= ABOVE_ALPHA;
            }
            if ((dotsFor >= 0 ? i < dotsFor : i < focus) && !retained(i)) a *= ABOVE_ALPHA;
            if (a <= 0.003f) continue;
            drawLine(canvas, i, side, y, a);
        }
        canvas.restoreToCount(save);
    }

    /**
     * The interlude slot before line d: three dots.
     *
     * Before their turn they sit there dim and still, like any line to come. Once the gap starts
     * they light one after another across it (dim at 18%, each lit over about 70% of its third,
     * sizes and spacing off Apple's frames) while the group breathes about its centre - swelling
     * quicker than it shrinks, a whole number of breaths fitted to the gap so the last one ends
     * at rest. Just before the stack moves on they swell a touch and shrink away to nothing.
     * Once their interlude is over they are not drawn again.
     */
    private void drawDots(Canvas c, int d, float x, float top, float alphaMul) {
        long gapStart = d == 0 ? 0L : lines.get(d - 1).end;
        long sw = switchAt(d);
        long appear = gapStart + Math.min(DOT_APPEAR_MS, (long) ((sw - gapStart) * 0.15f));
        long exitEnd = sw - DOT_EXIT_LEAD_MS;
        long exitStart = Math.max(appear, exitEnd - DOT_EXIT_MS);
        if (ms >= exitEnd) return;

        float group = 1f, fade = 1f, u = 0f;
        // Still, they sit at rest and unlit: the breath and the lighting are both movement.
        if (ms >= appear && !LockLyrics.still()) {
            long span = exitStart - appear;
            long t = Math.min(ms, exitStart) - appear;
            u = span <= 0 ? 3f : 3f * t / (float) span;
            if (span > 0) {
                float period = span / (float) Math.max(1, Math.round(span / (float) DOT_BREATH_MS));
                // Started a quarter of the way into the swell, where the curve is at rest level
                // and rising, so the first frame of the interlude matches the still dots.
                float p = (t + period * DOT_INHALE / 2f) / period;
                p -= (float) Math.floor(p);
                float b = p < DOT_INHALE
                        ? -(float) Math.cos(Math.PI * p / DOT_INHALE)
                        : (float) Math.cos(Math.PI * (p - DOT_INHALE) / (1f - DOT_INHALE));
                float depth = clamp01(t / (float) DOT_BREATH_IN_MS);
                depth = 1f - (1f - depth) * (1f - depth);
                group = 1f + DOT_BREATH * depth * b;
            }
            if (ms >= exitStart) {
                float e = clamp01((ms - exitStart) / (float) (exitEnd - exitStart));
                float back = e * e * ((DOT_EXIT_BACK + 1f) * e - DOT_EXIT_BACK);
                group *= Math.max(0f, 1f - back);
                fade = 1f - clamp01((e - 0.55f) / 0.45f);
            }
        }
        if (group <= 0.01f) return;
        float size = DOT_EM * textPx;
        float gap = DOT_GAP_EM * textPx * group;
        // They sit in the slot of the line they lead into, so they go where it goes: the user's
        // alignment, with a duet's second voice against the other edge (see alignFor). Laid out
        // at their rest spacing, which is the anchor the breath moves them around.
        float groupW = size + 2f * DOT_GAP_EM * textPx;
        Layout.Alignment align = alignFor(lines.get(d).opposite, builtAlign);
        float colW = main[d].getWidth();
        float left = align == Layout.Alignment.ALIGN_CENTER ? x + (colW - groupW) / 2f
                : align == Layout.Alignment.ALIGN_OPPOSITE ? x + colW - groupW : x;
        float cx = left + size / 2f + DOT_GAP_EM * textPx;
        float cy = top + DOT_CENTER_EM * textPx;
        float r = size / 2f * group;
        for (int k = 0; k < 3; k++) {
            float litK = clamp01((u - k) / DOT_RAMP);
            float alpha = alphaMul * fade * (DOT_DIM + (1f - DOT_DIM) * litK);
            if (alpha <= 0.003f) continue;
            dotPaint.setColor(ink(alpha, litK));
            c.drawCircle(cx + (k - 1) * gap, cy, r, dotPaint);
        }
    }

    private void drawLine(Canvas canvas, int i, float x, float y, float a) {
        LyricLine l = lines.get(i);
        StaticLayout lay = main[i];
        float e = emph[i];
        float sc = scale[i];
        if (simultaneousVisible(i) && simultaneousCount() > 1) sc *= simultaneousScale();
        if (dotsFor < 0 && i == focus) {
            if (LockLyrics.playing() && !LockLyrics.still() && !LockLyrics.inHeldAod()) {
                sc *= pulseScale;
            }
        }
        // Pivot on the line's own edge, so a size change does not shift it sideways.
        Layout.Alignment align = alignFor(l.opposite, builtAlign);
        float pivotX = align == Layout.Alignment.ALIGN_CENTER ? lay.getWidth() / 2f
                : align == Layout.Alignment.ALIGN_OPPOSITE ? lay.getWidth() : 0f;
        boolean still = LockLyrics.still();
        // Retained lines stay readable, but finished word lifts/glows need no per-word rendering.
        boolean settledWords = retained(i) && i < focus
                && (long) ms >= (long) l.end
                + Math.max(LIFT_HOLD_MS + LIFT_RETURN_MS, GLOW_TAIL_MS);
        boolean eyeCandy = AliveLyricsEffects.eyeCandy(LockLyrics.sAliveFx);
        boolean words = l.hasDisplayWords() && e > 0f && !still && !settledWords;
        int save = canvas.save();
        canvas.translate(x, y);
        canvas.scale(sc, sc, pivotX, 0f);
        if (words) {
            // The full-line blur would preview unsung glyphs, so Eye-candy alone skips it.
            float k = blur[i] <= 0f ? 0f : clamp01(blur[i] / Math.max(0.01f, blurFor(1)));
            drawWords(canvas, i, eyeCandy ? a : a * (1f - k), e);
            if (k > 0f && !eyeCandy) {
                float r = blurFor(1);
                for (int d = 1; d <= BLUR_MAX_ROWS && blurFor(d) < blur[i]; d++) r = blurFor(d + 1);
                drawBlurLevel(canvas, i, r, a * INACTIVE * k, r);
            }
        } else {
            // The radius sits between two of the fixed ones - sharp, or a distance's blur - and is
            // drawn as a crossfade of those two pictures, so each is blurred once, not per frame.
            float r = blur[i];
            float lo = 0f, hi = 0f;
            for (int d = 0; d <= BLUR_MAX_ROWS; d++) {
                float v = blurFor(d);
                if (v <= r + 0.01f) lo = v;
                if (v >= r - 0.01f) {
                    hi = v;
                    break;
                }
                hi = v;
            }
            if (hi < lo) hi = lo;
            float t = hi > lo ? clamp01((r - lo) / (hi - lo)) : 0f;
            // A word-timed line that is not the focus is all unsung colour; a line-timed one is
            // lit while it is sung.
            float w = l.hasDisplayWords() && !still && !settledWords ? 0f : lit[i];
            float base = a * (INACTIVE + (1f - INACTIVE) * w);
            if (t < 1f) drawBlurLevel(canvas, i, lo, base * (1f - t), hi, w);
            if (t > 0f) drawBlurLevel(canvas, i, hi, base * t, lo, w);
        }
        canvas.restoreToCount(save);
    }

    private void drawBlurLevel(Canvas canvas, int i, float radius, float alpha, float keep) {
        drawBlurLevel(canvas, i, radius, alpha, keep, 0f);
    }

    private void drawBlurLevel(Canvas canvas, int i, float radius, float alpha, float keep,
                               float whiteness) {
        if (alpha <= 0.002f) return;
        Bitmap b = radius <= 0f ? null : blurredFor(i, rowsFor(radius));
        if (b == null) {
            drawStatic(canvas, i, alpha, whiteness);
            return;
        }
        // A blur spreads a glyph's ink over more pixels, so the same alpha reads fainter the more it
        // is blurred. Compensated a little, so distance reads as depth rather than as fading out.
        float boost = 1f + BLUR_ALPHA_PER_DP * (radius / density);
        bmpPaint.setAlpha(Math.round(255f * Math.min(1f, alpha * boost)));
        int w = main[i].getWidth() + 2 * blurPad;
        int h = Math.round(height[i]) + 2 * blurPad;
        bmpDst.set(-blurPad, -blurPad, w - blurPad, h - blurPad);
        canvas.drawBitmap(b, null, bmpDst, bmpPaint);
    }

    // ------------------------------------------------------------------ colour

    /** Keep ordinary lyric text white; shimmer is reserved for HDR highlights. */
    private void updateTint() {
        int src = 0;
        if (src == tintSrc) return;
        tintSrc = src;
        shimmerShader = null;
        if (src == 0) {
            tintR = tintG = tintB = 1f;
        } else {
            float[] hsv = new float[3];
            android.graphics.Color.colorToHSV(src, hsv);
            hsv[1] = Math.min(TINT_SAT, hsv[1] * 1.5f);
            hsv[2] = 1f;
            int t = android.graphics.Color.HSVToColor(hsv);
            tintR = ((t >> 16) & 0xff) / 255f;
            tintG = ((t >> 8) & 0xff) / 255f;
            tintB = (t & 0xff) / 255f;
        }
        int ti = android.graphics.Color.rgb(Math.round(tintR * 255f), Math.round(tintG * 255f),
                Math.round(tintB * 255f));
        bmpPaint.setColorFilter(new android.graphics.PorterDuffColorFilter(ti,
                android.graphics.PorterDuff.Mode.SRC_IN));
    }

    private boolean hdrShimmerEnabled() {
        return LockLyrics.sHdr;
    }

    /** Text colour: the tint at whiteness 0, white at 1, with an alpha. */
    private long ink(float alpha, float whiteness) {
        float w = clamp01(whiteness);
        float r = tintR + (1f - tintR) * w, g = tintG + (1f - tintG) * w, b = tintB + (1f - tintB) * w;
        return android.graphics.Color.pack(r, g, b, clamp01(alpha), EXTENDED);
    }

    /** The distance a blur radius belongs to. */
    private int rowsFor(float radius) {
        for (int d = 1; d <= BLUR_MAX_ROWS; d++) {
            if (Math.abs(blurFor(d) - radius) < 0.01f) return d;
        }
        return BLUR_MAX_ROWS;
    }

    /**
     * The line's picture blurred for a distance, or the nearest one it has while that is still
     * being made. Never made here: blurring on the UI thread, several lines at every line change,
     * was the hitch the scroll showed (99th percentile 38ms, 2026-09-16).
     */
    private Bitmap blurredFor(int i, int rows) {
        Bitmap[] have = blurBmp[i];
        if (have[rows - 1] != null) return have[rows - 1];
        requestBlur(i, rows);
        for (int gap = 1; gap < BLUR_MAX_ROWS; gap++) {
            if (rows - gap >= 1 && have[rows - gap - 1] != null) return have[rows - gap - 1];
            if (rows + gap <= BLUR_MAX_ROWS && have[rows + gap - 1] != null) {
                return have[rows + gap - 1];
            }
        }
        return null;
    }

    /**
     * The pictures the lines around the focus want now and at the next line change: their
     * distance now and one less, and for the line being sung the distance it drops back to.
     * Once made they stay, so after the first few lines this asks for one new line a change.
     */
    private void prewarmBlur() {
        int n = lines.size();
        for (int i = Math.max(0, focus - 4); i <= Math.min(n - 1, focus + 10); i++) {
            int d = Math.min(rowsFromFocus(i), BLUR_MAX_ROWS);
            if (d >= 1) requestBlur(i, d);
            if (d >= 2) requestBlur(i, d - 1);
            if (d <= 1) requestBlur(i, 2);
            if (i < focus && d < BLUR_MAX_ROWS) requestBlur(i, d + 1);
        }
    }

    private void requestBlur(final int i, final int rows) {
        if (rows < 1 || rows > BLUR_MAX_ROWS || i < 0 || i >= blurBmp.length) return;
        if (blurBmp[i][rows - 1] != null) return;
        if (!blurPending.add(i * 8 + rows)) return;
        // Everything the other thread needs, taken here: the paints are this view's and change on
        // every frame, so it gets copies, and it lays the text out again for itself.
        final int gen = buildGen;
        final LyricLine l = lines.get(i);
        final int alignOn = builtAlign;
        final int width = main[i].getWidth();
        final int fullW = width + 2 * blurPad, fullH = Math.round(height[i]) + 2 * blurPad;
        final float radius = blurFor(rows);
        final TextPaint p = new TextPaint(paint);
        final TextPaint tp = new TextPaint(transPaint);
        final TextPaint op = new TextPaint(onlineTransPaint);
        final TextPaint bp = new TextPaint(bgPaint);
        final int pad = blurPad;
        final float transGap = TRANS_GAP_DP * density;
        final float onlineTransGap = ONLINE_TRANS_GAP_DP * density;
        final float bgGap = BG_GAP_DP * density;
        final int down = rows >= BLUR_QUARTER_ROWS ? 4 : 2;
        blurHandler().post(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = makeBlurred(l, width, fullW, fullH, pad, radius, p, tp, op, bp,
                        transGap, onlineTransGap, bgGap, down, alignOn);
                post(new Runnable() {
                    @Override
                    public void run() {
                        blurPending.remove(i * 8 + rows);
                        if (b == null || gen != buildGen || i >= blurBmp.length) return;
                        blurBmp[i][rows - 1] = b;
                        // While the loop runs its next frame draws it anyway.
                        if (!looping) invalidate();
                    }
                });
            }
        });
    }

    /**
     * Off the UI thread: the line at 1/down of the resolution, blurred. The blur scales with the
     * canvas.
     */
    private static Bitmap makeBlurred(LyricLine l, int width, int fullW, int fullH, int pad,
                                      float radius, TextPaint p, TextPaint tp, TextPaint op,
                                      TextPaint bp, float transGap, float onlineTransGap,
                                      float bgGap, int down, int alignOn) {
        try {
            Bitmap b = Bitmap.createBitmap(Math.max(1, fullW / down), Math.max(1, fullH / down),
                    Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(1f / down, 1f / down);
            c.translate(pad, pad);
            BlurMaskFilter mf = new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL);
            Layout.Alignment align = alignFor(l.opposite, alignOn);
            p.setShader(null);
            p.clearShadowLayer();
            p.setAlpha(255);
            p.setMaskFilter(mf);
            String displayText = l.displayText();
            StaticLayout lay = StaticLayout.Builder.obtain(displayText, 0, displayText.length(), p, width)
                    .setAlignment(align)
                    .setIncludePad(false)
                    .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
                    .build();
            lay.draw(c);
            float below = lay.getHeight();
            if (l.bg != null) {
                bp.setShader(null);
                bp.setColor(0xFFFFFFFF);
                bp.setAlpha(Math.round(255f * BG_ALPHA));
                bp.setMaskFilter(mf);
                StaticLayout bl = StaticLayout.Builder.obtain(l.bg.text, 0, l.bg.text.length(),
                                bp, width)
                        .setAlignment(align)
                        .setIncludePad(false)
                        .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
                        .build();
                int save = c.save();
                c.translate(0f, below + bgGap);
                bl.draw(c);
                c.restoreToCount(save);
                below += bgGap + bl.getHeight();
            }
            String nativeSecondary = LockLyrics.sOnlineTranslateMode == LockLyrics.TR_MODE_OFF
                    ? l.translation : null;
            String onlineSecondary = l.onlineTranslation;
            if (LockLyrics.sTrans) {
                if (nativeSecondary != null && l.roma != null
                        && (LockLyrics.below() & LockLyrics.BELOW_ROMA) != 0) {
                    nativeSecondary += "\n" + l.roma;
                }
                if (onlineSecondary != null && l.roma != null
                        && (LockLyrics.below() & LockLyrics.BELOW_ROMA) != 0) {
                    onlineSecondary += "\n" + l.roma;
                }
                if (nativeSecondary != null) {
                    tp.setAlpha(Math.round(255f * TRANS_ALPHA));
                    tp.setMaskFilter(mf);
                    StaticLayout t = StaticLayout.Builder.obtain(nativeSecondary, 0,
                                    nativeSecondary.length(), tp, width)
                            .setAlignment(align)
                            .setIncludePad(false)
                            .build();
                    c.translate(0f, below + transGap);
                    t.draw(c);
                    below += transGap + t.getHeight();
                    if (onlineSecondary != null) {
                        op.setAlpha(Math.round(255f * TRANS_ALPHA));
                        op.setMaskFilter(mf);
                        StaticLayout ot = StaticLayout.Builder.obtain(onlineSecondary, 0,
                                        onlineSecondary.length(), op, width)
                                .setAlignment(align)
                                .setIncludePad(false)
                                .build();
                        c.translate(0f, onlineTransGap);
                        ot.draw(c);
                    }
                } else if (onlineSecondary != null) {
                    tp.setAlpha(Math.round(255f * TRANS_ALPHA));
                    tp.setMaskFilter(mf);
                    StaticLayout t = StaticLayout.Builder.obtain(onlineSecondary, 0,
                                    onlineSecondary.length(), tp, width)
                            .setAlignment(align)
                            .setIncludePad(false)
                            .build();
                    c.translate(0f, below + transGap);
                    t.draw(c);
                }
            }
            String under = l.under(LockLyrics.below());
            boolean secondaryDrawn = nativeSecondary != null || onlineSecondary != null;
            if (under != null && !secondaryDrawn) {
                tp.setAlpha(Math.round(255f * TRANS_ALPHA));
                tp.setMaskFilter(mf);
                StaticLayout t = StaticLayout.Builder.obtain(under, 0, under.length(), tp, width)
                        .setAlignment(align)
                        .setIncludePad(false)
                        .build();
                c.translate(0f, below + transGap);
                t.draw(c);
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A line far from the focus lets its pictures go. Not recycled: the render thread may still be
     * drawing one from the last frame. The collector frees them once nothing holds them.
     */
    private void dropBlurred(int i) {
        if (i >= blurBmp.length) return;
        Bitmap[] have = blurBmp[i];
        for (int k = 0; k < have.length; k++) have[k] = null;
    }

    private static android.os.Handler sBlurHandler;

    private static synchronized android.os.Handler blurHandler() {
        if (sBlurHandler == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("MCLyricBlur",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            t.start();
            sBlurHandler = new android.os.Handler(t.getLooper());
        }
        return sBlurHandler;
    }

    /** A line at one brightness: the text at a, its translation below it. */
    private void drawStatic(Canvas c, int i, float a) {
        drawStatic(c, i, a, 0f);
    }

    /** A line at one brightness, white by whiteness and the cover's tint otherwise. */
    private void drawStatic(Canvas c, int i, float a, float whiteness) {
        // Each layout's own paint: it draws with the copy it was laid out with, not the view's.
        StaticLayout lay = main[i];
        lay.getPaint().setColor(ink(a, whiteness));
        lay.draw(c);
        lay.getPaint().setColor(0xFFFFFFFF);
        StaticLayout b = bgLay[i];
        LyricLine line = lines.get(i);
        boolean eyeCandyBacking = !LockLyrics.still()
                && AliveLyricsEffects.eyeCandy(LockLyrics.sAliveFx)
                && line.bg != null;
        if (b != null && (!eyeCandyBacking
                || (ms >= line.bg.start && ms < line.bg.end))) {
            int save = c.save();
            c.translate(0f, lay.getHeight() + BG_GAP_DP * density);
            b.getPaint().setColor(ink(a * BG_ALPHA, whiteness));
            b.draw(c);
            b.getPaint().setColor(0xFFFFFFFF);
            c.restoreToCount(save);
        }
        drawTranslation(c, i, a);
    }

    /** How far down the translation starts: under the line and its background vocal. */
    private float transTop(int i) {
        float y = main[i].getHeight();
        if (bgLay[i] != null) y += BG_GAP_DP * density + bgLay[i].getHeight();
        return y + TRANS_GAP_DP * density;
    }

    /** White at a brightness in SDR units - 1 is ordinary white, more is HDR - and an alpha. */
    private static long white(float alpha, float gain) {
        return android.graphics.Color.pack(gain, gain, gain, clamp01(alpha), EXTENDED);
    }

    /**
     * A held note's brightness: HDR only while it glows, scaled with the glow. Nothing else is
     * drawn above white - the highlight is the end of a long note lighting up, not the line.
     */
    private static float glowGain(float glow) {
        return LockLyrics.sHdr ? 1f + (HDR_GAIN - 1f) * glow : 1f;
    }

    private void drawTranslation(Canvas c, int i, float a) {
        StaticLayout t = trans[i];
        StaticLayout online = onlineTrans[i];
        if (t == null && online == null) return;
        float fade = 1f;
        float lift = 0f;
        int fx = LockLyrics.sAliveFx;
        if (AliveLyricsEffects.enabled(fx) && !LockLyrics.still()) {
            long at = transFadeAt.length > i ? transFadeAt[i] : 0L;
            if (at == -1L) {
                at = now();
                transFadeAt[i] = at;
            }
            if (LockLyrics.playing()) {
                long frameNow = now();
                fade = AliveLyricsEffects.translationFade(fx, true, false, frameNow - at);
                lift = AliveLyricsEffects.translationLiftDp(fx, fade) * density;
                if (fade >= 0.999f) transFadeAt[i] = 0L;
            } else {
                transFadeAt[i] = 0L;
            }
        }
        int save = c.save();
        c.translate(0f, transTop(i) - lift);
        if (t != null) {
            t.getPaint().setColor(ink(a * TRANS_ALPHA * fade, 0f));
            t.draw(c);
            t.getPaint().setColor(0xFFFFFFFF);
        }
        if (online != null) {
            if (t != null) c.translate(0f, t.getHeight() + ONLINE_TRANS_GAP_DP * density);
            online.getPaint().setColor(ink(a * TRANS_ALPHA * fade, 0f));
            online.draw(c);
            online.getPaint().setColor(0xFFFFFFFF);
        }
        c.restoreToCount(save);
    }

    /**
     * A line with word timing, a syllable at a time, row by row: a row already sung at the sung
     * brightness, a row not reached at the unsung one, and the row being sung through a gradient
     * that crosses from one to the other at the character being sung.
     *
     * The gradient is the text paint's own shader. It used to be a DST_IN mask over an offscreen
     * layer of the whole line - a second full-size pass on the render thread every frame, which
     * was a quarter of a core for as long as a word-timed song played (measured 2026-09-16).
     */
    private void drawWords(Canvas c, int i, float a, float e) {
        LyricLine l = lines.get(i);
        StaticLayout lay = main[i];
        float level = INACTIVE + (1f - INACTIVE) * e;
        rowLeadIndex = i;
        drawWordRows(c, l, lay, paint, charXFor(i, lay, l, charX), e, a * level,
                a * (INACTIVE + (1f - INACTIVE) * e * UNSUNG), 1f, true, 0);
        StaticLayout bl = bgLay[i];
        if (bl != null && l.bg != null) {
            int save = c.save();
            c.translate(0f, lay.getHeight() + BG_GAP_DP * density);
            drawWordRows(c, l.bg, bl, bgPaint, charXFor(i, bl, l.bg, charXBg), e,
                    a * level * BG_ALPHA,
                    a * (INACTIVE + (1f - INACTIVE) * e * UNSUNG) * BG_ALPHA,
                    BG_LIFT, false, 1);
            c.restoreToCount(save);
        }
        drawTranslation(c, i, a * level);
    }

    /**
     * One word-timed layout, row by row: a row already sung at the sung brightness, a row not
     * reached at the unsung one, and the row being sung through a gradient that crosses from one
     * to the other at the character being sung.
     *
     * The gradient is the paint's own shader. It used to be a DST_IN mask over an offscreen layer
     * of the whole line - a second full-size pass on the render thread every frame, which was a
     * quarter of a core for as long as a word-timed song played (measured 2026-09-16).
     */
    private void drawWordRows(Canvas c, LyricLine l, StaticLayout lay, TextPaint p, float[] xs,
                              float e, float sungA, float unsungA, float liftScale,
                              boolean glowOn, int gradSlot) {
        if (!l.hasWords()) {
            boolean active = ms >= l.start;
            TextPaint layoutPaint = lay.getPaint();
            layoutPaint.setShader(null);
            layoutPaint.setColor(ink(active ? sungA : unsungA, active ? e : 0f));
            lay.draw(c);
            layoutPaint.setColor(0xFFFFFFFF);
            return;
        }
        float sung = l.sungChars(ms);
        float feather = FEATHER_EM * p.getTextSize();
        int rows = lay.getLineCount();
        for (int r = 0; r < rows; r++) {
            int rs = lay.getLineStart(r), re = lay.getLineEnd(r);
            rowAt = Float.NaN;
            if (sung >= re) {
                p.setColor(white(sungA, 1f));
            } else if (sung <= rs) {
                p.setColor(ink(unsungA, 0f));
            } else {
                int ch = (int) sung;
                float f = sung - ch;
                float xa = xs[ch];
                float xb = ch + 1 < re ? xs[ch + 1] : lay.getLineRight(r);
                p.setColor(0xFFFFFFFF);
                rowAt = xa + (xb - xa) * f;
                p.setShader(gradient(white(sungA, 1f), ink(unsungA, 0f), rowAt, feather,
                        gradSlot));
            }
            rowFeather = feather;
            rowSungA = sungA;
            rowUnsungA = unsungA;
            drawSyllables(c, l, lay, p, xs, e, r, liftScale, glowOn);
            p.setShader(null);
            p.setColor(0xFFFFFFFF);
        }
    }

    /** Draw one timed row with its mode-specific rise, highlight and optional held-note glow. */
    private void drawSyllables(Canvas c, LyricLine l, StaticLayout lay, TextPaint p, float[] xs,
                               float e, int r, float liftScale, boolean glowOn) {
        int count = l.sylStart.length;
        int rs = lay.getLineStart(r), re = lay.getLineEnd(r);
        float baseline = lay.getLineBaseline(r);
        float sungChars = l.sungChars(ms);
        int fx = LockLyrics.sAliveFx;
        boolean aliveEffects = AliveLyricsEffects.enabled(fx);
        boolean pcmLead = aliveEffects && glowOn && LockLyrics.sAudioReactive && focus >= 0
                && l == lines.get(focus) && LockLyrics.playing() && !LockLyrics.still()
                && Main.screenOnCached();
        for (int k = 0; k < count; k++) {
            int from = k == 0 ? 0 : l.charEnd[k - 1];
            int cs = Math.max(from, rs), ce = Math.min(l.charEnd[k], re);
            if (ce <= cs) continue;
            int s = l.sylStart[k], end = l.sylEnd[k], dur = end - s;
            float syllableProgress = (ms - s) / (float) Math.max(1, dur);
            float lift;
            if (!aliveEffects) {
                float wordRise = smoothUnit(clamp01((ms - s) / (float) BASIC_WORD_RISE_MS));
                lift = glowOn ? liftPx * 1.65f * e * wordRise : 0f;
            } else {
                // Alive motion is part of the lyric effect, not a side effect of PCM. The
                // audio-reactive lift below is an optional extra layered on this shared rise.
                lift = liftPx * liftScale * e * letterRise(syllableProgress, end);
            }
            float glow = 0f;
            // PCM may enhance the syllable currently being sung, but must not revive a
            // completed syllable during its visual tail. The timing/highlight path remains
            // authoritative for Spicy lyrics; PCM is only an in-window intensity layer.
            boolean audioWindow = ms >= s && ms < end;
            float audioDrive = pcmLead && audioWindow
                    ? clamp01(pcmLevel + 0.55f * pcmTransient) : 0f;
            float audioLift = 0f;
            boolean syllableStarted = sungChars > cs;
            if (LockLyrics.sHdr && glowOn && syllableStarted
                    && dur >= GLOW_MIN_MS) {
                glow = ms < end ? clamp01((ms - s) / (dur * 0.3f))
                        : 1f - clamp01((ms - end) / (float) GLOW_TAIL_MS);
                glow *= e;
            }
            if (aliveEffects && glowOn) {
                float trail = AliveLyricsEffects.wordTrailIntensity(fx, ms, end, dur, GLOW_MIN_MS);
                if (trail > 0f) glow = Math.max(glow, trail * e);
            }
            if (glow > 0f) {
                glow = clamp01(glow * AliveLyricsEffects.glowMultiplier(fx));
            }
            float audioGlow = audioDrive > 0.01f
                    ? clamp01(0.72f * audioDrive + 0.42f * pcmTransient) * e : 0f;
            if (audioGlow > 0f && glowOn && glow > 0f) {
                // PCM is an intensity modifier for the existing highlight, not a second glyph
                // pass. This keeps the normal karaoke character range authoritative.
                glow = clamp01(glow + audioGlow * (1f - glow));
            }
            float x0 = xs[cs];
            if (glow > 0.01f) {
                float x1 = ce < re ? xs[ce] : lay.getLineRight(r);
                int save = c.save();
                float widthScale = AliveLyricsEffects.glowWidthScale(fx, dur, GLOW_MIN_MS, glow);
                float swell = 1f + GLOW_SWELL * glow * widthScale;
                c.scale(swell, swell, (x0 + x1) / 2f, baseline);
                float g = glowGain(glow);
                long halo = white(GLOW_ALPHA * glow, g);
                p.setShadowLayer(glowPx * glow * widthScale, 0f, 0f, halo);
                // The glowing syllable itself goes above white with its halo: its sung part
                // through this row's gradient re-coloured, or all of it once the row is sung.
                Shader rowShader = p.getShader();
                long rowColor = p.getColorLong();
                if (g > 1.001f) {
                    long highlight = white(rowSungA, g);
                    if (Float.isNaN(rowAt)) {
                        if (rowShader == null && sungChars >= ce) {
                            p.setColor(highlight);
                        }
                    } else {
                        p.setShader(gradient(highlight, ink(rowUnsungA, 0f), rowAt,
                                rowFeather, 2));
                    }
                }
                drawSyllableInk(c, l, lay, p, xs, cs, ce, rs, re, k, s, end,
                        baseline - lift - audioLift, e, glowOn, g);
                p.setShader(rowShader);
                p.setColor(rowColor);
                p.clearShadowLayer();
                c.restoreToCount(save);
            } else {
                drawSyllableInk(c, l, lay, p, xs, cs, ce, rs, re, k, s, end,
                        baseline - lift - audioLift, e, glowOn, 1f);
            }
        }
    }

    /** Draw timed clusters in their measured shaping context, with mode-specific motion. */
    private void drawSyllableInk(Canvas c, LyricLine l, StaticLayout lay, TextPaint p,
                                 float[] xs, int cs, int ce, int rs, int re, int syllable,
                                 int start, int end, float baseline, float e, boolean lead,
                                 float gain) {
        if (AliveLyricsEffects.eyeCandy(LockLyrics.sAliveFx) && l.hasWords()
                && !LockLyrics.still() && !LockLyrics.inHeldAod()) {
            drawKaraokeSyllable(c, l, lay, p, xs, cs, ce, rs, re, syllable, start, end,
                    baseline, e, lead, gain, true);
            return;
        }
        if (!AliveLyricsEffects.enabled(LockLyrics.sAliveFx)) {
            drawSyllableRun(c, l, lay, p, xs, cs, ce, rs, re, baseline, false, gain);
            return;
        }
        boolean lifted = lead && !LockLyrics.still() && !LockLyrics.inHeldAod();
        boolean activeLead = lead && focus >= 0 && l == lines.get(focus)
                && LockLyrics.playing() && lifted && Main.screenOnCached();
        boolean colorFill = false;
        float spring = 0f;
        if (activeLead && rowLeadIndex == focus) {
            float seconds = (ms - start) * 0.001f;
            if (seconds >= 0f && seconds < 0.42f) {
                spring = (float) (Math.exp(-17.0 * seconds) * Math.sin(38.0 * seconds));
            }
        }
        int springSave = -1;
        if (spring != 0f) {
            springSave = c.save();
            c.scale(1f + spring * 0.032f, 1f + spring * 0.032f,
                    (xs[cs] + xs[ce]) * 0.5f, baseline);
            baseline -= spring * liftPx * 0.25f;
        }
        int[] ends = lifted && rowLeadIndex >= 0 && rowLeadIndex < clusterEnd.length
                ? clusterEnd[rowLeadIndex] : null;
        if (ends == null || !canSplitClusters(lay, xs, ends, cs, ce, re)) {
            float progress = (l.sungChars(ms) - cs) / Math.max(1f, ce - cs);
            float rise = lifted ? letterRise(progress,
                    clusterCompletionAt(l, cs, ce, start, end)) : 0f;
            float y = baseline - liftPx * 1.65f * e * rise;
            drawSyllableRun(c, l, lay, p, xs, cs, ce, rs, re, y, colorFill, gain);
        } else {
            float sung = l.sungChars(ms);
            for (int ch = cs; ch < ce; ) {
                int next = ends[ch];
                float progress = (sung - ch) / (next - ch);
                int completesAt = clusterCompletionAt(l, ch, next, start, end);
                float rise = letterRise(progress, completesAt);
                float y = baseline - liftPx * 1.65f * e * rise;
                drawSyllableRun(c, l, lay, p, xs, ch, next, rs, re, y, colorFill, gain);
                ch = next;
            }
        }
        if (springSave >= 0) c.restoreToCount(springSave);
    }

    /**
     * Karaoke reveals only sung grapheme clusters. Their direction is stable per lyric cluster,
     * so redraws never reshuffle the entrance; unsafe shaping falls back to the whole syllable.
     */
    private void drawKaraokeSyllable(Canvas c, LyricLine l, StaticLayout lay, TextPaint p,
                                     float[] xs, int cs, int ce, int rs, int re, int syllable,
                                     int start, int end, float baseline, float whiteness,
                                     boolean lead, float gain, boolean eyeCandy) {
        if (cs >= ce || ms < 0) return;
        // Backing vocals are a complete secondary lyric, not a second lead line. Keep their
        // future words visible in the dim row and let the timed pass brighten them as sung.
        if (!lead && ms < start) {
            // Reveal the backing phrase as a secondary entrance before its individual
            // karaoke timings begin. It stays dim, but does not pop in fully formed.
            int phraseOnset = l.start + syllable * 55;
            if (ms < phraseOnset) {
                drawSyllableRun(c, l, lay, p, xs, cs, ce, rs, re, baseline, false, gain);
            } else {
                setWordEntrance(l, syllable, cs, phraseOnset, true);
                drawKaraokeCluster(c, l, lay, p, xs, cs, ce, rs, re, phraseOnset,
                        AliveLyricsEffects.wordEntranceMs(LockLyrics.sAliveFx, true),
                        baseline, whiteness, lead, gain, eyeWordMotion[0],
                        eyeWordMotion[1], eyeWordMotion[2], eyeWordMotion[3]);
            }
            return;
        }
        int[] ends = rowLeadIndex >= 0 && rowLeadIndex < clusterEnd.length
                ? (l == lines.get(rowLeadIndex).bg
                    ? clusterEndBg[rowLeadIndex] : clusterEnd[rowLeadIndex]) : null;
        boolean backing = !lead;
        int entranceStart = backing ? Math.min(start, l.start + syllable * 55) : start;
        if (ends == null || !canSplitClusters(lay, xs, ends, cs, ce, re)) {
            setWordEntrance(l, syllable, cs, entranceStart, eyeCandy);
            drawKaraokeCluster(c, l, lay, p, xs, cs, ce, rs, re,
                    entranceStart, KARAOKE_LETTER_FLY_MS, baseline, whiteness, lead, gain,
                    eyeWordMotion[0], eyeWordMotion[1], eyeWordMotion[2], eyeWordMotion[3]);
            return;
        }
        int from = syllable == 0 ? 0 : l.charEnd[syllable - 1];
        for (int ch = cs; ch < ce; ) {
            int next = ends[ch];
            if (next <= ch || next > ce) {
                setWordEntrance(l, syllable, cs, start, eyeCandy);
                drawKaraokeCluster(c, l, lay, p, xs, cs, ce, rs, re,
                        start, KARAOKE_LETTER_FLY_MS, baseline, whiteness, lead, gain,
                        eyeWordMotion[0], eyeWordMotion[1], eyeWordMotion[2], eyeWordMotion[3]);
                return;
            }
            int syllableEnd = l.charEnd[syllable];
            int onset = clusterStartAt(ch, from, syllableEnd, entranceStart, end);
            int following = next < ce ? clusterStartAt(next, from, syllableEnd, start, end)
                    : ce < syllableEnd ? clusterStartAt(ce, from, syllableEnd, start, end)
                    : syllable + 1 < l.sylStart.length ? l.sylStart[syllable + 1]
                    : onset + KARAOKE_LETTER_FLY_MS;
            int flightMs = following > onset
                    ? Math.min(KARAOKE_LETTER_FLY_MS, Math.max(80, following - onset))
                    : KARAOKE_LETTER_FLY_MS;
            setWordEntrance(l, syllable, ch, onset, eyeCandy);
            drawKaraokeCluster(c, l, lay, p, xs, ch, next, rs, re,
                    onset, flightMs, baseline, whiteness, lead, gain, eyeWordMotion[0],
                    eyeWordMotion[1], eyeWordMotion[2], eyeWordMotion[3]);
            ch = next;
        }
    }

    /** A word's letters arrive as a soft wave, with one stable direction for the whole word. */
    private void setWordEntrance(LyricLine l, int syllable, int charAt, int onset,
                                 boolean eyeCandy) {
        eyeWordMotion[0] = 0f;
        eyeWordMotion[1] = 1f;
        eyeWordMotion[2] = 0f;
        eyeWordMotion[3] = 0f;
        if (!eyeCandy || !LockLyrics.playing()) return;
        boolean backing = rowLeadIndex >= 0 && l == lines.get(rowLeadIndex).bg;
        int mode = LockLyrics.sAliveFx;
        int entranceMs = AliveLyricsEffects.wordEntranceMs(mode, backing);
        float t = clamp01((ms - onset) / (float) entranceMs);
        float settle = smoothUnit(t);
        int seed = l.text.hashCode() * 31 + l.start * 17 + syllable * 13
                + (backing ? 1 : 0);
        float direction = (seed & 1) == 0 ? -1f : 1f;
        float wave = (float) Math.sin((charAt + l.start * 0.001f) * 0.55f);
        float offset = AliveLyricsEffects.wordEntranceOffsetDp(mode, backing);
        eyeWordMotion[1] = direction * offset * density * (1f - settle)
                + wave * 2f * density * (1f - settle);
        eyeWordMotion[2] = smoothUnit(clamp01(t / 0.18f));
        eyeWordMotion[3] = AliveLyricsEffects.wordEntranceBlurDp(mode, backing) * density
                * (1f - smoothUnit(clamp01(t / 0.65f)));
    }

    private void drawKaraokeCluster(Canvas c, LyricLine l, StaticLayout lay, TextPaint p,
                                    float[] xs, int cs, int ce, int rs, int re, int onset,
                                    int flightMs, float baseline, float whiteness, boolean lead,
                                    float gain, float wordX, float wordOffset, float wordAlpha,
                                    float wordBlur) {
        if (ms < onset) return;
        float progress = !LockLyrics.playing() ? 1f
                : clamp01((ms - onset) / (float) Math.max(1, flightMs));
        float remaining = 1f - progress;
        float settled = 1f - remaining * remaining * remaining;
        float alpha = (0.68f + 0.32f * settled) * wordAlpha;
        // The line itself already moves as the previous lyric is handed off. Keep Eye-candy
        // entrances on that shared baseline instead of adding a second vertical wave.
        float y = baseline;
        int save = c.save();
        c.translate(wordX, 0f);
        float softBlur = density * 1.2f * (1f - settled) + wordBlur;
        boolean theatrical = AliveLyricsEffects.eyeCandy(LockLyrics.sAliveFx);
        boolean backing = !lead;
        float settleScale = 1f + AliveLyricsEffects.wordEntranceScale(
                LockLyrics.sAliveFx, backing) * (1f - settled);
        c.scale(settleScale, settleScale, xs[cs], baseline);
        if (theatrical) {
            int seed = l.text.hashCode() * 31 + cs * 13 + (lead ? 0 : 1);
            float direction = (seed & 1) == 0 ? -1f : 1f;
            c.rotate(direction * AliveLyricsEffects.wordEntranceRotation(
                    LockLyrics.sAliveFx, backing) * (1f - settled), xs[cs], baseline);
            softBlur += density * AliveLyricsEffects.wordEntranceBlurDp(
                    LockLyrics.sAliveFx, backing) * (1f - settled);
        }
        Shader oldShader = p.getShader();
        long oldColor = p.getColorLong();
        int oldAlpha = p.getAlpha();
        float oldRowAt = rowAt;
        float oldShadowRadius = p.getShadowLayerRadius();
        float oldShadowDx = p.getShadowLayerDx();
        float oldShadowDy = p.getShadowLayerDy();
        long oldShadowColor = p.getShadowLayerColorLong();
        boolean colorFill = false;
        if (oldShader != null) {
            p.setShader(oldShader);
            rowAt = oldRowAt;
            p.setAlpha(Math.round(oldAlpha * alpha));
        } else {
            p.setShader(null);
            rowAt = Float.NaN;
            p.setColor(oldColor);
            p.setAlpha(Math.round(oldAlpha * alpha));
        }
        if (softBlur > 0.15f) {
            float radius = Math.max(oldShadowRadius, softBlur);
            float blurAlpha = clamp01(0.44f * (1f - settled) + wordBlur / (density * 3f));
            long color = oldShadowRadius > 0f ? oldShadowColor : white(blurAlpha, gain);
            p.setShadowLayer(radius, oldShadowDx, oldShadowDy, color);
        }
        drawSyllableRun(c, l, lay, p, xs, cs, ce, rs, re, y, colorFill, gain);
        p.setShader(oldShader);
        p.setColor(oldColor);
        p.setAlpha(oldAlpha);
        rowAt = oldRowAt;
        if (oldShadowRadius > 0f) {
            p.setShadowLayer(oldShadowRadius, oldShadowDx, oldShadowDy, oldShadowColor);
        } else {
            p.clearShadowLayer();
        }
        c.restoreToCount(save);
    }

    private static int clusterStartAt(int clusterStart, int syllableStart, int syllableEnd,
                                      int start, int end) {
        int length = Math.max(1, syllableEnd - syllableStart);
        float fraction = clamp01((clusterStart - syllableStart) / (float) length);
        return start + Math.round((end - start) * fraction);
    }

    private float letterRise(float progress, int completedAt) {
        if (ms >= completedAt) {
            float fall = clamp01((ms - completedAt - LIFT_HOLD_MS) / (float) LIFT_RETURN_MS);
            return 1f - smoothUnit(fall);
        }
        return smoothUnit(clamp01(progress));
    }

    private static int clusterCompletionAt(LyricLine line, int cs, int ce, int start, int end) {
        for (int i = 0; i < line.charEnd.length; i++) {
            if (cs < line.charEnd[i] && ce <= line.charEnd[i]) {
                int from = i == 0 ? 0 : line.charEnd[i - 1];
                float fraction = clamp01((ce - from)
                        / (float) Math.max(1, line.charEnd[i] - from));
                return start + Math.round((end - start) * fraction);
            }
        }
        return end;
    }

    private static float smoothUnit(float value) {
        float t = clamp01(value);
        return t * t * (3f - 2f * t);
    }

    /** Draw one shaped cluster run, replacing only the active karaoke fill shader. */
    private void drawSyllableRun(Canvas c, LyricLine l, StaticLayout lay, TextPaint p, float[] xs,
                                 int cs, int ce, int rs, int re, float baseline,
                                 boolean colorFill, float gain) {
        float x = xs[cs];
        if (colorFill) {
            if (drawActiveSungFill(c, l, p, xs, cs, ce, rs, re, baseline, gain)) return;
        }
        if (!drawShimmer(c, l, lay, p, xs, cs, ce, rs, re, baseline, gain)) {
            c.drawTextRun(l.text, cs, ce, rs, re, x, baseline, false, p);
        }
    }

    private static boolean canSplitClusters(StaticLayout lay, float[] xs, int[] ends,
                                            int cs, int ce, int re) {
        if (lay.getParagraphDirection(lay.getLineForOffset(cs)) != 1) return false;
        for (int ch = cs; ch < ce; ) {
            int next = ends[ch];
            if (next <= ch || next > ce || lay.isRtlCharAt(ch)) return false;
            float right = next < re ? xs[next] : lay.getLineRight(lay.getLineForOffset(ch));
            if (right <= xs[ch] + 0.01f) return false;
            ch = next;
        }
        return true;
    }

    /** Keep completed clusters in the currently sung syllable cover-colored up to its end. */
    private boolean drawActiveSungFill(Canvas c, LyricLine l, TextPaint source, float[] xs,
                                       int cs, int ce, int rs, int re, float baseline, float gain) {
        if (ms < 0) return false;
        int syllable = -1;
        for (int i = 0; i < l.charEnd.length; i++) {
            if (cs < l.charEnd[i] && ce <= l.charEnd[i]) {
                syllable = i;
                break;
            }
        }
        if (syllable < 0 || ms < l.sylStart[syllable] || ms >= l.sylEnd[syllable]
                || l.sungChars(ms) < ce) return false;
        shimmerPaint.set(source);
        shimmerPaint.setShader(null);
        shimmerPaint.setColor(white(rowSungA, gain));
        if (source.getShadowLayerRadius() > 0f) {
            shimmerPaint.setShadowLayer(source.getShadowLayerRadius(), source.getShadowLayerDx(),
                    source.getShadowLayerDy(), white(
                            android.graphics.Color.alpha(source.getShadowLayerColorLong()), gain));
        }
        c.drawTextRun(l.text, cs, ce, rs, re, xs[cs], baseline, false, shimmerPaint);
        return true;
    }

    /** Replace the actual fill shader, not an SDR overlay behind an already-white HDR run. */
    private boolean drawShimmer(Canvas c, LyricLine l, StaticLayout lay, TextPaint source,
                                float[] xs, int cs, int ce, int rs, int re, float baseline,
                                float gain) {
        if (!hdrShimmerEnabled()) return false;
        // No active fill edge means this run must retain its ordinary sung/unsung paint.
        if (Float.isNaN(rowAt)) return false;
        float left = xs[cs], right = ce < re ? xs[ce] : lay.getLineRight(lay.getLineForOffset(cs));
        if (lay.getParagraphDirection(lay.getLineForOffset(cs)) != 1
                || right <= left || right <= rowAt - rowFeather || left >= rowAt + rowFeather) {
            return false;
        }
        long hi = white(rowSungA, gain), lo = ink(rowUnsungA, 0f);
        long colorHi = white(rowSungA, gain), colorLo = white(rowUnsungA, 1f);
        long glintHi = white(rowSungA, gain * 1.3f);
        long glintLo = white(rowUnsungA, 1.3f);
        if (shimmerShader == null || hi != shimmerHi || lo != shimmerLo
                || colorHi != shimmerColorHi || colorLo != shimmerColorLo
                || glintHi != shimmerGlintHi || glintLo != shimmerGlintLo) {
            shimmerHi = hi;
            shimmerLo = lo;
            shimmerColorHi = colorHi;
            shimmerColorLo = colorLo;
            shimmerGlintHi = glintHi;
            shimmerGlintLo = glintLo;
            // The narrow highlight rides the edge without changing the lyric's neutral color.
            shimmerShader = new LinearGradient(-1f, 0f, 1f, 0f,
                    new long[]{hi, colorHi, glintHi, glintLo, colorLo, lo},
                    new float[]{0f, 0.27f, 0.36f, 0.64f, 0.73f, 1f},
                    Shader.TileMode.CLAMP);
        }
        shimmerMatrix.setScale(rowFeather, 1f);
        shimmerMatrix.postTranslate(rowAt, 0f);
        shimmerShader.setLocalMatrix(shimmerMatrix);
        shimmerPaint.set(source);
        shimmerPaint.setShader(shimmerShader);
        shimmerPaint.setColor(0xffffffff);
        if (source.getShadowLayerRadius() > 0f) {
            shimmerPaint.setShadowLayer(source.getShadowLayerRadius(), source.getShadowLayerDx(),
                    source.getShadowLayerDy(), white(
                            android.graphics.Color.alpha(source.getShadowLayerColorLong()), gain));
        }
        c.drawTextRun(l.text, cs, ce, rs, re, left, baseline, false, shimmerPaint);
        return true;
    }

    /** A left-to-right gradient centred on at. Rebuilt only when its two alphas change. */
    private Shader gradient(long hi, long lo, float at, float feather, int slot) {
        LinearGradient g = grads[slot];
        if (g == null || hi != gradHi[slot] || lo != gradLo[slot]) {
            gradHi[slot] = hi;
            gradLo[slot] = lo;
            g = new LinearGradient(-1f, 0f, 1f, 0f, new long[]{hi, lo}, null,
                    Shader.TileMode.CLAMP);
            grads[slot] = g;
        }
        gradMatrix.setScale(feather, 1f);
        gradMatrix.postTranslate(at, 0f);
        g.setLocalMatrix(gradMatrix);
        return g;
    }

    /** Each character's x, measured once per line the first time its words are drawn. */
    private float[] charXFor(int i, StaticLayout lay, LyricLine l, float[][] cache) {
        float[] xs = cache[i];
        if (xs != null) return xs;
        xs = charXOf(lay, l);
        cache[i] = xs;
        return xs;
    }

    private static float[] charXOf(StaticLayout lay, LyricLine l) {
        int len = l.text.length();
        float[] xs = new float[len + 1];
        for (int k = 0; k < len; k++) xs[k] = lay.getPrimaryHorizontal(k);
        xs[len] = lay.getLineRight(lay.getLineCount() - 1);
        return xs;
    }

    /** Built off-thread: UTF-16 interiors are never independently animated. */
    private static int[] clusterEndsOf(String text) {
        int[] ends = new int[text.length()];
        for (int at = 0; at < text.length(); ) {
            int cp = text.codePointAt(at);
            int next = at + Character.charCount(cp);
            int type = Character.getType(cp);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            boolean safe = script == Character.UnicodeScript.LATIN
                    || script == Character.UnicodeScript.GREEK
                    || script == Character.UnicodeScript.CYRILLIC
                    || script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HANGUL
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.BOPOMOFO
                    || script == Character.UnicodeScript.COMMON;
            safe &= type != Character.FORMAT && type != Character.SURROGATE
                    && !clusterExtender(cp) && !(cp >= 0x1f1e6 && cp <= 0x1f1ff)
                    && !(cp >= 0x1100 && cp <= 0x11ff)
                    && !(cp >= 0xa960 && cp <= 0xa97f)
                    && !(cp >= 0xd7b0 && cp <= 0xd7ff);
            // Common Latin ligatures need one baseline even with full-row shaping context.
            if (cp == 'f' && next < text.length()) {
                char following = text.charAt(next);
                if (following == 'f' || following == 'i' || following == 'l') safe = false;
            }
            while (next < text.length()) {
                int following = text.codePointAt(next);
                if (!clusterExtender(following)) break;
                next += Character.charCount(following);
            }
            if (next < text.length() && text.codePointAt(next) == 0x200d) safe = false;
            ends[at] = safe ? next : -1;
            at = next;
        }
        return ends;
    }

    private static boolean clusterExtender(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK || (cp >= 0xfe00 && cp <= 0xfe0f)
                || (cp >= 0xe0100 && cp <= 0xe01ef) || (cp >= 0x1f3fb && cp <= 0x1f3ff);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Diagnostics: which state asked for a redraw, per step, since the last describe(). */
    private final int[] whyCount = new int[10];
    private int stepCount, drawCount;

    /**
     * Diagnostics: how evenly the animation's frames came while it ran. A gap is a vsync the
     * loop asked for and did not get - counted only between two frames of a running loop.
     */
    private long loopFrames, gapsOver1, gapsOver3, maxGapMs;
    private long drawNsMax, drawNsSum;
    private long lastLoopFrame;
    private long focusChangedAt;
    private final StringBuilder gapLog = new StringBuilder();
    private long vsyncMs;

    // ---- the probe's little timeline

    /** What the last step made of the page's fate; the watch in LockLyrics compares against it. */
    private boolean pageWanted = true;

    boolean pageWanted() {
        return pageWanted;
    }

    private static final int TRACE_N = 16;
    private final String[] trace = new String[TRACE_N];
    private int traceN;
    private boolean traceGone;
    private float traceShow = -1f;
    private long traceAt, traceBurst;

    /**
     * One line per frame that moved, kept for the probe: what the page did while the lock screen
     * was leaving, and which reading was late with it (user, 2026-09-29: "解锁的时候歌词会有一瞬间
     * 的残留"). Only frames that move are kept, so one event is one burst, and a burst restarts
     * after a quiet gap so the times are relative to the first frame of *this* event.
     *
     * Columns: ms since the burst began, `show`, `G` while the page is being cut (the keyguard is
     * gone or has said it is leaving), `a` the view's own transition alpha, `w` the swipe fade
     * read off the card's container, `k` the keyguard reading that frame used, and which signal
     * said the lock screen was leaving.
     */
    private void trace(long now, boolean gone) {
        if (gone == traceGone && Math.abs(show - traceShow) < 0.02f) return;
        if (now - traceAt < 32L) return;
        if (now - traceAt > 700L) traceBurst = now;
        traceAt = now;
        traceGone = gone;
        traceShow = show;
        trace[traceN++ % TRACE_N] = "@" + now + " +" + (now - traceBurst) + "ms "
                + r2(show) + (gone ? "G" : "g")
                + " a" + r2(getTransitionAlpha()) + " w" + r2(swipeFade())
                + " k" + (Main.keyguardLocked() ? 1 : 0)
                + " c" + (Main.coverModeOn() ? 1 : 0) + " t" + (LockLyrics.wantsAttached() ? 1 : 0)
                + " s" + (Main.screenOnCached() ? 1 : 0) + " p" + ClockCollapse.phase()
                + " " + LockLyrics.leavingWhy() + " L" + LockLyrics.lastLeave();
    }

    private static String r2(float v) {
        return String.valueOf(Math.round(v * 100f) / 100f);
    }

    /**
     * The trace, oldest first. NOT read-and-cleared like the other counters: an unlock is easy to
     * follow with a re-lock, and the times on each line are absolute, so a burst that is not the
     * one asked about can be told apart instead of being read as it.
     */
    private String traceText() {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(traceN, TRACE_N);
        for (int i = 0; i < n; i++) {
            String s = trace[(traceN - n + i) % TRACE_N];
            if (s == null) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(s);
        }
        return sb.toString();
    }

    private void noteFrameGap(long now) {
        if (vsyncMs == 0L) {
            android.view.Display disp = getDisplay();
            float hz = disp == null ? 60f : disp.getRefreshRate();
            vsyncMs = Math.max(4L, Math.round(1000f / hz));
        }
        if (lastLoopFrame != 0L) {
            long gap = now - lastLoopFrame;
            loopFrames++;
            if (gap > vsyncMs * 3 / 2) gapsOver1++;
            if (gap > vsyncMs * 3) {
                gapsOver3++;
                // Each stall with how long after the last line change it came, to tell a stall
                // caused by a line change from one that is not.
                if (gapLog.length() < 600) {
                    gapLog.append(gap).append('@').append(now - focusChangedAt).append(' ');
                }
            }
            if (gap > maxGapMs) maxGapMs = gap;
        }
        lastLoopFrame = now;
    }

    private void noteWhy(int why) {
        stepCount++;
        for (int b = 0; b < whyCount.length; b++) {
            if ((why & (1 << b)) != 0) whyCount[b]++;
        }
    }

    String describe() {
        int words = 0;
        for (LyricLine l : lines) if (l.hasDisplayWords()) words++;
        StringBuilder w = new StringBuilder();
        String[] names = {"rebuild", "band", "show", "focus", "scroll", "snap", "emph", "blur",
                "words", "anchor"};
        for (int b = 0; b < whyCount.length; b++) {
            if (whyCount[b] > 0) w.append(names[b]).append('=').append(whyCount[b]).append(',');
            whyCount[b] = 0;
        }
        String counts = " steps=" + stepCount + " draws=" + drawCount + " why=" + w
                + " loopFrames=" + loopFrames + " late=" + gapsOver1 + " veryLate=" + gapsOver3
                + " maxGap=" + maxGapMs + "ms vsync=" + vsyncMs + "ms drawMax="
                + (drawNsMax / 100000L) / 10f + "ms drawAvg="
                + (drawCount == 0 ? 0f : (drawNsSum / drawCount / 100000L) / 10f) + "ms";
        stepCount = drawCount = 0;
        counts += " stalls(gap@sinceLineChange)=[" + gapLog.toString().trim() + "]";
        gapLog.setLength(0);
        loopFrames = gapsOver1 = gapsOver3 = maxGapMs = 0L;
        drawNsMax = drawNsSum = 0L;
        return "lines=" + lines.size() + " wordLines=" + words + " layoutMs=" + layoutMs + counts
                + " focus=" + focus + " ms=" + ms + " show=" + show
                // The route the band's lower edge came by, so a band placed off the fallback can
                // be told apart from one that was measured.
                + " band=" + (bandOk ? Math.round(bandTop) + ".." + Math.round(bandBottom)
                        + "(" + LockLyrics.bandSource() + ")" : "none")
                + " looping=" + looping + " parent=" + (getParent() instanceof ViewGroup)
                // The last few frames that moved, oldest first: what the page did while the lock
                // screen was leaving, and which reading was late with it. Only frames that move
                // are in it, so a burst is one event. See trace().
                + " trace=[" + traceText() + "]";
    }
}
