# Code audit: full app (November 2026)

Scope: the whole app at `ba999a6` (all Kotlin sources, manifests, Gradle,
CI). The October review covered only a diff; its 10 findings are fixed and
not repeated here.

What was run: `gradle testDebugUnitTest lintDebug` (Gradle 8.9, Android
Studio JBR). Build OK, 17/17 tests pass, lint reports no issues, and the
compiler prints one warning (finding 15). Nothing was run on a device or
against the camera. Findings marked **check on device** depend on camera
or phone behavior that the code alone cannot show.

Each finding has a **Decision** line for us to fill in.

## Summary

| # | Problem | Severity | Confidence |
|---|---|---|---|
| 1 | A cancelled or stalled transfer can leak bytes into the next download | High | Medium (check on device) |
| 2 | Auto-reconnect never fires while mobile data is on | Medium | High |
| 3 | Changing a setting on a dead socket crashes the app | Medium | High |
| 4 | Reopening the app or reconnecting erases download state | Medium | High |
| 5 | `runCatching` swallows coroutine cancellation; a download can stay "in progress" forever | Medium | Medium |
| 6 | Deleting a downloaded file never works on Android 8–9 | Medium | High |
| 7 | A photo can be lost if removing its temporary video row fails | Medium | Medium |
| 8 | Local clip audio is flushed and re-seeked over and over | Medium | Medium (check on device) |
| 9 | Downloads while disconnected wait ~11 s each before failing | Low | High |
| 10 | Live view dies when a download starts and does not restart | Low | Medium |
| 11 | Streamed clips may play without audio | Low | Medium |
| 12 | Cancel race in `downloadOne` leaves a clip stuck | Low | High |
| 13 | Heartbeat restarts after a disconnect | Low | High |
| 14 | `exchange()` straggler drain does nothing | Low | High |
| 15 | Compiler warning: experimental `Channel.isEmpty` | Low | High |
| 16 | Pending MediaStore rows leak on two error paths | Low | High |
| 17 | `DebugLogger` does file I/O on the caller's thread, including Main | Low | High |
| 18 | `START_STICKY` with an in-memory queue | Low | Medium |
| 19 | Unused dependencies and permissions | Low | High |
| 20 | Debug-only code runs in release builds | Low | High |
| 21 | Tests cover only `AviMjpegPlayer` | Medium | High |
| 22 | No Gradle wrapper, formatter, static analysis or git hooks | Low | High |
| 23 | CI grants `contents: write` to pull-request runs | Low | High |
| 24 | `FilesScreen.kt` holds a full media player inside a composable | Low | High |
| 25 | Auto-download can skip a clip that shares the watermark second | Low | Medium |
| 26 | Unsynchronized shared lists in `AviMjpegPlayer` during streaming | Low | Medium |
| 27 | Wi-Fi settings panel can throw `ActivityNotFoundException` | Low | Low |

Suggested order: 2, 3, 4, 6 first (each is small and certain), then verify
1 and 8 on the device, then 21 (parser tests).

---

## 1. A cancelled or stalled transfer can leak bytes into the next download

- **Where:** `core/net/GpsocketClient.kt:488-495`, `:521-551`; abort paths
  `core/download/DownloadService.kt:220` (cancel), `:178-182` (stall retry),
  `ui/files/FilesScreen.kt:947` (closing the stream player)
- **Problem:** the client stops reading when the user cancels, a stall
  times out, or the player closes. No "abort transfer" command is known, so
  the camera may keep sending `0x0503` chunks. Chunks carry no record id.
  The next `downloadFile` subscribes to every `0x0503` frame. `awaitCmd`
  throws away stray chunks while it waits for the select and media-class
  acks, but chunks that arrive after the last ack and before
  `writeArgs(CMD_DOWNLOAD)` (`:513-521`, an 800 ms window) are written into
  the new file. A stray empty chunk (old terminator) ends the new download
  as `Refused`.
- **What breaks (if the camera keeps streaming):** cancel a long clip, the
  next queued clip starts at once and begins with foreign bytes. Because
  `received >= expected` counts bytes only, the corrupt file is saved as
  `Success`.
- **Check on device:** cancel a large clip, start a small one right away,
  compare the saved file size and its RIFF size field.
