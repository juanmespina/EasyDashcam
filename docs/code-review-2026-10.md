# Code review: unpushed commits (October 2026)

Scope: the 88 commits in `origin/main..f97a7ba` (43 files, about 5,300 lines
added). Each finding below was checked against the source by reading it.
Nothing was built, run on a device or tested.

Each finding has a **Decision** line for us to fill in: fix now, fix later,
or not a problem.

## Summary

| # | Problem | Severity | Confidence |
|---|---|---|---|
| 1 | Write errors during a download crash the app | High | High |
| 2 | A cancelled clip can never be downloaded again | High | High |
| 3 | Local playback can freeze after garbage collection | High | Medium |
| 4 | Download chunks are dropped when the disk is slow | High | Medium |
| 5 | Race at the idle shutdown of the download service | Medium | Medium |
| 6 | Queued clips can be queued again and download twice | Medium | High |
| 7 | MediaStore scan on every progress tick, and failed marks get erased | Medium | High |
| 8 | Photos saved as Video entries, then changed to JPEG | Medium | Low |
| 9 | Auto-download start time is never reset | Low | High |
| 10 | RTSP quantization tables skipped on the wrong packets | Low | Low |

Suggested order: 1 to 5 first, one small commit each, with a test for every
fix that can be tested without a device.

---

## 1. Write errors during a download crash the app

- **Where:** `app/src/main/java/com/juanm/easydashcam/core/download/DownloadService.kt:195`
- **Problem:** `downloadOne` catches only `CancellationException`. The worker
  runs on a `SupervisorJob` scope with no exception handler.
- **What breaks:** the phone storage fills up, or the MediaStore stream breaks
  in the middle. `out.write()` in `GpsocketClient.kt:517` throws an
  `IOException`. The app crashes, `failFile()` never runs, and the pending
  MediaStore entry stays behind as an invisible half file.
  `ClipStore.open()` (`ClipStore.kt:51-53`) can throw in the same way.
- **Proposed fix:** also catch `IOException` (and wrap `ClipStore.open`), then
  call `failFile` with the error message.
- **Decision:** Fix now — `IOException` on `out.write()` or a throwing `ClipStore.open` crashed the supervisor scope. Both now fail the file with the error message.

## 2. A cancelled clip can never be downloaded again

- **Where:** `DownloadService.kt:104`, checked at `:129` and `:175`
- **Problem:** cancel adds the clip id to `cancelled`, and nothing ever
  removes it.
- **What breaks:** the user cancels a clip, then taps retry. The worker sees
  the id in `cancelled` and ends it as "cancelled" right away. This repeats
  until Android kills the process.
- **Proposed fix:** remove the id from `cancelled` when the clip is queued
  again (`ACTION_DOWNLOAD`) or when it finishes.
- **Decision:** Fix now — `queuedIds` set plus `cancelled -= id` on re-queue; a cancelled clip downloads again.

## 3. Local playback can freeze after garbage collection

- **Where:** `app/src/main/java/com/juanm/easydashcam/ui/files/FilesScreen.kt:916`
- **Problem:** the player is built from
  `openFileDescriptor(uri, "r")!!.fileDescriptor`. The `ParcelFileDescriptor`
  is not kept anywhere, and it closes its file when it is garbage collected.
- **What breaks:** a garbage collection during playback closes the file.
  `AviMjpegPlayer` reads fail, video freezes and audio stops in the middle
  of a local clip. `FilesScreen.kt:513` does it correctly with `.use`.
- **Proposed fix:** use `ParcelFileDescriptor.AutoCloseInputStream(pfd)`.
  It owns the descriptor, and the player already closes its channel.
- **Decision:** Fix now — confirmed: nothing held the `ParcelFileDescriptor`. `AutoCloseInputStream` now owns it for the player's lifetime.

## 4. Download chunks are dropped when the disk is slow

- **Where:** `app/src/main/java/com/juanm/easydashcam/core/net/GpsocketClient.kt:463-467`
- **Problem:** frames go into `Channel<Frame>(64)` with `trySend`. When the
  channel is full, `trySend` drops the frame without any error.
- **What breaks:** 64 chunks of 32 KiB is about 2 MiB. If the disk write,
  a garbage collection or a notification update blocks the writer for about
  1 second, chunks are lost. The download ends as "stalled" (`Incomplete`)
  and long clips need a retry.
- **Options:**

  | Option | Speed to ship | Runtime cost | Correctness risk | Code cleanliness |
  |---|---|---|---|---|
  | `Channel.UNLIMITED` | One line | Memory grows if the disk is very slow (assumption: a few MiB in practice) | No dropped chunks | Same as now |
  | Suspending `send` instead of `trySend` | One line | None | Blocks the shared `_frames` collector, which can then drop frames for other commands (`GpsocketClient.kt:106`, `extraBufferCapacity = 256`) | Same as now |

  Recommendation: `Channel.UNLIMITED`. The camera sends a fixed-size file,
  so memory use has an upper limit.
- **Decision:** Fix now — went with `Channel.UNLIMITED` as recommended; suspending `send` would stall the shared `_frames` collector.

## 5. Race at the idle shutdown of the download service

- **Where:** `DownloadService.kt:126-142`
- **Problem:** after 10 seconds with an empty queue the worker leaves the loop,
  sets `worker = false`, then calls `stopSelf()` with no start id.
