# Lyric presentation

## Cover-color shimmer

**Cover-color lyrics** is an optional, off-by-default experimental setting. When enabled, lyric
ink is cover-tinted and the active lead's karaoke fill and completed-syllable flash use the cover
color. When disabled, lyric ink and these effects return to white. Cover
sampling/color rendering may be inconsistent on some covers or devices, so turn the option off if
it looks wrong. It stops on pause, screen-off and AOD/still mode.

The Canvas renderer reuses its TextPaint, LinearGradient and Matrix; the gradient is rebuilt
only when its cover/brightness/alpha inputs change. No Lottie dependency, full-line offscreen layer or new
animation loop is added. The existing karaoke frames drive the effect.

## Plain karaoke and Alive modes

Alive Lyrics Off uses a simple Apple Music-style karaoke presentation. All word-timed text remains
visible; as each timed word is sung, its segment fills white and rises as one unit over a short
transition. Once sung, it stays white and raised. It has no letter-rise wave, breathing, trails,
translation motion or cover tint. HDR held-note highlighting remains available via
its separate HDR setting. Timed lyrics still fill normally; line-synced lyrics remain line-level.

Subtle and Dramatic retain the normal renderer: all word-timed letters remain visible, and the fill
cursor drives each safe grapheme's rise. As the fill passes, each letter eases smoothly back to
baseline. They also provide their mode-specific trails, breathing and translation motion, but neither
flies letters in from off-screen nor hides upcoming lyric lines. The full-line blur crossfade is
retained for these modes.

Eye-candy alone uses the animated reveal. Unsung letters stay hidden while their measured layout
positions remain reserved. Each safe grapheme appears in karaoke order with a clearly visible,
smooth up-or-down slide as its timed word makes a gentle, short glide into place. The letter movement
is a little more pronounced than the normal letter-rise distance, not a screen-spanning entrance.
It also hides future lyric lines until their timestamps, avoiding a
preview that competes with the current reveal. Word direction is stable, and a light text-shadow haze
softens arrivals without blurring the whole line or allocating a bitmap per frame. Eye-candy does not
use the full-line blur crossfade.

Timed backing-vocal rows use their own text and karaoke timestamps, so revealing a backing letter
does not consume or delay a lead letter. If a provider gives a shared timing segment for lead and
echo text, both follow that shared timing. Unsafe grapheme shaping falls back to revealing the
whole syllable together. Lyrics with only line-level sync do not get fabricated letter timings;
they retain the normal line rendering. On AOD, Eye-candy uses the existing low-power scheduled
redraws to show a stepwise letter rise, throttled to at most one karaoke update every 750 ms; it
does not run a continuous frame loop. Other modes keep their settled AOD rendering.

## Parenthesized backing vocals

In word-timed lyrics, one balanced ASCII `(…)` or fullwidth `（…）` phrase is moved under its
main lyric when it can be separated without inventing word timings. The layout order is:

1. Main lyric, with its retained word timings.
2. Smaller backing-vocal row, with the phrase's original word timings.
3. Original translation/romanization, when translations are shown.

Native accompaniment metadata wins over inferred parentheses. Translations remain attached
to the main lyric, and hiding translations removes only the translation row. This does not
infer singer identities from punctuation.

Ambiguous cases stay inline: nested/multiple/unbalanced groups, wholly parenthetical lines,
recognized stage directions and optional word endings such as `sing(ing)`. If one timed syllable
contains both lead and echo text, its existing timing is shared by both rows rather than dropped
or divided, so the lead fill remains intact. Ordinary line-timed lyrics are unchanged.

## Word timing and catalogue alternatives

Missing/zero word ends are inferred from the next strictly later word start; valid ends stay
unchanged, with guarded terminal-word fallback to avoid stretching through interludes.

For multi-credit metadata, the original and normalized combined-credit searches come first,
followed by the core title separately with each credited artist. If the title has an explicit
feature credit, that exact title is also searched separately with each original primary artist.
NetEase song-search terms retain the complete artist string, not just the first artist. Up to eight metadata queries are checked
with two workers, at most 18 catalogue calls and an eight-second quality-search budget. Existing
catalogue caches are reused. Line lyrics stay visible during this pass; a new track/session
payload cancels retries and blocks stale callbacks.

Word-timed alternatives must match the complete normalized sung text and agree in timing within
two seconds; provider metadata validation still checks title/artist/recording identity and known
duration. Explicit `- From …`/`(From …)` soundtrack credits and bilingual title subtitles such
as `瞬息甜味（Sugar Time）` normalize to the base title for both searches and matching. Live,
remix, version and sequel qualifiers remain distinct; arbitrary substring matches stay disabled.
Existing word timing is never downgraded. Translations/backing vocals are retained on
confident whole-line matches; upgrades that would lose secondary text are refused. When the
original sources find nothing, per-artist search can also return a normal synced fallback.

All effects need real-device visual/performance validation, including wrapped lyrics,
translations, player changes, pause/resume and AOD transitions.