- **Options:**

  | Option | Speed to ship | Runtime cost | Correctness risk | Code cleanliness |
  |---|---|---|---|---|
  | Drain `ch` right before `writeArgs(CMD_DOWNLOAD)` | 1 line | None | Closes the 800 ms window; chunks still in flight after the request remain possible | Same as now |
  | Drain, plus check the first chunk starts with `RIFF` or `FF D8` | ~10 lines | None | Catches the rest; a mismatch fails as `Refused` | Small, local |
  | After an abort, wait until no `0x0503` arrives for ~2 s before releasing `requestLock` | ~15 lines | Up to the rest of the old file's transfer time | Removes the cause | Adds a quiet-period loop (assumption: camera eventually stops) |

  Recommendation: option 2. It is cheap and turns silent corruption into a
  visible retry.
- **Decision:** Fixed (`Drain an aborted transfer before the next download starts`). Confirmed on the device before the fix: a 192 KiB clip was saved with the RIFF header of an 8.5 MB one and reported done. The camera keeps streaming the whole rest of the file after an abort (measured 5.6 MB in 7.3 s, 9.6 MB in 12.8 s, 11.1 MB in 14.3 s), so the client now drains until the camera's terminator or 1.5 s of silence before the next command, and rejects a first chunk that is neither RIFF nor JPEG. The drain cap grows with the bytes left (front-channel clips reach ~120 MB, ~160 s). Device check after the fix: cancel then immediate download saved a correct file (header 196608 = file size).

## 2. Auto-reconnect never fires while mobile data is on

- **Where:** `core/session/CameraSession.kt:94-104`
- **Problem:** `registerDefaultNetworkCallback` tracks the app's *default*
  network. The Android docs say traffic "will never use a Wi-Fi network
  that does not provide Internet access … if another network that does
  offer Internet access (e.g. cellular data) is available". The camera
  hotspot has no internet, so with mobile data on, the default network is
  cellular. `onCapabilitiesChanged` never sees `TRANSPORT_WIFI`, and
  `maybeReconnect()` never runs. `onLost` also resets `wifiPresent` for any
  network loss, not only Wi-Fi.
- **What breaks:** the camera reboots or the phone re-joins the hotspot;
  the app stays disconnected until the user taps Connect. The feature
  works only when mobile data is off.
- **Fix:** `registerNetworkCallback(NetworkRequest.Builder()
  .addTransportType(TRANSPORT_WIFI).build(), callback)` and use
  `onAvailable` / `onLost`. `CameraNetworkBinder.bindToWifi` already finds
  the Wi-Fi network correctly by scanning `allNetworks`.
- **Decision:** Fixed (`Reconnect when the camera Wi-Fi returns, with mobile data on`, `Retry the automatic reconnect a few times`). Uses registerNetworkCallback on TRANSPORT_WIFI, drops the socket when the bound network is lost, and retries the reconnect up to three times. Not verified on a phone with mobile data; the test tablet has no cellular radio.

## 3. Changing a setting on a dead socket crashes the app

- **Where:** `ui/camera/CameraViewModel.kt:409-415` (`setSetting`),
  `:424` (`probe`)
- **Problem:** `client.setParam` writes to the socket. If the link died but
  the read loop has not noticed yet (up to the 15 s `soTimeout`,
  `GpsocketClient.kt:143`), `out.write` throws `SocketException`. Neither
  launch has `runCatching`, so the exception reaches `viewModelScope` and
  crashes the process. `loadSettings` and `refreshFiles` are wrapped; these
  two are not.
- **Fix:** wrap both in `runCatching`, and in `setSetting` treat a failure
  like a rejected value (restore `prev` and post the notice).
- **Decision:** Fixed (`Stop two crashes ...`). setSetting treats an IOException as a refused value. probe() and sendRaw() had no callers and were deleted instead of guarded.

## 4. Reopening the app or reconnecting erases download state

- **Where:** `ui/camera/CameraViewModel.kt:97-108`
- **Problem:** `_state` starts as `Disconnected`, and `client.state` replays
  `Connected`. Every new `CameraViewModel` therefore sees a
  "fresh connection" and calls `session.clearDownloads()`. The same happens
  on any reconnect, including the auto-reconnect while `DownloadService` is
  still working.
