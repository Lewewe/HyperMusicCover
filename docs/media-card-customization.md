# Media card customization

Adapted for HyperMusicCover-Enhanced from [juren233/HyperLyrics-Enhanced](https://github.com/juren233/HyperLyrics-Enhanced), revision `912df58`.

The background renderers, crossfade, soft-flow shader and palette extractor retain their upstream algorithms. The notification/island hook adapter, settings bridge, persistence and lifecycle integration are specific to this fork. No lyric hooks, audio capture, wake locks or screen keep-alive logic are imported.

Upstream project license: GPL-3.0, retained in `HyperLyrics-Enhanced-LICENSE.txt`. Files with explicit upstream Apache-2.0 notices (including the color extractor) retain their copyright and are attributed here: Copyright 2026 Proify, Tomakino, juren233. The Apache-2.0 license is retained in `Apache-2.0-LICENSE.txt`.

Settings are independent for notification/lock-screen cards and expanded Hyper Island cards. Defaults preserve the native appearance. Animation stops when hidden or the display is off. Original material, outlines, visibility and foreground colors are restored when customization is disabled.

Forced island light/dark themes use the themed native island drawable after clearing incompatible bionics blending. Returning to System restores the native material. This compatibility path does not import the reference module's notification-glass hook stack.
