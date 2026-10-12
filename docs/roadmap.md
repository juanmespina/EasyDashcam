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

## Phase 0 — App shell (foundation for everything)

- Bottom navigation: **Live / Files / Settings** (+ overflow "Debug log").
- `navigation-compose` dependency (only new dep in this plan — standard lib).
- Connection state machine in `CameraViewModel`: Disconnected → Connecting →
  Connected → Error, surfaced on every screen ("join camera Wi-Fi" card).
- Theme: `MaterialTheme` defaults, dark-first.
- **Done when:** app opens on Live tab, Connect works from the shell, debug
  log reachable via menu, existing buttons still function.

## Phase 1 — Live view

- `PlayerView` (Media3) playing `rtsp://192.168.100.1:8080/?action=stream`.
- Player lifecycle tied to the screen (pause when backgrounded, release on
  leave); start/stop requires Connected state.
- Show buffering/offline states; a tap toggles controls.
- **Done when:** live MJPEG video plays full-screen on the camera hotspot.

## Phase 2 — Files

- List from `listFiles()`: icon by kind (L/A/J), timestamp, human size,
  grouped by day. Pull-to-refresh.
- Tap → download with progress (reuse `downloadFile()`), destination
  `MediaStore` `Movies/EasyDashcam/` — visible in gallery, survives reinstall.
- In-app playback of downloaded AVI via ExoPlayer; photos shown as image.
- Empty/error states; cancel-able download.
- **Done when:** a real clip downloads, appears in the gallery, and plays
  in-app without leaving the camera network.

## Phase 3 — Settings

- Parse `camera_menu.xml` → categories → `ListItem` rows (enums as dropdowns,
  toggles as switches, read-only Version/Name rows).
- On enter: `getParams()` for all ids; on change: `setParam()` + revert on
  failure. Show "applied" feedback.
- **Done when:** every setting in the menu reads correctly and at least one
  harmless toggle (e.g. WDR) round-trips on hardware.

## Phase 4 — Polish & release

- Foreground-service download for large clips (manifest already prepares it).
- Reconnect handling: hotspot drop → clean Disconnected state, no stuck UI.
- Loading skeletons, content descriptions, edge-to-edge insets.
- Debug log behind `BuildConfig.DEBUG` or a hidden menu entry.
- Release build: minify+shrinkResources, signed APK, versionName in About row.
- **Done when:** cold start → connect → live view → download a clip → change
  a setting, all without a dev PC attached.

## Later / unknown (not blocking)

- `0x0403` thumbnails — every probe returned `f8ff`; likely unsupported on
  this camera. Revisit only if a capture ever shows a non-error reply.
- `0x0100` status blob fields — decode when a UI feature needs them
  (storage %, recording state are the candidates).
- File delete/lock commands — never observed; would need a labeled capture.
- Set-param on string params (device name) — different encoding than enums.
