# Play Store listing — Easy Dashcam

## Title

Easy Dashcam — ad-free Wi-Fi dashcam viewer

## Short description (80 chars max)

View, stream, and download dashcam clips over Wi-Fi — no ads, no account, no cloud.

## Full description (4000 chars max)

Easy Dashcam is a local-only companion for Wi-Fi dashcams that speak the GPSOCKET protocol (the firmware family behind Viidure-compatible cameras).

Everything happens between your phone and the camera's own Wi-Fi hotspot. No account, no subscription, no ads, no telemetry — the app doesn't even need internet access to work.

**Live view**
- Real-time MJPEG preview with audio where the firmware provides it
- One-tap snapshots saved straight to your gallery

**Recordings**
- Browse every recording on the camera, grouped by day — list, grid, or timeline view
- Stream a clip instantly: it plays while the data arrives, no download step
- Download to keep, with background transfers that continue while you do something else
- Real thumbnails, true durations, seek, share, and delete for saved clips
- Optional auto-download of new clips as they appear on the card

**Camera control**
- Read and change camera settings (resolution, loop recording, WDR, audio, and more, as supported by your firmware)
- Protocol diagnostics and a live frame log for tinkerers

**Privacy by design**
- Works entirely on the camera's hotspot — never touches the internet
- No account, no analytics, no ads, no paywall
- Files you download stay on your device; share them only when you choose to

Compatibility: dashcams in the GPSOCKET/Viidure firmware family (typically sold under many brand names). If your camera's stock app is Viidure, this app is for you.

## Category

Auto & Vehicles (primary) — Tools (secondary)

## Content rating

Everyone

## Tags / keywords (for internal reference)

dashcam, car camera, dvr, wifi camera, viidure alternative, offline, no ads

## Graphic assets checklist

| Asset | Size | Status |
|---|---|---|
| App icon | 512×512 PNG | already in project |
| Feature graphic | 1024×500 PNG | TODO |
| Phone screenshots | min 2, 16:9 or 9:16 | blocked — capture on device |
| Tablet screenshots | optional | same |

## Release checklist

1. Add repo secrets for CI release builds: `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
2. Tag `v1.0.0` — CI builds signed APK + AAB and attaches them to the GitHub Release
3. Upload AAB to Play Console → closed track (14-day / 12-tester requirement for new personal accounts)
4. Paste listing text, upload screenshots + feature graphic
5. Data safety form — see `data-safety.md` for the answers
6. Privacy policy URL — host `privacy-policy.md` (GitHub Pages or raw link works during review)
