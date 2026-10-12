# Easy Dashcam

A clean, free, local-only Android app for cheap Wi-Fi dashcams that work with the Viidure app.

## Why

The official Viidure app is ad-heavy, gates basic features behind a paid membership, and phones home to third-party servers while you browse your own camera. Easy Dashcam talks directly to the camera on its own Wi-Fi hotspot — no account, no ads, no telemetry.

## Features

- Live preview (RTSP/MJPEG, custom client — ExoPlayer can't do payload type 26)
- Browse recordings and photos on the camera's SD card
- Background downloads with progress notifications (MediaStore, shows in gallery)
- In-app playback with seeking — the camera's MJPEG-in-AVI files don't play in stock players
- Camera settings (menu-driven from the camera's own XML)
- No internet ever leaves your phone

## Camera protocol

Reverse-engineered from packet captures (see `../docs/recon/`). Camera = `192.168.100.1`:

- TCP `8081` — GPSOCKET binary protocol (commands, file list, downloads)
- TCP `8080` — RTSP live stream: `rtsp://192.168.100.1:8080/?action=stream`

## Build

Open in Android Studio, or from CLI (Gradle 8.9, JBR 21):

```
gradle :app:assembleDebug        # signed debug build
gradle :app:assembleRelease      # minified; signed if a keystore is present
gradle :app:testDebugUnitTest    # JVM unit tests
```

Release signing is optional: drop a `keystore.properties` at the repo root
(both files gitignored):

```
storeFile=easydashcam.jks
storePassword=...
keyAlias=easydashcam
keyPassword=...
```

Deploy to a connected device: `python tools/deploy_app.py` (`--logcat` tails logs).

## Stack

Kotlin, Jetpack Compose (Material 3), MVVM, OkHttp, coroutines. RTSP and AVI
playback are hand-rolled — see `docs/crash-course.md` for the full protocol tour.
