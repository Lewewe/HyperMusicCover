<div align="center">
  <img src="docs/images/app-icon.png" alt="HyperMusicCover-Enhanced app icon" width="128" height="128" />
  <h1>HyperMusicCover-Enhanced</h1>
  <p><strong>A smoother lock screen music cover art experience for HyperOS 4.</strong></p>
  <p>Large artwork, expressive lyrics, customizable players and audio-reactive visualizers.</p>
  <p>
    <a href="https://github.com/Lewewe/HyperMusicCover-Enhanced/releases"><img alt="Download" src="https://img.shields.io/github/v/release/Lewewe/HyperMusicCover-Enhanced?style=flat-square&amp;label=Download&amp;color=f38caf" /></a>
    <a href="https://t.me/HyperMusicCoverEnhanced"><img alt="Official Telegram group" src="https://img.shields.io/badge/Telegram-Official_group-26A5E4?style=flat-square&amp;logo=telegram" /></a>
    <a href="LICENSE"><img alt="License AGPL-3.0" src="https://img.shields.io/badge/License-AGPL--3.0-ffaaa3?style=flat-square" /></a>
  </p>
  <p>
    <a href="https://github.com/Lewewe/HyperMusicCover-Enhanced/releases">Download APK</a> ·
    <a href="https://t.me/HyperMusicCoverEnhanced">Official Telegram</a> ·
    <a href="https://github.com/Lewewe/HyperMusicCover-Enhanced/issues">Report an issue</a> ·
    <a href="https://afdian.com/a/yzc26623">Support development</a>
  </p>
</div>

---

