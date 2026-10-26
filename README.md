# AirCast

English | [简体中文](README.zh-CN.md)

**Cast audio playing on your Android phone to AirPlay 2 speakers — HomePod, Sonos, Apple TV, and more.**

Android phones speak Google Cast. AirPlay speakers speak AirPlay. The two don't
meet natively, so until now the usual answer was "get an iPhone". AirCast is
a small Android app that bridges the gap: it captures whatever is playing on
your phone and streams it to the AirPlay receiver on your Wi-Fi, directly from
the phone. No bridge box, no Raspberry Pi, no host machine.

Nothing leaves your local network. No account, no cloud, no telemetry.

## Interface

The screens are drawn in code around one liquid-glass material: a translucent
rounded slab over a slowly drifting aurora backdrop, pill buttons, grouped list
rows, and iOS-proportioned switches and sliders. The palette follows the system
light/dark setting, the system bars are drawn edge-to-edge, and their icon tint
flips with the theme.

The interface is original to this distribution and lives next to the app code:
`Ui.java` (design system), `GlassDrawable.java` (the material),
`GlassToggle.java`, `GlassSeekBar.java`, `GlassDialog.java` and
`AuroraView.java` (the backdrop).


## What it does

- **Streams any audio app** — music, podcasts, audiobooks, video, calls.
- **Discovers receivers automatically** over mDNS/Bonjour (`_airplay._tcp`),
  or type an IP address.
- **Routes your volume keys to the speaker** while casting, with on-screen
  +/− buttons as a fallback.
- **Mutes the phone speaker** while casting, so you don't hear both.
- **Works with no host** — everything runs on the phone.

## Requirements

- Android 10 (API 29) or newer
- arm64-v8a device
- An AirPlay 2 receiver on the **same Wi-Fi network**
- Android's capture consent (you tap once per session — the platform requires it)

## Documentation

| Doc | What it covers |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers, data flow, threading, JNI, lifecycle, mute state machine |
| [docs/FEATURES.md](docs/FEATURES.md) | Feature list, behaviour contracts, settings reference |
| [docs/PITFALLS.md](docs/PITFALLS.md) | **Every trap hit so far — read before changing anything** |
| [docs/BUILD.md](docs/BUILD.md) | Exact toolchain, environment setup, artifact verification |
| [docs/TESTING.md](docs/TESTING.md) | Manual test matrix (mute ownership is the core regression set) |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Next features, including per-app capture |

## Build from source

Everything needed is in the repository. There is no setup script and no
dependency download step — the AirPlay sender is vendored under
`airplay2-sender-cpp/` and Mbed TLS is a pinned git submodule.

```bash
git clone --recurse-submodules <repository-url>
cd aircast

# point at your SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # unsigned release APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If you cloned without `--recurse-submodules`, run:

```bash
git submodule update --init --recursive
```

**Toolchain requirements:** JDK 17+, Android SDK with `compileSdk 36`,
`build-tools 36.0.0`, NDK `27.2.12479018` and CMake `3.22.1`
(Google Gradle plugin via the wrapper: 9.8.0).
Full details, and the artifact checks to run before releasing, are in
[docs/BUILD.md](docs/BUILD.md).

### Release signing

The release build is unsigned unless signing properties are supplied, which is
what reproducible builders want. To sign it, put these in
`~/.gradle/gradle.properties` (never in the repository):

```properties
RELEASE_STORE_FILE=/absolute/path/to/keystore
RELEASE_STORE_PASSWORD=…
RELEASE_KEY_ALIAS=…
RELEASE_KEY_PASSWORD=…
```

## How it works

```
audio playing on the phone
  │  AudioPlaybackCapture (MediaProjection — one consent tap per session)
  ▼
PCM frames ──► JNI ──► ring buffer ──► AirPlay 2 sender ──► speaker
                                                     (patched, vendored)
