# Easy Dashcam

Easy Dashcam is a local-only Android companion app for compatible Wi-Fi dashcams normally used with Viidure. It connects your phone directly to the camera so you can watch the live feed, browse recordings and photos, save media to your phone, and change camera settings.

This app requires a separate, compatible dashcam; it does not turn your phone into one. It runs on Android 8.0 or later (API 26).

Easy Dashcam is not affiliated with Viidure or any camera maker. It works by talking to the camera with a protocol that was reverse-engineered from network captures.

## Objective

Make everyday dashcam tasks accessible without an account, advertising, subscriptions, or a cloud connection. Your camera and recordings should be usable directly, even when the camera's Wi-Fi network has no internet access.

Easy Dashcam is an alternative to the Viidure workflow, focused on local access to your own hardware and media rather than online services.

## Screenshots

| Live view | Files | Settings |
|-----------|-------|----------|
| ![Live view](docs/screenshots/live.png) | ![Files](docs/screenshots/files.png) | ![Settings](docs/screenshots/settings.png) |

## Why replace Viidure?

The original motivation for Easy Dashcam was Viidure's ad-heavy experience and observed connections to external, third-party servers while browsing the camera. Accessing recordings on your own camera should not require those extras.

Easy Dashcam talks directly to the camera on its own Wi-Fi hotspot — no account, no ads, no telemetry, and no cloud connection.

The external-server activity is a concern about unnecessary network communication, not proof that recordings were uploaded. Three observations back the concern:

