# Crash course: how Easy Dashcam works

A beginner's tour of everything this project is built from, using the exact files in this repo — written for someone coming from web/backend work, so it explains the Android and binary-protocol parts in familiar terms. Read top to bottom; each section builds on the previous one.

---

## 1. The whole machine in one map

```
Your taps                     The app                              The camera
─────────                     ────────                             ──────────
   │                                                               
   ▼                                                               
┌─────────┐   state (StateFlow)  ┌──────────────┐   TCP socket   ┌──────────┐
│ Compose │ ◄─────────────────── │ CameraView-  │ ◄────────────► │ dashcam  │
│   UI    │   render on change   │   Model      │  bytes over    │          │
└────┬────┘                      └──────┬───────┘  Wi-Fi         └──────────┘
     │ button tap: onConnect()          │ sendLogin(), sendParamQuery(), ...
     ▼                                  ▼
   MainActivity.kt                GpsocketClient.kt
                                  (+ CameraNetworkBinder picks the right network)
```

The dev loop (PC side) is three tools:

```
edit code in editor ──► gradle assembleDebug ──► adb install ──► phone runs app
      Write/Edit          build_apk pipeline    install_apk      logcat for logs
```

Everything is closed-loop local: no servers, no accounts, nothing leaves the phone.

---

## 2. Threads, the main-thread rule, and the bug we just fixed

### Concepts

- **Thread** = one stream of instructions the CPU executes. A phone runs many at once.
- **Main (UI) thread** = the one thread allowed to touch the screen. It redraws every ~16 ms (60×/second). If it pauses, the whole app freezes.
- **Blocking call** = a function that waits (e.g. `Socket.connect()` pauses until the camera answers — can take seconds).
- Android's rule, enforced at runtime: **no network operations on the main thread.** Violate it and the app dies instantly with `NetworkOnMainThreadException`.

### Our actual bug (commit `542fc78`)

```kotlin
// what connect() did initially (simplified):
suspend fun connect() {
    s.connect(InetSocketAddress(host, port), 5000)   // blocking, ran on CALLER's thread
}
```

`CameraViewModel.connect()` launched this from `viewModelScope`, whose default dispatcher is **Main**. So the blocking socket connect executed on the UI thread → instant exception, before a single byte was sent. The app's initial "Not connected" text wasn't part of the bug — it was just the normal start state.

### The fix — coroutine concepts in one box

- **Coroutine** = a lightweight task; think "function that can pause and resume".
- **`suspend fun`** = a function allowed to pause without blocking its thread.
- **Dispatcher** = which pool of threads a coroutine runs on:
  - `Dispatchers.Main` = the UI thread (never block here)
  - `Dispatchers.IO` = background pool sized for network/disk work
- **`withContext(Dispatchers.IO) { ... }`** = "pause this coroutine here, run this block on the IO pool, then come back with the result".

```kotlin
// fixed: anything that can block lives inside withContext(IO)
withContext(Dispatchers.IO) {
    s.connect(InetSocketAddress(host, port), 5000)
    runInitSequence()
}
```

Same trick applies throughout: the read loop and heartbeat run on the client's own `Dispatchers.IO` scope; the UI never sleeps.

---

## 3. The GPSOCKET protocol (our language with the camera)

Camera is `192.168.100.1`. Two services, both plaintext TCP:

| Port | Protocol | Web-world equivalent |
|---|---|---|
| 8081 | GPSOCKET (custom binary) | a persistent connection speaking a private request/response protocol — think WebSocket, but raw bytes instead of JSON |
| 8080 | RTSP | live video stream, an industry standard; we run it ourselves in `RtspMjpegClient` (§3.7) — ExoPlayer rejects this stream |