```

`AudioPlaybackCapture` runs in a foreground service of type `mediaProjection` and
feeds raw PCM into a ring buffer. A native thread paces that buffer into the
AirPlay 2 session, using the vendored `airplay2-sender-cpp` library.

Two patches to that library are what make this work reliably; both are applied
in the vendored copy and described in `NOTICE`:

1. **AirPlay 2 transient-pairing event keys.** On the pairing path HomePods use,
   upstream derives only the control-channel keys, so the event channel is never
   keyed and the receiver drops the session after about 30 seconds — no matter
   that audio is flowing. AirCast derives the Events keys on that path too.
2. **Latency management.** While connecting, the buffer is drained toward ~0.25 s
   so the handshake doesn't leave a stale backlog; once live, a small drift
   splice above 1.6 s of queued audio absorbs clock drift.

Expect roughly **2–3 seconds** of delay. That is the AirPlay protocol's own
buffering, not something this app adds.

## Phone mute: what is automatic, and what is not

Silencing the phone during a cast turned out to be the hardest part of this app,
because some ROMs only honour a volume write that has a **real touch** behind it.
What the app does about it is deliberate — please read
[docs/PITFALLS.md](docs/PITFALLS.md#1-系统音量条会莫名从屏幕侧边弹出来)
before "fixing" any of it:

| Moment | Phone volume restored automatically? |
|---|---|
| Capture consent dialog: tapped X / cancelled | **Yes** (the return is touch-backed) |
| Capture consent dialog: dismissed by tapping outside | **No** — tap *Unmute phone* |
| Cast stopped from the notification shade | **No** — tap *Unmute phone* |
| Cast stopped with the on-screen **Stop casting** button | **Yes** |

The app never uses `FLAG_SHOW_UI`: the one time it did, it forced the system
volume bar onto the screen without making the write land.

## Repository layout

```
app/                    Android app (Java, no AndroidX) + JNI glue
  src/main/java/com/aircast/app/   MainActivity, SettingsActivity,
                                   CastService, AirplayDiscovery,
                                   Settings, CastLog, App, and the UI
                                   system (Ui, GlassDrawable, GlassToggle,
                                   GlassSeekBar, GlassDialog, AuroraView)
  src/main/cpp/                    native_sender.cpp (ring buffer + pump thread)
airplay2-sender-cpp/    Vendored AirPlay 2 sender (patched — see NOTICE)
third_party/mbedtls/    Crypto (git submodule, mbedtls-3.6.7)
docs/                   Developer documentation (architecture, features,
                        pitfalls, build, testing, roadmap)
dist/                   Prebuilt APK of the current version
```

## Known limitations

- Apps that opt out of playback capture play silence while casting. DRM and
  streaming apps (Netflix and friends) do this by design; no app can capture
  them.
- Capture is filtered by **audio usage, not by app**: any app playing media
  audio is picked up, so the system's "share one app / entire screen" choice
  does not narrow what is captured (the app never records the screen). Per-app
  capture is planned — see [docs/ROADMAP.md](docs/ROADMAP.md).
- The audio-latency setting applies to the **next** cast: it is fixed when the
  session is set up (the receiver's jitter-buffer window is negotiated in
  `SETUP`, and the RTP timeline is anchored to it).
- One consent tap per casting session (Android's MediaProjection requirement).
- No automatic reconnect if the receiver drops mid-session — stop and start.
- If another app owns your media session, the volume keys may adjust that app's
  volume instead of the speaker's. Use the on-screen +/− buttons.
- The capture rate the device actually grants may differ from what was
  requested (44.1 kHz vs 48 kHz); the app reads back the real rate and paces
  the sender to match.

## Privacy

AirCast collects nothing, stores nothing, and has no network code beyond
talking to the AirPlay receiver on your LAN. See [PRIVACY.md](PRIVACY.md).

## Credits and license

AirCast is licensed **Apache-2.0** — see [LICENSE](LICENSE). This distribution
reworks the interface and application code of the upstream AirCast project by
Wesley DeLand; the original copyright notices and every third-party notice are
preserved in [NOTICE](NOTICE), as Apache-2.0 requires.

It builds on **[airplay2-sender-cpp](https://github.com/akustikrausch/airplay2-sender-cpp)**
(Apache-2.0, © 2026 Andreas Wendorf), which is vendored under
`airplay2-sender-cpp/` with the patches noted above. That project in turn
credits [pyatv](https://github.com/postlund/pyatv) and
[pair_ap](https://github.com/ejurgensen/pair_ap) (both MIT) for protocol logic
it ports and cross-checks. Cryptography comes from
[Mbed TLS](https://github.com/Mbed-TLS/mbedtls) (Apache-2.0, included as a
submodule) and [orlp/ed25519](https://github.com/orlp/ed25519) (zlib).
Full notices are in [NOTICE](NOTICE) and [`licenses/`](licenses/).

"AirPlay", "HomePod", "Sonos" and "Apple TV" are trademarks of their respective
owners and are used here only to describe compatibility. AirCast is not
affiliated with or endorsed by Apple Inc. or any other speaker manufacturer.