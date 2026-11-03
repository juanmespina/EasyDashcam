# AGENTS.md — Easy Dashcam

Android app (Kotlin + Jetpack Compose, MVVM) replacing the ad-heavy Viidure app for a cheap Wi-Fi dashcam. Local-only, no accounts, no ads, no cloud.

## Target camera protocol (reverse-engineered from pcaps)

- Camera IP: `192.168.100.1` (it hosts a Wi-Fi AP; phone joins it, no internet)
- **TCP 8081 — GPSOCKET binary control + data channel**
  - Frame: `"GPSOCKET"` (8-byte magic) + type (u16 LE: `1` phone→cam, `2` cam→phone) + cmd (u16 LE) + body — 12-byte header, body shape depends on cmd (bare / u16 LE len + payload / u32 inline arg)
  - Known cmds: `0x0500` login (TX u32 token → RX 6B key), `0x0100` keepalive/status (TX bare → RX 27B blob ~1/s), `0x0004` get-param (TX u32 param id → RX u8 value or string), `0x0104` set-param (TX `param u16 | 00 00 | 0x01 | value u8` → RX empty ack), `0x0200` settings menu XML (TX bare → RX 498B chunks + empty terminator), `0x00ff` time-sync (`"GPVENDOR"\0\0` + datetime), `0x0203` file count, `0x0303` file list (TX `00` start / `00`+id resume → RX `0x10` + 13B records: type,id,datetime,sizeKB, 16/page), `0x0700` auth round (u32→4B), `0x0000` mode gate (arg u8: `02`=file list, `03`=settings — camera ignores those ops until mode is entered; pace commands one reply at a time, bursts get dropped), `0x0001/0x0400/0x0800` session steps, `0x0403` per-record probe (→ `f8ff`)
  - File download (verified): `0x0400` → `0x0003`+id (select) → `0x0603`+kind byte → `0x0503`+id → RX `0x0503` 32 KiB chunks + len=0 terminator; total = record.size×1024; payload is RIFF AVI; `feff` type-3 reply = refused
  - Download pacing: re-enter file mode (`0x0000` arg `0x02`) first — the sequence feffs from preview/settings modes; space steps ~800 ms apart (Viidure uses ~1–3 s; a ~150 ms burst races the camera's state). A `feff` on a *recent* record usually means the camera is still writing it — retry older records.
  - No abort command (verified 2026-11-03): once `0x0503` is sent the camera streams the whole file even if the app stops reading — measured ~750 KB/s, 5–11 MB of tail over 7–14 s. Chunks carry no record id, so the tail lands in the next transfer unless drained first: `GpsocketClient.drainAbortedStream` reads `0x0503` until the empty terminator (or a quiet gap) before releasing the lock.
  - Locked (`L`) records are unservable: `0x0503` is feff-refused for them even with the full preamble, and Viidure's captures never show one transferred. The app hides them from the file list.
  - Modes are global: downloads and live preview conflict — whichever enters its mode last wins.
  - Record ids are NOT unique (photo+video pairs share one; that's why `0x0603` exists). Key records by `(id << 8) | kind` (`CameraFile.uniqueId`), and even that can collide — add list position for UI keys.
  - type=3 RX frames: 14 bytes, fixed 2-byte arg, no len field (`f8ff`/`feff` errors)
  - Full decode + param map: `../docs/recon/pcap_11oct_analysis.txt`, `../docs/recon/camera_menu.xml`
- **TCP 8080 — RTSP**: `rtsp://192.168.100.1:8080/?action=stream`, no auth. Video MJPEG (RTP payload type 26) over UDP + L16 audio track.
  - Camera only pushes media after GPSOCKET mode `0x01` (`enterPreviewMode()`); Viidure's pcaps show PLAY→200 OK but zero media without it.
  - `media3-exoplayer-rtsp` CANNOT play this stream (`Unsupported static paylod type 26`) — use `RtspMjpegClient` (own RTSP session + RFC 2435 depacketizer + BitmapFactory). VLC works.
  - Session quirks (verified): answers TEARDOWN with `501`, holds ~one session — leaving Live must also drop GPSOCKET to mode `0x00` or the next SETUP gets `404`. Handshake must match Viidure's FFmpeg client: `CSeq` restarts at 1 per connection, `User-Agent: Lavf58.12.100`, SETUP **every** SDP track (video `track0` + audio `track1`) before PLAY. A track0-only SETUP gets `404` on this firmware.

## Stack

- Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9, compileSdk 35, minSdk 26
- Compose BOM 2024.12.01, Material 3
- No media library: RTSP is custom (`RtspMjpegClient`), AVI/MJPEG playback is custom (`AviMjpegPlayer`)
- Coroutines 1.9.0
- Package: `com.juanm.easydashcam`
- Checks: `./gradlew ktlintCheck detekt lintDebug testDebugUnitTest` (prek runs ktlint + detekt on commit; Kotlin warnings fail the build). `FakeCamera` in the unit tests is a localhost GPSOCKET server.
- On a device: `python tools/deploy_app.py`, then `python tools/device.py` (connect, download, cancel, log, header) against the camera hotspot.

## Conventions

- MVVM: Compose screens observe `StateFlow` from ViewModels; networking lives in a `GpsocketClient` class (raw `Socket`, `ByteBuffer` LITTLE_ENDIAN), never in the UI layer
- All camera traffic is plaintext TCP — keep `usesCleartextTraffic="true"`
- Design goal: everything works offline on the camera's AP; no internet permission usage beyond the local socket