- **What breaks:**
  - A clip queued after the timeout but before `worker = false`:
    `ensureWorker` sees a running worker and does nothing. The service stops
    and the clip is never downloaded.
  - A clip queued after `worker = false` but before `stopSelf()`: a new
    worker starts, then the old `stopSelf()` destroys the service and cancels
    the new download.
- **Proposed fix:** do the shutdown on the main thread. Check the queue again
  first, and call `stopSelf(lastStartId)` so a newer start request keeps the
  service alive.
- **Decision:** Fix now — shutdown moved to the main dispatcher; queue is re-checked and `stopSelfResult(lastStartId)` keeps the service alive if a newer start arrived.

## 6. Queued clips can be queued again and download twice

- **Where:** `FilesScreen.kt:212-214`, `app/src/main/java/com/juanm/easydashcam/ui/camera/CameraViewModel.kt:272-275`
- **Problem:** queuing a clip only sends an intent. `session.downloads` gets
  an entry only when the first progress update arrives.
- **What breaks:** after "Download all" on 50 clips, 49 still show the
  Download button and still count in "Download all". Tapping again queues
  them twice, and MediaStore gets duplicate files.
- **Proposed fix:** set a "queued" state in `session.downloads` when the clip is
  queued, and skip ids already in the queue in `onStartCommand`.
- **Decision:** Fix now — `vm.download()` marks the entry queued instantly (row shows progress, 'Download all' count drops) and `DownloadService` rejects duplicate in-flight ids.

## 7. MediaStore scan on every progress tick, and failed marks get erased

- **Where:** `CameraViewModel.kt:136` and `:206-208`
- **Problem:** the collector calls `refreshLocalFiles()` on every emission
  of `downloads` when any finished download exists.
- **What breaks:**
  - After the first clip finishes, each progress update (about 2 per second)
    starts a new MediaStore query.
  - The reconcile step removes every `done` entry that is not on disk. Failed
    downloads are also `done`, so their error mark disappears.
- **Proposed fix:** refresh only when the set of successful downloads changes
  (`map { ... }.distinctUntilChanged()`), and in the reconcile step only
  remove entries with `error == null`.
- **Decision:** Fix now — collector now emits only when the set of successful ids changes (`distinctUntilChanged`), and reconcile removes only error-free done entries.

## 8. Photos saved as Video entries, then changed to JPEG

- **Where:** `app/src/main/java/com/juanm/easydashcam/core/download/ClipStore.kt:62-73`
- **Problem:** `open()` inserts into the MediaStore Video collection.
  `commit()` then changes the type to `image/jpeg` and the folder to Pictures.
- **What breaks (not tested):** MediaStore may reject this update. The
  exception would crash the app because of finding 1. If the update works,
  `refreshLocalFiles` (Video collection only) never finds the photo, so it
  shows as not downloaded and gets downloaded again.
- **To check first:** download one photo on a device and see if it shows in
  the gallery and in the app as downloaded.
- **Decision:** Fix now, verify on device — the code gap was real: `refreshLocalFiles` queried only `MediaStore.Video`, so a JPEG-committed photo could never map back. Now queries Images too. Device check of the MediaStore update path still open.

## 9. Auto-download start time is never reset

- **Where:** `app/src/main/java/com/juanm/easydashcam/core/session/CameraSession.kt:66-67`
- **Problem:** `auto_ts` is set only when it does not exist yet.
- **What breaks:** the user turns auto-download on, off, then on again two
  weeks later. The old start time is used, so every clip from the time it
  was off gets downloaded (10 per refresh). The comment at
  `CameraViewModel.kt:323` says old clips should be left alone.
- **Proposed fix:** set the start time to now every time the option goes
  from off to on.
- **Question:** is the current behavior wanted ("catch up on what I missed")?
- **Decision:** Fix now — watermark re-bases on every off→on transition; 'catch up on missed clips' was not the documented intent (comment says old clips are left alone).

## 10. RTSP quantization tables skipped on the wrong packets

- **Where:** `app/src/main/java/com/juanm/easydashcam/core/net/RtspMjpegClient.kt:240`
- **Problem:** the code skips a table header on every packet when `q >= 100`.
  The RTSP JPEG standard (RFC 2435) sends tables only when `q >= 128`, and
  only in the first fragment of a frame (`fragOff == 0`).
- **What breaks:** nothing today if the camera never sends `q >= 100`. If it
  does, later fragments lose bytes and frames are corrupt.
- **Proposed fix:** change the condition to `q >= 128 && fragOff == 0`.
- **To check first:** the `q` value in a live stream capture.
- **Decision:** Fix now — `q >= 128 && fragOff == 0` per RFC 2435; camera sends q<100 today so behavior is unchanged, but the old condition would have corrupted frames if it ever sent dynamic tables.

## Sources

- `git diff --stat origin/main HEAD`, `git log`
- Files read: `ClipStore.kt:1-84`, `DownloadService.kt:1-267`,
  `CameraSession.kt:60-80`, `CameraViewModel.kt:125-145, 195-215, 272-275, 320-345`,
  `GpsocketClient.kt:106, 455-505`, `FilesScreen.kt:212-214, 513, 908-922`,
  `RtspMjpegClient.kt:232-252`
