# Easy Dashcam — roadmap

Local-only Viidure replacement. The camera protocol is fully decoded and
verified live (§3 of `crash-course.md`); everything below is product work —
no more reverse engineering is required to ship.

## Principles

- **Stock Material 3 look.** No custom design system; good defaults, clean
  spacing, dark theme.
- **Standard Android practices:** MVVM, one Activity, Navigation Compose,
  StateFlow + `collectAsStateWithLifecycle`, all networking inside
  `GpsocketClient`, downloads via `MediaStore`, string resources for UI text.
- **Offline-first:** everything must work on the camera hotspot with zero
  internet. The debug frame log stays, hidden behind a dev toggle.
- **Fast and background-friendly.** Downloads keep running with the screen
  off / app backgrounded (foreground service + progress notification). One
  `GpsocketClient` per camera session, owned by the process — not by a screen —
  so service and UI share the same socket.

## Phase 0 — App shell (foundation for everything)

- Bottom navigation: **Live / Files / Settings** (+ overflow "Debug log").
- `navigation-compose` dependency (only new dep in this plan — standard lib).
- Connection state machine in `CameraViewModel`: Disconnected → Connecting →
  Connected → Error, surfaced on every screen ("join camera Wi-Fi" card).
- Theme: `MaterialTheme` defaults, dark-first.
- **Done when:** app opens on Live tab, Connect works from the shell, debug
  log reachable via menu, existing buttons still function.

## Phase 1 — Live view — ✅ DONE (hardware-verified)

- `RtspMjpegClient` (in `core/net/`): hand-rolled RTSP handshake + RFC 2435
  depacketizer + `BitmapFactory` frames into a Compose `Image`.
  Media3-rtsp cannot parse RTP payload type 26 (MJPEG) — dead end, removed.
- Camera must be armed first: GPSOCKET mode `0x01` (`enterPreviewMode`).
- Stream lifecycle tied to `Lifecycle.State.STARTED` — stops in background.
- **Done when:** live MJPEG video plays full-screen on the camera hotspot. ✓

## Phase 2 — Files + background downloads — ✅ DONE (hardware-verified)

- `CameraSession` refactor: move `GpsocketClient` ownership from
  `CameraViewModel` to an Application-scoped holder. ViewModel and the download
  service both use it — this is what makes downloads survive the UI.
- List from `listFiles()`: icon by kind (L/A/J), timestamp, human size,
  grouped by day. Pull-to-refresh.
- Tap → `DownloadService` (foregroundServiceType `dataSync`, manifest already
  declares it): `downloadFile()` into `MediaStore` `Movies/EasyDashcam/`,
  progress notification with cancel action. Queue = sequential (the protocol
  serializes transfers anyway — one socket, one request at a time).
- Runtime `POST_NOTIFICATIONS` prompt on first download (API 33+).
- In-app playback of downloaded AVI via `AviMjpegPlayer` (RIFF demux + JPEG
  decode — the same dep-free approach as the live view; stock players and
  ExoPlayer are unreliable for MJPEG-in-AVI).
- Local-copy detection via MediaStore scan (`cam_<id><kind>.avi`) so
  downloaded rows stay marked across restarts.
- **Done when:** a real clip downloads while the screen is off, shows progress
  in the notification shade, lands in the gallery, and plays in-app.

## Phase 3 — Settings — implemented (menu loads on hardware; setParam round-trip unverified)

- Parse `camera_menu.xml` → categories → `ListItem` rows (enums as dropdowns,
  toggles as switches, read-only Version/Name rows).
- On enter: `getParams()` for all ids; on change: `setParam()` + revert on
  failure. Show "applied" feedback.
- **Done when:** every setting in the menu reads correctly and at least one
  harmless toggle (e.g. WDR) round-trips on hardware.

## Phase 4 — Polish & release — mostly done (release flow verified; last item needs hardware)

- Reconnect handling: hotspot drop → clean Disconnected state via a 15 s
  read timeout (the camera's ~1/s status blob is the liveness signal).
- Content descriptions + edge-to-edge insets: done.
- Debug log in the overflow menu, debug builds only (`BuildConfig.DEBUG`).
- Release build: minify+shrinkResources + optional signing from a
  gitignored `keystore.properties`; verified `assembleRelease` produces a
  signed APK. `versionName` shows in a Settings About row.
- Lint clean: 0 warnings (deprecation/manifest/icon issues fixed; the
  Kotlin-2.0 lint crash rule and pinning-nag rules disabled with reasons).
- **Done when:** cold start → connect → live view → background-download a
  clip with screen off → change a setting, all without a dev PC attached.
  ← needs a hardware pass; everything else is verified.

## Later / unknown (not blocking)

- `0x0403` thumbnails — every probe returned `f8ff` (dozens of records in the
  Oct-6 capture; Viidure's list shows no thumbnails either). Unsupported on
  this firmware. Revisit only if a capture ever shows a non-error reply.
- `feff` on newest record — the camera refuses downloads of the file it is
  currently writing; verified: same record refuses, older ones stream fine.
- `0x0100` status blob fields — decode when a UI feature needs them
  (storage %, recording state are the candidates).
- File delete/lock commands — never observed; would need a labeled capture.
- Set-param on string params (device name) — different encoding than enums.
