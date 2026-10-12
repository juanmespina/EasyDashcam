# AGENTS.md — Easy Dashcam

Android app (Kotlin + Jetpack Compose, MVVM) replacing the ad-heavy Viidure app for a cheap Wi-Fi dashcam. Local-only, no accounts, no ads, no cloud.

## Target camera protocol (reverse-engineered from pcaps)

- Camera IP: `192.168.100.1` (it hosts a Wi-Fi AP; phone joins it, no internet)
- **TCP 8081 — GPSOCKET binary control + data channel**
  - Frame: `"GPSOCKET"` (8-byte magic) + type (u16 LE: `1` phone→cam, `2` cam→phone) + cmd (u16 LE) + body — 12-byte header, body shape depends on cmd (bare / u16 LE len + payload / u32 inline arg)
  - Known cmds: `0x0500` login (TX u32 token → RX 6B key), `0x0100` keepalive/status (TX bare → RX 27B blob ~1/s), `0x0004` get-param (TX u32 param id → RX u8 value or string), `0x0104` set-param (TX `param u16 | 00 00 | 0x01 | value u8` → RX empty ack), `0x0200` settings menu XML (TX bare → RX 498B chunks + empty terminator), `0x00ff` time-sync (`"GPVENDOR"\0\0` + datetime), `0x0203` file count, `0x0303` file list (TX `00` start / `00`+id resume → RX `0x10` + 13B records: type,id,datetime,sizeKB, 16/page), `0x0700` auth round (u32→4B), `0x0000` mode gate (arg u8: `02`=file list, `03`=settings — camera ignores those ops until mode is entered; pace commands one reply at a time, bursts get dropped), `0x0001/0x0400/0x0800` session steps, `0x0403` per-record probe (→ `f8ff`)
  - File download (verified): `0x0400` → `0x0003`+id (select) → `0x0603`+kind byte → `0x0503`+id → RX `0x0503` 32 KiB chunks + len=0 terminator; total = record.size×1024; payload is RIFF AVI; `feff` type-3 reply = refused; run the steps back-to-back (a 24 s select→request gap got `feff`)
  - type=3 RX frames: 14 bytes, fixed 2-byte arg, no len field (`f8ff`/`feff` errors)
  - Full decode + param map: `../docs/recon/pcap_11oct_analysis.txt`, `../docs/recon/camera_menu.xml`
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