There is no HTTP, no TLS, and no JSON anywhere — the firmware speaks one binary format, described below. Everything we know came from **pcaps** (packet captures of the Viidure app talking to the camera — the equivalent of proxying an app's HTTP traffic and reading it).

### 3.1 Frames: the unit of conversation

Every message in both directions is a **frame**:

```
| "GPSOCKET" (8-byte magic) | type u16 | cmd u16 | body — shape depends on cmd |
        sync marker          direction      the verb
```

For a web dev: `magic` is message-boundary marker + protocol identifier in one, `cmd` is the route, `type` says which side is talking (`1` phone→cam, `2` cam→phone, `3` cam→phone error reply), and the body is the payload.

- **u16 little-endian**: multi-byte integers travel low-byte-first. `0x9002` goes on the wire as `02 90`. (Most network protocols use big-endian; this firmware chose the opposite — the single most common source of protocol bugs.)
- **Outgoing bodies** come in three shapes seen on the wire: bare header (keepalive `0x0100`), header + inline args (login token, param id, set-param `param|00 00|01|value`), and header + u16 length + payload (time-sync, 17 bytes: `"GPVENDOR"` + datetime).
- **Incoming responses** are almost always `u16 length + payload`. A `00 00` length = empty ACK — the protocol's "204 No Content".
- **Type-3 replies** are the exception: a fixed 14-byte frame with a 2-byte arg and *no* length field. `f8ff`/`feff` are the firmware's 4xx codes. (Parse them as length-prefixed and `fe ff` declares a 65,534-byte payload — wedging the stream. That bug is why `drainFrames` special-cases `type == 3`.)

### 3.2 Command table

| cmd | role | shape (TX → RX) |
|---|---|---|
| `0x0500` | login | u32 random token → 6-byte session key |
| `0x0700` | auth round 2 | u32 challenge → 4 bytes |
| `0x00ff` | time sync | `"GPVENDOR"\0\0` + date/time |
| `0x0100` | keepalive + status | bare → 27-byte status blob, ~1/s — the app's ping/heartbeat |
| `0x0000` | mode gate | u8 mode: `02`=file ops, `03`=settings — features are silently ignored outside their mode, like hitting an endpoint without the right scope |
| `0x0001`/`0x0400`/`0x0800` | session steps | part of the mode-change dance |
| `0x0004` | get param | u32 param id → u8 value or string. Replies carry **no id** — they're positional, so queries go out one at a time and zip to answers in order |
| `0x0104` | set param | `param u16 \| 00 00 \| 0x01 \| value u8` → empty ACK |
| `0x0200` | settings menu XML | bare → ~498 B chunks + empty terminator — literally chunked transfer with a zero-length final chunk, like HTTP `Transfer-Encoding: chunked` |
| `0x0203` | file count | bare → u16 total |
| `0x0303` | file list page | `00` start / `00`+last-id resume → `0x10` + 13-byte records — cursor pagination where the cursor is the previous page's last record id |
| `0x0003` | select file | u16 record id → ACK |
| `0x0603` | media class | record kind byte → ACK |
| `0x0503` | download | u16 record id → 32 KiB data chunks + `len=0` terminator (§3.4) |
| `0x0403` | per-record probe | u16 id → `f8ff`; appears while Viidure browses the list — probably "does a thumbnail exist?" |

Full decode with byte dumps: `../../docs/recon/pcap_11oct_analysis.txt`; the settings param map: `../../docs/recon/camera_menu.xml`.

### 3.3 File records

Each `0x0303` page is a header byte (`0x10`) then up to 16 records of 13 bytes:

```
| kind u8 | id u16 | yy mm dd hh mm ss (6 B) | size u32 |
```

- **kind** is an ASCII letter: `0x4c` = `'L'` locked video, `0x41` = `'A'` normal video, `0x4a` = `'J'` photo.
- **size** is KiB — verified, not guessed: a record saying `3776` streamed exactly `3776 × 1024` bytes.
- The timestamp is the camera's clock (ours thinks it's 2026).

### 3.4 File download — verified on the wire

```
TX 0x0400             → ACK                enter download mode
TX 0x0003 + id        → ACK                select file
TX 0x0603 + kind      → ACK                media class (echo the record's kind byte)
TX 0x0503 + id        → 32 KiB chunk       stream starts immediately
                        ... more chunks
                        len=0 frame        EOF — chunked encoding's final chunk
```