An independently maintained LSPosed fork of [HyperMusicCover](https://github.com/zyl6932/HyperMusicCover), focused on HyperOS 4. It builds on the original lock screen music experience with smoother artwork transitions, richer lyric tools and closer integration with notifications, AOD and HyperOS materials.

## What’s inside

### HyperCanvas · optional extension

Use Spotify Canvas as a moving lock screen background through the separate [HyperCanvas](https://github.com/Lewewe/HyperCanvas) APK, managed from the **Extensions** tab.

- Tracks with Canvas can use an expanded media player over the video; tracks without Canvas retain the artwork background.
- Opening big cover art or lyrics blurs and dims Canvas, with smooth transitions and a dedicated lyrics toggle on the player.
- Pause video with playback, freeze and blur it in Canvas mode on AOD, and pause/blur it on the password screen.
- Adjust dimming and blur, choose whether Canvas remains visible in pill mode, and retain the previous cover/lyrics/player state.
- Keep a bounded cache of three videos, cleared when the extension is disabled.

**HyperCanvas only works with HyperMusicCover-Enhanced.** It needs its own LSPosed scopes for Spotify and SystemUI. See the [extension README](https://github.com/Lewewe/HyperCanvas#readme) for setup and compatibility.

### Real audio visualizers · beta

Replace the Super Island’s decorative music wave with bars driven by playback audio.

- Five frequency bands with artwork-colored gradients and consistent bar widths.
- Optional visualizers in place of the output-device button, with separate switches for the lock screen, notification player and expanded Super Island.
- Internal playback capture can continue at zero media volume without using the microphone; an output-mix Visualizer provides a fallback.
- Bluetooth timing compensation with an optional manual adjustment, plus reduced frame rate on AOD.
- Optional hiding of the Super Island headphone icon, and recovery when tracks change automatically.

Capture and timing depend on the device and audio route; the feature is marked beta.

### Lyrics and the NextLyrics runtime

A dedicated **Lyrics settings** page brings search, presentation and translation together.

- **Original / Extended search:** choose the original source-selection and parsing behavior or the extended provider pipeline.
- **NextLyrics:** reuse cached lyrics and preload neighboring tracks when queue information is available. Cache entries distinguish search/provider/translation settings so changing modes does not reuse incompatible results.
- **Gentle lyric flow:** retain rapidly changing lines in groups of up to four so they remain readable.
- Support for simultaneously sung lines and word-timed lyric formats, alongside ordinary synced lyrics.
- Optional **Alive lyrics** effects, cover-color highlighting, translation and romanisation tools.
- Online translation options, including LibreTranslate, Google Cloud and DeepL; some services require your own configuration or API key.
- Installable lyric font packs that can be enabled or disabled without deleting them.
- Optional beat-reactive kaomojis when lyrics are unavailable, with layout adaptation around long clocks and the player.

Lyrics, translations and word timing depend on the selected provider and the track. More details: [lyric presentation](docs/lyric-effects.md) and [online translation](docs/online-lyric-translation.md).

### Media player styling

Customize the lock screen/notification player and expanded Hyper Island player.

- Background themes and materials, including animated styles and artwork-based colors.
- Default, circular and rotating circular artwork styles.
- Smooth corner/shape transitions between big cover art and circular player artwork, and restored native rounding when returning to Default.
- Preserve native themes, corners and AOD layout when customization is disabled.

The styling foundation is adapted from [HyperLyrics-Enhanced](https://github.com/juren233/HyperLyrics-Enhanced). See [implementation and attribution](docs/media-card-customization.md).

### Big cover art, refined

- Refresh artwork automatically when tracks change, including YouTube Music’s song/video artwork switching.
- Handle square and landscape artwork with clock sizing, spacing and animated expansion/collapse.
- Use a light blur/dim transition while switching tracks, then fade it as the cover expands and becomes ready.
- Improve rapid track changes and previous-track handling, avoiding stale covers and transition effects when only restarting or seeking within a track.
- Compatibility fixes for Spotify, NetEase Music and Salt Player.
- A single full-screen artwork background with supplementary cover-derived color regions.
- Optional **Use wallpaper background** mode with adjustable dimming behind floating cover art and lyrics. Its dim layer stays full-screen on AOD, and HyperCanvas is temporarily suspended while this mode is enabled.
- A lyrics button on big cover art when lyrics are available; tracks without lyrics can return to the player with one tap when tap gestures are enabled.

### Notifications, pill mode and AOD

- Coordinate big cover art with notification stack/list gestures: temporarily collapse for the list and restore the selected scene when appropriate.
- Avoid extra collapse/expand animations from stack bounce, fast gestures and AOD wake transitions.
- Fade player blur when reopening the music pill in big cover mode, without triggering the effect from the notification-group pill.
- Adjustable pill blur and brightness, compatible with HyperOS 4 Soft Glass and Frosted Glass.
- Optional compact notification pill with size and downward-offset controls on supported low-FOD devices.
- Explain notification sinking and offer a SystemUI restart when its setting changes.
- Adapt clock layout to user-resized and long clock styles; collapse overlapping big artwork into the player on AOD.
- Improve date alignment and status-bar icon contrast against music backgrounds.
- Mark default slider values and provide haptic feedback when returning to them.

## Install

1. Use a rooted **HyperOS 4** device with an LSPosed build supporting the modern Xposed API. The APK requires **Android 15 or newer**.
2. Download the APK from [GitHub Releases](https://github.com/Lewewe/HyperMusicCover-Enhanced/releases).
3. Install it, enable the module in LSPosed and use the relevant recommended scopes. Restart the affected processes or reboot.
4. Open the app to configure cover art, lyrics, player styling and other features.
5. For Spotify Canvas, install [HyperCanvas](https://github.com/Lewewe/HyperCanvas) separately and enable it in **Extensions**.

The app package is `com.yzc26623.HyperMusicCoverEnhanced`. If migrating from an older module with a different package name, back up your settings and disable its scopes before enabling this one.

**The feature list describes the current source.** Downloaded releases contain the features listed in their own release notes; new features on `main` may not yet be included in the latest APK.

## Compatibility and feedback

Development is tested primarily on **Xiaomi 17 Pro running HyperOS 4**. Clock styles, fingerprint placement, SystemUI versions, music apps and other hooked modules can change behavior on other devices.

Join the [official Telegram group](https://t.me/HyperMusicCoverEnhanced) for discussion, or [open an issue](https://github.com/Lewewe/HyperMusicCover-Enhanced/issues) with your device, HyperOS version, module version, player app and steps to reproduce the problem. A short recording or relevant log helps with intermittent animation issues.

## Build from source

Clone with the HyperCanvas submodule:

```sh
git clone --recurse-submodules https://github.com/Lewewe/HyperMusicCover-Enhanced.git
cd HyperMusicCover-Enhanced
```

Use the included Gradle wrapper and Android SDK 37, and configure `sdk.dir` in a local `local.properties`. The project’s Gradle daemon configuration selects its Java toolchain.

```sh
./gradlew :app:assembleRelease
./gradlew :hyper-canvas:assembleRelease
```

Both modules support a local, untracked `keystore.properties` with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without release signing configuration, a build uses the local debug key. **HMC-Enhanced and HyperCanvas must share a signing certificate** for their bridge to work, and APK updates must use the installed app’s signing key.

For an existing checkout:

```sh
git submodule update --init --recursive
```

## Credits

- [Lewewe](https://github.com/Lewewe) — Enhanced fork development and maintenance.
- [puhboo](https://github.com/puhboo) — major lyric contributions: extended search, translation, parsing and word timing, caching, and lyric effects.
- [zyl6932](https://github.com/zyl6932/HyperMusicCover) — original HyperMusicCover project.
- [juren233 / HyperLyrics-Enhanced](https://github.com/juren233/HyperLyrics-Enhanced) — media player customization foundation.
- [miuix](https://github.com/compose-miuix-ui/miuix) and the other projects credited in the app — UI, rendering, lyric data and supporting libraries.

The in-app **Credits** page and the documentation retain detailed attributions and third-party license information.

## License

[GNU Affero General Public License v3.0](LICENSE). Imported components retain their original copyright notices and licenses.