- **What breaks:** the user leaves the app with the back button during
  "Download all", then reopens it. Queued clips lose their marker and
  show the Download button again. Error marks from failed downloads
  disappear. The clip in progress comes back on its next progress tick.
- **Fix:** do not clear entries that are not `done`. Clear only finished
  entries, and only when the camera host or card actually changes (or drop
  the clear: `refreshLocalFiles` already reconciles successful entries).
- **Decision:** Fixed (`Keep download state across reconnects and app reopen`). The clear is removed; refreshLocalFiles already drops finished entries whose file is gone. Failure marks stay until retried.

## 5. `runCatching` swallows coroutine cancellation

- **Where:** `core/net/GpsocketClient.kt:290-292`, `:527-529`, `:562-570`;
  effect in `core/download/DownloadService.kt:265-272`, `:176-182`
- **Problem:** `runCatching { withTimeout { ch.receive() } }` also catches
  the `CancellationException` thrown when the calling coroutine is
  cancelled, and turns it into "timeout" (`Incomplete` / `null`).
  `listFiles` (`:415-421`) does it correctly by catching only
  `TimeoutCancellationException`.
- **What breaks:** the service is destroyed mid-transfer (`onDestroy` →
  `scope.cancel()`, `:324`). `downloadFile` returns `Incomplete` instead of
  throwing, `downloadAttempt` returns `false`, and `delay()` in
  `downloadOne` then throws. `finishFile` never runs, so
  `session.downloads` keeps a not-done entry. `CameraViewModel.download`
  (`:292`) refuses to re-queue it until the process dies.
- **Fix:** use `withTimeoutOrNull { … }` (returns null on timeout, rethrows
  cancellation) in all three places.
- **Decision:** Fixed (`Stop swallowing coroutine cancellation in GpsocketClient`). withTimeoutOrNull in exchange, awaitCmd and the download loop; the init sequence ignores only a refused or slow menu. Test: a cancelled caller no longer receives a made-up Incomplete (failed before the fix).

## 6. Deleting a downloaded file never works on Android 8–9

- **Where:** `ui/camera/CameraViewModel.kt:181` (builds `Uri.fromFile`),
  `:277-286` (`deleteLocal`)
- **Problem:** on API < 29, local files are `file://` URIs.
  `ContentResolver.delete()` needs a content provider; for a `file://` URI
  it throws `IllegalArgumentException("Unknown URL")`, which `runCatching`
  turns into 0 rows.
