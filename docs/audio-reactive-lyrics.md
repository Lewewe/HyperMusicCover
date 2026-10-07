# Opt-in playback energy for reactive lyrics

Enable **Playback-reactive pulse** in the lock-screen lyrics settings to drive the active
line's small scale pulse from playback PCM energy. It is **off by default**, independent of
the existing word-trail/translation-lift preset. Enable the player scopes listed below in
LSPosed and restart the player and SystemUI after installing the update.

`PlaybackPcmCapture` intercepts Java `AudioTrack.write` in scoped player processes and sends
only RMS/peak scalars to SystemUI. `LyricView` smooths the RMS envelope (45ms attack, 180ms
release) and a short peak-derived transient (25ms attack, 140ms release). Word-timed lyrics use
the envelope and transient for a stronger white halo and lift on only the currently sung syllable;
the optional **Cover-color lyrics** setting tints that halo. Lyric timestamps still choose
the active syllable. Line-synced lyrics use a whole-line
scale pulse up to 1.053. Fresh PCM silence settles to rest. Missing/stale PCM instead uses the
lyric-clock fallback (up to 1.018 with the dramatic preset), including unsupported Spotify paths.
The alive-lyrics preset also retains its original pulse when the PCM option is off. No microphone
capture or new audio permission is used.

## Renderer integration contract

The main renderer owns the user preference and lifecycle policy. In **SystemUI only**, call:

```java
// Heartbeat every ~500ms, only while user-enabled, awake, visible and playing.
PlaybackPcmCapture.setActive(context, selectedMediaPackage, true);

// Read each animation frame. No snapshot object is allocated.
// Freshness, not nonzero energy, determines whether PCM is available.
boolean fresh = PlaybackPcmCapture.hasFreshSample();
long sample = PlaybackPcmCapture.sample();
float rms = PlaybackAudioState.rms(sample);
float peak = PlaybackAudioState.peak(sample);
// Or RMS alone:
float energy = PlaybackPcmCapture.currentEnergy();

// Fresh samples wake the renderer on main, coalesced (including idle line-timed lyrics).
PlaybackPcmCapture.setOnSample(rendererWakeup);

// Immediately on AOD, hidden lyrics, pause, disable, detach or lost selection.
PlaybackPcmCapture.setOnSample(null);
PlaybackPcmCapture.stop(context);
// Equivalent: setActive(context, selectedMediaPackage, false).
```

`setActive` returns whether local activation succeeded, **not** whether PCM has been received.
Unsupported packages return false and release the old selection. A package change releases the
old player, clears energy and starts a new session. Stop clears local energy immediately and
sends a remote release; the remote release is asynchronous.

Each heartbeat grants at most **1500ms** of capture using boot-monotonic `elapsedRealtime`
time, with control broadcasts limited to one per **500ms** (plus immediate start/stop).
There is **no autonomous renewal**. A stopped/crashed renderer therefore cannot leave player
capture enabled indefinitely. Callers must keep heartbeats running independently of whether
current energy is zero; zero can mean silence or unsupported playback. Data older than
**350ms** returns zero, as do inactive/expired leases. `hasFreshSample()` uses the same lease and
age checks and is true even when an accepted sample is packed zero (true silence). It is false
before the first accepted sample, on stale data, on release/expiry and after a session change.
Rendering/easing remains the caller's job.

### Renderer fallback

`LyricView` keeps the existing synthetic pulse while the alive-lyrics preset or playback-reactive
option is enabled, visible, awake and playing. Fresh PCM takes precedence when capture is enabled;
fresh zero energy remains silence. Missing/stale samples use the old synthetic pulse, including
for Spotify or unsupported packages. Capture eligibility and pulse eligibility are separate;
`supports()`/`setActive()` success does not prove that audio samples are being received.

Keep capture heartbeats independent of the chosen pulse, and keep requesting animation frames
for the synthetic pulse even when PCM is missing. Retain the existing active-line, pause, AOD,
hide and detach guards; still release capture immediately on those lifecycle changes. Ease
between targets to avoid snapping when data arrives or becomes stale. Freshness and energy
accessors are separate reads, not an atomic snapshot; availability can change between reads.
The renderer eases the resulting scale with the existing attack/release constants. Audio energy
normalization remains unchanged; this fallback does not promise capture on native player paths.

## Scope and playback support

Capture runs only in these music-player packages:

- `com.apple.android.music` (already scoped for AppleLyrics)
- `com.spotify.music` (already scoped)
- `com.netease.cloudmusic`
- `com.tencent.qqmusic`
- `com.kugou.android`
- `com.luna.music`
- `com.miui.player`