Facts that matter when implementing:

- **No per-chunk acks.** TCP does the flow control; the camera pours bytes until EOF.
- **The selection expires.** In the pcap, a 24 s gap between `0x0003` and `0x0503` got `feff` (refused). Run the four steps back-to-back — treat the selection like a short-lived token. This is the likely reason Viidure's downloads sometimes fail for no visible reason.
- **Payload is RIFF AVI** (Motion JPEG). First bytes are literally `RIFF....AVI`; photos arrive as a single `AVI1`-marked JPEG frame.
- **The phone goes quiet during transfer** in Viidure's captures. We keep our keepalive running — verified harmless on this firmware.
- **Restore file mode first.** The sequence only works in mode `0x02`; after visiting Live or Settings the same bytes get `feff`'d. `downloadFile()` re-enters the mode and paces the steps ~800 ms apart (Viidure waits ~1–3 s; a ~150 ms burst races the camera).
- **`feff` on a recent record = still writing.** The camera refuses the file it is currently recording; older records stream fine.
- **Throughput** is ~500 KiB/s — the camera's Wi-Fi is the bottleneck; nothing we send makes it faster.

### 3.5 The traps that cost us real debugging time

1. **Bursts = silence.** Twelve param queries sent back-to-back got *zero* replies; the same queries sent one-at-a-time, awaiting each reply, all answered. The camera drops frames it can't keep up with — it's a tiny TCP stack, not a server.
2. **Modes gate everything.** Params and file ops sent in idle mode are ignored, not errored. Silent drops are the firmware's favorite failure mode.
3. **Positional replies.** `0x0004` replies carry no request id. Collectors must subscribe *before* sending, and exchanges are serialized under `requestLock` — two concurrent queries would zip values onto the wrong ids.
4. **Not every frame has a length field.** Type-3 replies (§3.1) break naive framing.
5. **A second identical request is not guaranteed to work.** The settings menu may only stream once per session; we cache it at init (`lastMenuXml`).

### 3.6 What the client does with all this

`GpsocketClient` encodes frames (`writeBare`/`writeArgs`/`writePayload`), replays the observed session-init (login → param gets → menu XML → time-sync → auth → session flags), maintains the `0x0100` keepalive, and **parses the camera's stream** by scanning for the magic, reading the declared length, and emitting `Frame(type, cmd, payload)` events. Request/response calls (`getParams`, `setParam`, `requestMenuXml`, `listFiles`, `downloadFile`) subscribe to replies *before* sending, under `requestLock`, so positional replies can't interleave or be missed.

### 3.7 The RTSP side (live preview)

`rtsp://192.168.100.1:8080/?action=stream` carries MJPEG video (RTP payload type 26) + L16 audio over UDP. Three firmware quirks shape `RtspMjpegClient`:

1. **ExoPlayer can't play it** — media3's RTSP module has no decoder for static payload type 26 (`Unsupported static paylod type 26`). We do the handshake over a plain socket and reassemble JPEG frames from RTP fragments (RFC 2435), one `BitmapFactory.decodeByteArray` per frame.
2. **The handshake must look like Viidure's FFmpeg client**: `CSeq` restarts at 1 per connection, `User-Agent: Lavf58.12.100`, and a SETUP for *every* SDP track (video `track0` **and** audio `track1`) before PLAY. A track0-only SETUP gets `404` on this firmware.
3. **One session, no teardown.** The camera answers `TEARDOWN` with `501` and holds roughly one session. Leaving Live also drops GPSOCKET to mode `0x00` (`exitPreviewMode`) — that's what actually frees the session for the next `SETUP`.

---

## 4. StateFlow → Compose: how bytes become screen pixels

1. Camera sends bytes → read loop parses a frame → `_frames.emit(Frame(...))`
2. `CameraViewModel` collects (`client.frames.collect { ... }`), formats a debug line, and appends to `MutableStateFlow<List<String>>`
3. `MainActivity` observes with `collectAsState()`; when the flow's value changes, Compose **recomposes** — re-runs only the composables that changed, showing the new log line.