- **What breaks:** on Android 8 and 9 (the app's `minSdk` is 26), Delete
  always shows "delete failed".
- **Fix:** if `uri.scheme == "file"`, call `File(uri.path!!).delete()`.
- **Decision:** Fixed (`Fix MediaStore error paths`). file:// copies are deleted with File.delete(). Not run on an Android 8 or 9 device.

## 7. A photo can be lost if removing its temporary video row fails

- **Where:** `core/download/ClipStore.kt:90-96`
- **Problem:** the Images row is published (`IS_PENDING = 0`, `:90-92`),
  then the pending Video row is deleted (`:93`) inside the same `try`. If
  that delete throws, the `catch` deletes the published image, and the
  caller then `failFile`s the video row too.
- **What breaks:** a rare MediaStore error after a successful copy removes
  both copies; the download shows as failed.
- **Fix:** move the Video-row delete out of the `try` and wrap it in
  `runCatching`. A leftover pending row is cleaned by MediaStore later.
- **Decision:** Fixed (`Fix MediaStore error paths`). The pending video-row delete runs after the photo is published, outside the rollback.

## 8. Local clip audio is flushed and re-seeked over and over

- **Where:** `ui/files/FilesScreen.kt:1024-1043`
- **Problem:** the audio loop re-seeks whenever
  `pos != lastVideoPos + 1`, and sets `lastVideoPos = pos` once per write.
  Each write is 8192 bytes. Once the AudioTrack buffer is full, a write
  blocks for 8192 / byte-rate seconds. With 16 kHz mono 16-bit audio
  (32 KB/s), that is ~256 ms, while video advances one frame every ~66 ms.
  Video moves 3–4 frames per write, so every write triggers
  pause/flush/play and drops the buffered audio.
- **What breaks (expected):** audio with regular gaps. If the camera's AVI
  audio rate is above ~124 KB/s (8192 B per 66 ms), this does not happen.
- **Check on device:** play a local clip with sound; read the `strf` rate
  in `debug.log` or with `ffprobe`.
- **Options:**

  | Option | Speed to ship | Runtime cost | Correctness risk | Code cleanliness |
  |---|---|---|---|---|
  | Re-seek only on a real seek: set a `seekRequested` flag from the slider and skip buttons | ~6 lines | None | Video drift is not corrected (video already runs slow by decode time) | Clear intent |
  | Re-seek when audio and video differ by more than ~200 ms (compare `bytePos` time with `pos * frameMs`) | ~8 lines | None | Corrects drift and seeks with one rule | Clear, one threshold |

  Recommendation: option 2.
- **Decision:** Fixed (`Re-seek clip audio only when it drifts from the video`). Mechanism confirmed from a real camera clip (16 kHz mono, 6144-byte chunks, 30 fps). Audio now re-seeks only when played audio (playback head) and video differ by more than 200 ms, the video loop counts decode time against the frame interval, and the audio feeder runs on IO instead of Main. Not yet listened to on the device.

## 9. Downloads while disconnected wait ~11 s each before failing

- **Where:** `core/net/GpsocketClient.kt:193`, `:502-510`;
  `core/download/DownloadService.kt:136-149`
- **Problem:** `rawWrite` silently does nothing when `output == null`.
  `downloadFile` then waits 800 ms plus the full 10 s for a select ack and
  fails with "no select ack". The service never checks the connection.
  The Files tab works offline (cached list), so this path is reachable.
- **What breaks:** "Download all" on 40 clips while disconnected runs for
  ~7 minutes with a foreground notification, then shows 40 failures.
- **Fix:** in `downloadFile` (or the worker), return
  `Failed("not connected")` at once when the state is not `Connected`.
- **Decision:** Fixed (`Fix download service edge cases`). downloadFile returns NotConnected at once when there is no session. Test: returns in under 1 s and sends nothing.

## 10. Live view dies when a download starts and does not restart

- **Where:** `ui/live/LiveScreen.kt:116-135`; `GpsocketClient.kt:502`
- **Problem:** a download switches the camera to file mode, so RTP stops.
  `rtpSock.receive` times out after 8 s and Live shows an error.
  `repeatOnLifecycle` restarts only on a lifecycle change, so the stream
  stays dead after the download ends. Starting Live during a download
  waits on `requestLock` for the whole transfer while showing "waiting".
- **Fix:** key the player effect on `transferActive` as well, so Live
  pauses during a transfer and re-arms when it ends.
- **Decision:** Fixed (`Pause Live during downloads and resume after`). Not yet run on the device; the camera went offline during the session.

## 11. Streamed clips may play without audio

- **Where:** `ui/files/FilesScreen.kt:1008`
- **Problem:** `player.hasAudio` is read once, right after the first video
  frame is indexed. For a stream, the first audio chunk may not have
  arrived yet, and the audio job is never started later.
- **Fix:** start the audio job lazily inside the playback loop once
  `hasAudio` becomes true. Do this together with finding 26.
- **Decision:** Fixed (`Play audio on streamed clips and share the index safely`). The audio feeder waits for audio to appear while a clip streams. Test: hasAudio turns true when the first audio chunk arrives after the first frame.

## 12. Cancel race in `downloadOne` leaves a clip stuck

- **Where:** `core/download/DownloadService.kt:178-179`
- **Problem:** `downloadAttempt` returns `false` only when not cancelled
  (`:269`). If a cancel lands between that check and `:179`,
  `downloadOne` returns without `finishFile`. The id stays in `queuedIds`
  (re-queue rejected, `:113`) and the row stays "in progress".
- **Fix:** at `:179`, call `finishFile(f.uniqueId, error = cancelled)`
  before returning.
- **Decision:** Fixed (`Fix download service edge cases`).

## 13. Heartbeat restarts after a disconnect

- **Where:** `core/net/GpsocketClient.kt:556`
- **Problem:** the `finally` of `downloadFile` always calls
  `startHeartbeat()`. After `disconnect()` or a dropped link during a
  download, a heartbeat loop runs every 800 ms against `output == null`
  until the next connect.
- **Fix:** restart only if the state is `Connected`.
- **Decision:** Fixed (`Fix download service edge cases`). The heartbeat restarts only while connected.

## 14. `exchange()` straggler drain does nothing

- **Where:** `core/net/GpsocketClient.kt:280-287`
- **Problem:** the channel is created on the line above, so the drain loop
  always finds it empty. A late reply from a timed-out query arrives after
  the new collector subscribes and is taken as the next value. The comment
  describes protection that does not exist.
- **Fix:** delete the loop and its comment. For real protection, after a
  timeout, wait briefly and discard the late reply before the next query.
- **Decision:** Fixed (`Stop swallowing coroutine cancellation ...`). The no-op drain is deleted. Note: the later-found real cause of misaligned data on downloads was finding 1, not this.

## 15. Compiler warning: experimental `Channel.isEmpty`

- **Where:** `core/download/DownloadService.kt:158`
- **Problem:** `queue.isEmpty` needs `@OptIn(ExperimentalCoroutinesApi::class)`
  (Kotlin compiler warning).
- **Fix:** keep an `AtomicInteger` of queued items next to `enqueued`, or
  add the opt-in with a comment saying why.
- **Decision:** Fixed (`Add Gradle wrapper and device script, fail on warnings`). Replaced with a pending counter; Kotlin warnings now fail the build.

## 16. Pending MediaStore rows leak on two error paths

- **Where:** `core/download/ClipStore.kt:51-53`; `ui/live/LiveScreen.kt:228-234`
- **Problem:** if `openOutputStream` returns null (or `compress` throws in
  `saveSnapshot`), the row inserted just before is never deleted.
- **Fix:** delete the inserted URI on those paths.
- **Decision:** Fixed (`Fix MediaStore error paths`). saveSnapshot also no longer throws on Android 10+ and reports a failed compress.

## 17. `DebugLogger` does file I/O on the caller's thread, including Main

- **Where:** `core/DebugLogger.kt:133-145`; callers such as
  `GpsocketClient.writeArgs` (`:211`) run on Main when launched from
  `viewModelScope`
- **Problem:** every log line opens, appends and closes the file under a
  global lock; past 256 KiB it reads and rewrites the file. This also runs
  in release builds and on the socket read loop (`:679`, `:696`).
- **Fix:** log to the file only in debug builds, or hand lines to a
  single-thread writer.
- **Decision:** Fixed (`Keep debug code and the file log out of release builds`). File logging only in debug builds, written from one background thread.

## 18. `START_STICKY` with an in-memory queue

- **Where:** `core/download/DownloadService.kt:113`, `:122`
- **Problem:** after the process is killed, Android restarts the service
  with a `null` intent. The queue lived in memory, so nothing is
  downloaded, and the service never calls `stopSelf`.
- **Fix:** return `START_NOT_STICKY`.
- **Decision:** Fixed (`Fix download service edge cases`). START_NOT_STICKY.

## 19. Unused dependencies and permissions

- **Where:** `app/build.gradle.kts:419` (OkHttp), `:422-423` (Media3
  ExoPlayer + UI); `AndroidManifest.xml:8` (`CHANGE_WIFI_STATE`), `:9-12`
  (`NEARBY_WIFI_DEVICES`)
- **Problem:** `rg` finds no import of OkHttp or Media3 in `app/src`, and
  no code uses the two permissions. `CLAUDE.md` says Media3 is "kept for
  future playback", but both players are custom now. Unused permissions
  also show up on the Play data-safety and install screens.
  (`ACCESS_WIFI_STATE` is still needed by `CameraNetworkBinder.kt:49-51`.)
- **Fix:** remove all four and update the Stack section in `CLAUDE.md` and
  `AGENTS.md`.
- **Decision:** Fixed (`Remove unused OkHttp, Media3 and Wi-Fi permissions`). README privacy line updated to match.

## 20. Debug-only code runs in release builds

- **Where:** `ui/camera/CameraViewModel.kt:91-134` (frame-log collector),
  `:418-502` (probe, fetch, downloadFirst), `:513-519`
  (`SETTING_PARAM_IDS`, which repeats ids the menu XML already provides)
- **Problem:** the Debug screen is shown only when `BuildConfig.DEBUG`
  (`MainActivity.kt:249`), but the collector hex-formats every frame for
  the whole session in release builds too.
- **Fix:** move the debug actions and frame log into a debug-only
  ViewModel used by `DebugScreen`.
- **Decision:** Fixed (`Keep debug code ...`). DebugViewModel, created only by the debug-only Debug route; checked that the release dex contains none of it.

## 21. Tests cover only `AviMjpegPlayer`

- **Where:** `app/src/test/` (17 tests, one class)
- **Problem:** the riskiest pure code has no tests:
  `GpsocketClient.drainFrames` / `findMagic` (`:656-710`: magic split
  across reads, type-3 frames, partial frames, garbage before magic),
  `parseFileListPage` (`:572-593`: short page, bad date, wrong header),
  `MenuParser.parse`, `ClipStore.labelFor` and the file-name regex in
  `CameraViewModel.kt:170`. Findings 1, 5 and 12 are also testable with a
  fake socket (`ServerSocket` on localhost).
- **Fix:** start with frame parsing and the file-name mapping; both are
  pure functions. `drainFrames` and `findMagic` need to become `internal`.
- **Decision:** Fixed (`Add protocol tests and a fake camera` and later commits). 17 tests before, 50 after: frame splitting at every split point, file-list pages, name round trips, downloads against a localhost fake camera, auto-download selection, clip playback, logger. MenuParser stays untested (android.util.Xml is a JVM stub). Three deliberate code breaks were each caught by a test.

## 22. No Gradle wrapper, formatter, static analysis or git hooks

- **Where:** repo root; `.github/workflows/ci.yml:22-24`
- **Problem:** `gradlew` and `gradle-wrapper.jar` are not committed (only
  `gradle-wrapper.properties`), so a fresh clone needs Gradle 8.9
  installed. There is no ktlint/detekt configuration and no prek hooks.
- **Fix:** commit the wrapper (`gradle wrapper --gradle-version 8.9`), add
  ktlint (formatting) and detekt (static analysis), and a prek config that
  runs both.
- **Decision:** Fixed (`Add Gradle wrapper ...` and `Add ktlint, detekt and prek hooks; format the codebase`). ktlint 14.2.0 plugin, detekt 1.23.8 with a 58-entry baseline for old code, prek hooks (whitespace, ruff, zizmor, ktlint + detekt).

## 23. CI grants `contents: write` to pull-request runs

- **Where:** `.github/workflows/ci.yml:12-13`
- **Problem:** the single job needs write access only for the tag release,
  but has it for every push and pull request. (Fork pull requests get a
  read-only token anyway, so the exposure is same-repo branches.)
- **Fix:** split into a `build` job (`contents: read`) and a `release` job
  that runs only on tags.
- **Decision:** Fixed (`Give CI read-only access and move release to its own workflow`). Actions pinned to SHAs; actionlint and zizmor report nothing.

## 24. `FilesScreen.kt` holds a full media player inside a composable

- **Where:** `ui/files/FilesScreen.kt:901-1209` (1,209-line file)
- **Problem:** stream download, AVI indexing, the video decode loop and the
  AudioTrack feeder all live in a `LaunchedEffect`. `CLAUDE.md` says
  networking and logic stay out of the UI layer. It is also why findings
  8 and 11 are hard to test.
- **Fix:** move the playback loop into a `ClipPlayback` class (state as
  `StateFlow`), and keep the composable for drawing. Do this when fixing
  8 and 11, not as a separate refactor.
- **Decision:** Fixed (`Move clip playback out of the player composable`). ClipPlayback owns the loop; PlayerDialog draws it.

## 25. Auto-download can skip a clip that shares the watermark second

- **Where:** `ui/camera/CameraViewModel.kt:368-374`
- **Problem:** the batch is capped at 10 and the watermark moves to the
  last enqueued timestamp. The next run uses `isAfter(wm)`, so a record
  with the *same* timestamp (photo + video pairs share one) that fell
  outside the cap is never auto-downloaded.
- **Fix:** keep the watermark as (timestamp, uniqueId), or cut the batch
  only at a timestamp change.
- **Decision:** Fixed (`Keep same-second clips together in auto-download batches`).

## 26. Unsynchronized shared lists in `AviMjpegPlayer` during streaming

- **Where:** `core/media/AviMjpegPlayer.kt:192`, `:198`, `:209-210`, `:264`
- **Problem:** `frames` and `audioRefs` are the live `ArrayList`s that
  `extendIndex` appends to on one IO thread while `frameAt` / `audioRead`
  read them on another. `ArrayList` is not safe for this; a reader can see
  a new size with an old backing array.
- **Fix:** publish an immutable snapshot after each scan
  (`frames = videoRefs.toList()`); the copy is small next to a JPEG decode.
- **Decision:** Fixed (`Play audio on streamed clips and share the index safely`). Immutable snapshots through volatile fields; the audio index is one object.

## 27. Wi-Fi settings panel can throw `ActivityNotFoundException`

- **Where:** `ui/components/ConnectionGate.kt:313-318`
- **Problem:** some devices (some tablets, Android TV, custom ROMs) have no
  activity for `Settings.Panel.ACTION_WIFI`; `startActivity` then crashes.
- **Fix:** catch the exception and fall back to `ACTION_WIFI_SETTINGS`.
- **Decision:** Fixed (`Stop two crashes ...`). Falls back to ACTION_WIFI_SETTINGS.


## Follow-up from the review of the fixes

An independent review of the branch found issues in the fixes themselves;
each is fixed in its own commit (`Fix playback issues found in review`,
`Fix download issues found in review`, `Retry the automatic reconnect a few
times`):

- The clip audio feeder ran on the main thread, so each blocking write
  stalled the UI.
- A refused stream could show "no video frames": the transfer result was
  stored after "stream done" was set.
- A retry tapped while a cancelled clip was still draining was dropped.
- The drain cap (30 s) was too short for ~120 MB front-channel clips.
- A queue element lost to the idle-timeout receive kept the service and
  Live paused for the rest of the process.
- One reconnect attempt was not enough when the hotspot came up before its
  addresses or before the camera's server.

---

Checked and not a problem:

- RTP/JPEG frames go straight to `BitmapFactory` without RFC 2435 header
  rebuilding; it works because this firmware sends complete JPEGs.
- `ClipMetaCache` (`FilesScreen.kt:515-516`) does not double-close the
  descriptor: `FileInputStream(FileDescriptor)` does not own it.
- Wake lock with a 60-minute timeout (`DownloadService.kt:132`): releasing
  after the timeout does not throw on current Android.

## Sources

- Files read in full: `core/net/GpsocketClient.kt:1-720`,
  `core/download/DownloadService.kt:1-329`, `core/download/ClipStore.kt:1-110`,
  `core/session/CameraSession.kt:1-141`, `core/net/CameraNetworkBinder.kt:1-57`,
  `core/net/RtspMjpegClient.kt:1-360`, `core/media/AviMjpegPlayer.kt:1-302`,
  `core/settings/MenuParser.kt`, `core/DebugLogger.kt`, `EasyDashcamApp.kt`,
  `MainActivity.kt`, `ui/camera/CameraViewModel.kt:1-525`,
  `ui/files/FilesScreen.kt:1-1209`, `ui/live/LiveScreen.kt:1-242`,
  `ui/components/ConnectionGate.kt`, `ui/settings/SettingsScreen.kt`,
  `AndroidManifest.xml` (main and debug), `app/build.gradle.kts`,
  `.github/workflows/ci.yml`, `res/xml/*.xml`, `CLAUDE.md`,
  `docs/code-review-2026-10.md`
- Partly read: `ui/debug/DebugScreen.kt` (share and actions), test names
  in `AviMjpegPlayerTest.kt`
- Commands: `git log`, `git ls-files`, `git fetch origin` (in sync with
  `origin/main`), `rg` for OkHttp/Media3 imports and permission use,
  `gradle testDebugUnitTest lintDebug` (BUILD SUCCESSFUL, 17/17 tests,
  lint "No issues found", one Kotlin warning at `DownloadService.kt:158`)
- Docs: Android reference via Context7, `WifiManager.enableNetwork` note on
  Wi-Fi without internet, and `ConnectivityManager.registerDefaultNetworkCallback`