The five new entries are common players in
[LyricInfo's scope list](https://github.com/limczhh/LyricInfo/blob/main/app/src/main/resources/META-INF/xposed/scope.list).
No all-app scope is used. Existing users may need to enable these player scopes in LSPosed and
restart player processes. Initialization verifies the actual application context and process
UID before installing audio hooks. Regular package-owned secondary processes are supported
when the framework injects there; isolated processes, other users/profiles and processes not
injected by LSPosed are not covered. **SystemUI audio writes are never hooked.**

Supported public Java signatures, hooked individually with libxposed API 102:

- `write(byte[], offset, count)` and `write(byte[], offset, count, mode)`
- `write(short[], offset, count)` and `write(short[], offset, count, mode)`
- `write(float[], offset, count, mode)`
- `write(ByteBuffer, byteCount, mode)` and the timestamped `(..., long timestamp)` overload

Only the positive, successfully accepted prefix is examined, after the original call returns.
Errors, zero returns, thrown original calls, invalid regions, unsupported formats, released
leases and offloaded tracks produce no capture. Invalid regions, mismatched array encodings
and prefixes without a complete sample are rejected before consuming the sampling throttle;
they do not publish misleading fresh zero energy. The renderer is responsible for releasing on
pause; the hook deliberately avoids `getPlayState()`, which takes an Android monitor. A lease
left active while the player queues audio on a paused track can measure those accepted writes. Short/float-array returns count elements;
byte-array/ByteBuffer returns count bytes. Trailing incomplete samples are ignored. PCM8 is
unsigned and centered on 128; PCM16/24/32 are signed; packed byte PCM and floats are decoded
**little-endian**, independent of the buffer's configured byte order. Non-finite float
samples are zeroed, and magnitudes are clamped to [0,1].

ByteBuffers are accessed through absolute read-only `get(index)` operations, including heap,
direct, sliced and read-only buffers. The module does not duplicate a buffer or alter its
position, limit, mark, contents or byte order. The original write may advance its position;
only that original advancement remains. PCM is inspected synchronously while the successful
write's arguments are still available, and references are never saved beyond the callback.
Concurrent mutation of a supplied buffer by the player itself is outside this contract.

The original method is called exactly once with unchanged arguments. A ThreadLocal guard
spans the original call and suppresses nested/delegating overloads. Capture callbacks are
exception-isolated; original results and original exception identities remain unchanged.
Missing overloads fail independently rather than aborting player startup.

### Deliberately unsupported

- Native AAudio/OpenSL ES writes and native AudioTrack writes that bypass Java methods.
- Hardware/offloaded or compressed playback, including OEM paths with no accessible PCM.
- Accurate audible-energy timing from prefilled/static buffers or playback with no new writes
  during an active lease. Accepted writes can include queued, not-yet-audible audio.
- Apps outside the allowlist, isolated playback services, remote/Cast playback and other users.

A supported *package* is not a guarantee its current playback engine uses Java PCM. In
particular, Spotify being allowlisted/scoped does not establish that its current engine reaches
these Java hooks. Inspection found no verified Spotify-specific hook failure; native/offloaded
paths are not repaired by this change, and real-device capture/delivery remains unverified.
Use freshness-driven renderer fallback rather than promise global Spotify PCM support. There is
no microphone, AudioRecord, Visualizer, native hook, daemon/service, FFT, frequency-band
analysis or haptic output, and no fallback to capturing SystemUI/mixed system audio.

## Work and data bounds

At most **512 sample values** from an accepted prefix are spread across that prefix for each
measurement, with a process-wide **75ms** throttle. RMS/peak are approximate scalar envelope
statistics of those probes, not a full-buffer peak guarantee or a spectrum. Multiple tracks
or player processes can contribute; this is package/process energy, not exact media-session
or song attribution, and does not account for AudioTrack volume/muting or output latency.

The hook computes only bounded scalar arithmetic. It has no explicit per-write object
creation, PCM copy, blocking lock, queue post, IPC, network or logging. ThreadLocal internals
may initialize once on the first write on a thread, and libxposed/the original Android method
may allocate their own argument/result wrappers. A nonblocking atomic gate skips competing
capture callbacks. A scalar-only seqlock mailbox feeds a background-priority Handler worker;
only that worker constructs and sends energy broadcasts. Idle/expired player leases have no
periodic polling tasks. A process-local HandlerThread remains available for later activation;
no process/service is started or kept alive externally.

## IPC security and permissions

This uses Android 35+ APIs (the project's minSdk is 35). Both directions are **explicitly
package-targeted** and opt in to Android's sender identity sharing. `getSentFromUid()` and
`getSentFromPackage()` are checked; identity extras and `Binder.getCallingUid()` are not trusted.

- **Control:** the player's runtime receiver requires `android.permission.STATUS_BAR` from
  the broadcaster. It additionally verifies the sender is the installed SystemUI UID and
  package, and initialization requires SystemUI to match the platform (`android`) signing
  identity. Target package, session, boot-time deadline and increasing control revision are
  checked. Leases exceeding 1500ms, expired leases and reordered controls are rejected.
- **Data:** only SystemUI is targeted, with `STATUS_BAR` as the recipient permission. The
  SystemUI receiver validates the currently selected allowlisted package's UID and Android-
  reported sending package, session and bounded/fresh timestamps. Its signing identity is
  recorded from PackageManager at activation and rechecked on the worker on each receipt; UID
  or signer changes are rejected. The receiver snapshots the selected source, UID, signer
  baseline, session and lease under a short class lock, then performs PackageManager/Binder
  verification **outside that lock**. Before accepting, it re-locks and checks the snapshot is
  still current and the lease/sample have not expired during the lookup. A concurrent stop,
  source/session change or lease renewal discards that in-flight sample without notifying the
  renderer. Signer checks are not cached across samples, preserving immediate rejection of
  signing changes without blocking renderer activation/stop on the receiver's lookup. The
  existing main-handler sample callback remains coalesced. RMS/peak must be finite, normalized
  and internally consistent.
- No new manifest/runtime audio permissions are requested. Injection uses host-process
  permissions: SystemUI must already hold `STATUS_BAR`, and package/signing queries must be
  visible to both hosts. Missing identity, permission, visibility or platform-signature support
  fails closed. OEM SystemUI with a non-platform signing identity is deliberately unsupported.
- Session/revision values are non-secret monotonic identifiers, only for replay/order isolation.
  Neither secrets nor PCM are transmitted; messages contain only session/revision/deadline or
  session/time/RMS/peak. Broadcast action names are not credentials.

**Trust limits:** player signing checks bind data to the currently installed selected app;
they do **not** pin a vendor certificate or attest that a player is genuine. A counterfeit
allowlisted package installed before activation, or compromised selected player, can invent
its own scalar energy. Other code inside SystemUI can send control; other code inside the
selected player can observe its leases. Same-UID/code-injection/root/framework compromise
cannot be isolated by this bridge. It is not a secrecy boundary inside either host process.
Android still dispatches untrusted attempts to the exported data receiver before application
validation; this is not a denial-of-service mitigation. No waveform is transmitted, but scalar
envelopes can reveal playback activity/rhythm; they are not a promise of anonymity.

## Validation

The freshness/invalid-region change was inspected without running tests, builds or lint, as
requested. The fixture results below describe earlier validation, not a new run or device proof.

Run without Gradle/lint or downloads:

```sh
python tools/run-pcm-tests.py --java-home /path/to/jdk
```

The runner compiles both production Java files and the pure test helper, then runs 188 checks:
92 pure state/PCM checks, 35 player hook/transport checks, 32 SystemUI checks, 4 fail-closed
initialization checks and 25 receiver-verification race checks. The existing 163 checks are
preserved, including deferred/coalesced main-handler sample notifications. New fixtures block
PackageManager verification while a concurrent renderer stop completes, assert the lookup
holds no class monitor, and cover source/session changes, lease renewal, expiry and sample
staleness during verification; rejected in-flight samples must not enqueue a callback. Fixtures cover all seven hook signatures, partial/error/zero writes,
original exceptions, callback exceptions, nested-write suppression, throttling, float/byte
array paths, timestamped ByteBuffer position preservation, pause release/offload/unsupported formats,
lease expiry/release, idle scheduling, explicit targeting, spoofed UID/package, signing changes,
stale/future/wrong-session data and no automatic renewal. The decoder tests cover PCM8/16/24/32/
float, offsets, incomplete samples, invalid sizes/overflow, bounded sampling, non-finite floats,
and heap/direct/read-only/sliced buffers with cursor/mark preservation.

Both production files also compiled against the real Android 35 API jar with libxposed/Xp
fixtures (the available local JDK is 11; the actual API 102 artifact needs JDK 17). No Gradle
or lint was run. The standalone runner itself uses no network; the extra API compilation
check downloaded an Android 35 stub jar to a temporary directory.

These are **JVM fixture tests**, not real-device proof of Android broadcast delivery, OEM
permission behavior, libxposed interception or playback-engine compatibility. Before enabling
in the renderer, verify player-process injection, authenticated delivery, native/offloaded
silence, unchanged playback, and immediate local release on AOD/hide/pause on a device.
