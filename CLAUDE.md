# CLAUDE.md — Easy Dashcam

Android app (Kotlin + Jetpack Compose, MVVM) replacing the ad-heavy Viidure app for a cheap Wi-Fi dashcam. Local-only, no accounts, no ads, no cloud.

## Target camera protocol (reverse-engineered from pcaps)

- Camera IP: `192.168.100.1` (it hosts a Wi-Fi AP; phone joins it, no internet)
- **TCP 8081 — GPSOCKET binary control + data channel**
  - Frame: `"GPSOCKET"` (8-byte magic) + type (u16 LE: `1` phone→cam, `2` cam→phone) + cmd (u16 LE) + len (u16 LE) + payload
  - Known cmds: `0x0000` login/hello, `0x0004` file-list paging (payload starts with page index bytes), `0x00ff` time-sync (payload `"GPVENDOR"\0\0` + date/time bytes), `0x0303/0x0304` heartbeat/status, `0x0305` file-download data chunks (~32 KB each)
  - File downloads stream on the same TCP connection as 32 KB framed chunks
- **TCP 8080 — RTSP**: `rtsp://192.168.100.1:8080/?action=stream`, no auth. Video MJPEG (RTP payload type 26) over UDP + L16/16000 mono audio. Playable in VLC and ExoPlayer (`media3-exoplayer-rtsp`).

## Stack

- Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9, compileSdk 35, minSdk 26
- Compose BOM 2024.12.01, Material 3
- Media3 ExoPlayer (+rtsp, +ui) 1.5.1
- OkHttp 4.12.0 (fallback/HTTP), coroutines 1.9.0
- Package: `com.juanm.easydashcam`

## Conventions

- MVVM: Compose screens observe `StateFlow` from ViewModels; networking lives in a `GpsocketClient` class (raw `Socket`, `ByteBuffer` LITTLE_ENDIAN), never in the UI layer
- All camera traffic is plaintext TCP — keep `usesCleartextTraffic="true"`
- Design goal: everything works offline on the camera's AP; no internet permission usage beyond the local socket
