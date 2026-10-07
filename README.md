# Easy Dashcam

A clean, free, local-only Android app for cheap Wi-Fi dashcams that work with the Viidure app.

## Why

The official Viidure app is ad-heavy, gates basic features behind a paid membership, and phones home to third-party servers while you browse your own camera. Easy Dashcam talks directly to the camera on its own Wi-Fi hotspot — no account, no ads, no telemetry.

## Features (roadmap)

- Live preview (RTSP, MJPEG)
- Browse recordings on the camera's SD card
- Download clips to phone
- One-tap save/share
- No internet ever leaves your phone

## Camera protocol

Reverse-engineered from packet captures. Camera = `192.168.100.1`:

- TCP `8081` — GPSOCKET binary protocol (commands, file list, downloads)
- TCP `8080` — RTSP live stream: `rtsp://192.168.100.1:8080/?action=stream`

## Build

Open in Android Studio, or from CLI once a Gradle wrapper jar is present:

```
gradlew :app:assembleDebug
```

## Stack

Kotlin, Jetpack Compose (Material 3), MVVM, Media3 ExoPlayer, OkHttp.