Key idea: **UI is a pure function of state.** We never "set text" on a widget; we update state and Compose re-renders.

---

## 5. The Wi-Fi trick: no-internet hotspot problem

When the phone joins the camera AP, Android sees "Wi-Fi without internet" and may route app traffic over cellular — killing our socket. `CameraNetworkBinder.bindToWifi()` calls `ConnectivityManager.bindProcessToNetwork(wifiNetwork)`, pinning this app's sockets to the Wi-Fi path regardless. Cellular still serves other apps, so the phone keeps internet while we talk to the camera.

---

## 6. The build & deploy pipeline (what `deploy_app.py` automates)

| Step | Tool | What it does |
|---|---|---|
| Compile | Gradle 8.9 (`assembleDebug`) | Kotlin → bytecode/resources → signed **`app-debug.apk`** |
| Locate SDK | `local.properties` | points Gradle at the Android SDK |
| Talk to phone | `adb` (`platform-tools`) | `install -r` the APK, `monkey -p ...` to launch, `logcat` for logs |
| Deploy | `tools/deploy_app.py` | chains all of the above; `--logcat` tails live logs |

Java for the build comes from Android Studio's bundled **JBR 21** (`JAVA_HOME`), not the system JDK.

---

## 7. Repo map (where each concept lives)

- `app/src/main/.../MainActivity.kt` — Compose UI (state display + debug frame log)
- `.../ui/camera/CameraViewModel.kt` — MVVM glue: StateFlows, collects frames, drives connect()
- `.../core/net/GpsocketClient.kt` — the protocol engine (§3, §2's fix lives here)
- `.../core/net/CameraNetworkBinder.kt` — Wi-Fi binding (§5)
- `app/src/main/AndroidManifest.xml` — declared permissions (internet, network state, foreground download service prep)
- `build.gradle.kts` (*app*) — dependencies: Compose, Material 3, coroutines (RTSP/AVI playback are hand-rolled — see §3.7)
- `app/src/test/.../AviMjpegPlayerTest.kt` — JVM unit tests for the AVI indexer
- `tools/` — deploy/diagnostic scripts used by the dev loop (§6)

---

## 8. Glossary

- **ACK** — a reply with an empty body meaning "received and accepted" (≈ HTTP 204)
- **APK** — the installable Android app file (the compiler's output)
- **Chunked transfer** — sending a body as a series of sized pieces ending with a zero-length piece; HTTP does it (`Transfer-Encoding: chunked`), GPSOCKET does it for menu XML and file downloads
- **Cursor pagination** — fetching a list in pages where each request passes the id of the last item seen, instead of a page number (the file list works this way)
- **Dispatcher** — thread pool a coroutine runs on (Main vs IO)
- **Frame (GPSOCKET)** — one command packet: magic + type + command id + optional payload
- **Hotspot / AP** — Wi-Fi created *by the camera*; your phone joins as a client
- **Little-endian** — multi-byte numbers sent least-significant-byte first (`0x9002` → bytes `02 90`); the opposite of normal network byte order
- **MJPEG over RTSP** — the live-video stream format; a sequence of JPEG photos; plays in VLC, but not ExoPlayer (payload type 26 unsupported) — `RtspMjpegClient` handles it
- **MVVM** — UI (View) ↔ ViewModel (state/logic) ↔ Model (data/network); UI never opens sockets
- **pcap** — packet-capture file; how we reverse-engineered the protocol (Wireshark/PCAPdroid)
- **RIFF / AVI** — Microsoft's media container format; recordings download as Motion-JPEG AVI files (`RIFF` magic on the first bytes)
- **RTSP** — Real Time Streaming Protocol; the standard way to ask a camera for live video
- **StateFlow** — an observable value; UI re-renders whenever it changes
- **suspend** — a function that may pause mid-flight without freezing its thread
- **u8 / u16 / u32** — unsigned integers of 1 / 2 / 4 bytes