- **Our packet captures** (taken while the phone was connected to the camera's internet-less hotspot) show DNS lookups for `app-api.lufengzhe.com` — Viidure's own cloud API — plus `mshield.baidu.com`, `loc.map.baidu.com`, and `api.map.baidu.com`, and a live TLS session to a Baidu IP. The captures are not app-attributed and the TLS payloads were not decrypted.
- **Its location permission** goes beyond what Android requires: any app joining a device Wi-Fi network needs location access, but Viidure also queries `loc.map.baidu.com` — Baidu's geolocation service — so the granted permission feeds a third-party location SDK, not just the Wi-Fi connection.
- **Independent research** ([Brown Fine Security](https://brownfinesecurity.com/blog/police-bodycam-data-to-china), covered by [GBHackers](https://gbhackers.com/police-body-camera-apps/)) decrypted Viidure's traffic and found it posts device identifiers (IMEI, model, app version) to `app-api.lufengzhe.com` on a Huawei-hosted IP block, and that the app accepts forged TLS certificates.

Easy Dashcam declares no location permission and no Wi-Fi scanning permission. It finds the camera through the joined network's gateway address, so it can never read your GPS.

## Features

### Live view

- Watch the camera's live video feed over its Wi-Fi connection.
- Listen to live audio, with a mute control.
- Save a snapshot of the current frame to your phone.
- Keep the screen awake while watching the live feed.

### Recordings and photos

- Browse the camera's SD card through All, Videos, Photos, and On device subtabs.
- Filter by date (today, yesterday, or a chosen day) and sort within each day by time or file size.
- Switch between list, grid, and timeline views.
- Use the last cached camera file list and open saved copies while disconnected. On device shows saved files matched against that list, not every file on the phone.
- See available phone storage in the file-list footer.

### Downloads and playback

- Download media in the background through a foreground `DownloadService`, with progress notifications and in-row cancellation.
- Download individual files or queue all missing files in the current filtered view.
- Toggle automatic downloads for new, unlocked media recorded after the option is enabled. Files are queued when the camera file list refreshes, not by a continuous background camera scan.
- Stream a recording without downloading it first, and save the streamed copy to your phone.
- Play downloaded recordings inside the app with seeking, including the camera's MJPEG-in-AVI format that stock players may not support.
- View saved photos inside the app.
- On Android 10 and later, save photos and snapshots in MediaStore Images under `Pictures/EasyDashcam`, and videos under `Movies/EasyDashcam`. Android 8–9 use app-specific external storage instead.

### Camera settings

- Read the camera's own settings menu and available options.
- Change supported toggle and selection settings directly from the phone.
- View read-only information exposed by the camera, such as its firmware version.

Debug builds also expose a diagnostics screen with a protocol frame log through the overflow menu; this menu entry is not available in release builds.

## Install

Download `app-release.apk` from the [latest release](https://github.com/juanmespina/EasyDashcam/releases/latest) and open it on your phone. Android asks you to allow installing apps from your browser or file manager the first time.

To get updates automatically, add `https://github.com/juanmespina/EasyDashcam` to [Obtainium](https://github.com/ImranR98/Obtainium).

Found a bug, or tested another camera? [Open an issue](https://github.com/juanmespina/EasyDashcam/issues/new/choose).

## How it works

1. Turn on your dashcam and enable its Wi-Fi hotspot.
2. Connect your Android phone to the camera's Wi-Fi network. Internet access is not required. The connection screen provides a shortcut to Android's Wi-Fi panel (Wi-Fi settings on Android 8–9).
3. Open Easy Dashcam and connect to the camera.
4. Use the Live, Files, and Settings screens to access the camera.

Camera communication stays on the local network. The app does not require an account or a cloud service.

## Compatibility and limitations

- Tested with a camera whose Settings > Version screen shows `1084-w90-WIFI-7789` and firmware `20250306 V1.0`.
- The implementation targets the reverse-engineered GPSOCKET camera protocol described below. Compatibility with every camera supported by Viidure is not guaranteed.
- Your camera is likely compatible if it works with Viidure, its Wi-Fi network is named like `wifi-camera_xxxxxxxx`, and it uses the address `192.168.100.1`. If yours works or does not, please open an issue with its model.
- Live preview and downloads use different camera modes and cannot operate independently at the same time. A download can interrupt the live feed.
- Locked event clips are hidden because the target camera refuses to transfer them.
- A recording that the camera is still writing may not be available for download yet; try an older clip or retry later.
- Destructive camera actions, such as formatting the SD card or resetting the camera, are not exposed in Settings.

## Camera protocol

The target camera hosts a Wi-Fi access point at `192.168.100.1`:

- TCP `8081` — GPSOCKET binary protocol for commands, settings, file lists, and downloads.
- TCP `8080` — RTSP session at `rtsp://192.168.100.1:8080/?action=stream`, without authentication. Media travels over UDP: MJPEG video (RTP payload type 26) and an L16 audio track.
- Live media requires entering GPSOCKET preview mode before playback. Camera modes are shared between preview, settings, and file transfers.

See [AGENTS.md](AGENTS.md) for protocol details and [the captured settings menu](docs/camera_menu.xml) for the settings map. [The privacy policy](docs/store/privacy-policy.md) is under `docs/`.

## Build

Use Android Studio or a command-line Android SDK installation with SDK Platform 35. Set the SDK location in an untracked `local.properties` (`sdk.dir=...`) or through `ANDROID_HOME`. Use JDK 21 (Android Studio's JBR 21 is suitable) and set `JAVA_HOME` to it. The Gradle wrapper is included, so no separate Gradle install is needed. Run these commands from the repository root:

```sh
./gradlew :app:assembleDebug        # signed debug build
./gradlew :app:assembleRelease      # minified; signed only with release signing configured
./gradlew :app:lintDebug            # Android lint
./gradlew :app:testDebugUnitTest    # JVM unit tests
```

The application ID is `com.juanm.easydashcam`; the current version is `0.3.0` (version code 4). Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

Release signing is optional: put a keystore and a `keystore.properties` file at the repository root. The properties file and `*.jks` / `*.keystore` files are gitignored. Merely placing a keystore there is not enough; configure all four properties:

```properties
storeFile=easydashcam.jks
storePassword=...
keyAlias=easydashcam
keyPassword=...
```

Install a debug build on an authorized USB-debugging device with Android SDK Platform Tools:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`python tools/deploy_app.py` builds the debug app with the wrapper, installs it, and launches it; add `--logcat` to only follow the logs. It is written for Windows: it looks for `adb.exe` under `ANDROID_HOME` (or takes the `ADB` variable) and uses Android Studio's JBR when `JAVA_HOME` is not set.

## Stack

Kotlin 2.0.21, Android Gradle Plugin 8.7.3, Gradle 8.9, compile/target SDK 35, minimum SDK 26, Jetpack Compose (BOM 2024.12.01, Material 3), MVVM, and coroutines 1.9.0. Java/Kotlin bytecode targets Java 17; the build uses JDK 21.

RTSP/MJPEG live viewing (`RtspMjpegClient`) and AVI/MJPEG playback (`AviMjpegPlayer`) use custom implementations, with no media library.

## Contributing

Easy Dashcam is a hobby project maintained by one person who owns one camera, so support for other cameras grows through [compatibility reports](https://github.com/juanmespina/EasyDashcam/issues/new?template=camera-compatibility.yml). Report security problems as described in [SECURITY.md](SECURITY.md).

Issues and pull requests are welcome. Before opening a pull request, run the same checks as CI:

```sh
./gradlew ktlintCheck detekt lintDebug testDebugUnitTest
```

Kotlin warnings fail the build. [prek](https://github.com/j178/prek) runs ktlint and detekt on each commit after `prek install`. `python tools/device.py` drives a debug build on a phone joined to the camera Wi-Fi (connect, download, cancel, read the log); see its header for the commands. [AGENTS.md](AGENTS.md) has the protocol notes and code conventions.

## License

GPL-3.0 — see [LICENSE](LICENSE).
