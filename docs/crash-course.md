# Crash course: how Easy Dashcam works

A beginner's tour of everything this project is built from, using the exact files in this repo. Read top to bottom; each section builds on the previous one.

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

Camera is `192.168.100.1`: **TCP :8081** = command channel (GPSOCKET), **TCP :8080** = RTSP live video (added later).

Every command is a **frame** — a byte packet:

```
| "GPSOCKET" (8-byte magic) | type u16 | cmd u16 | [len u16 | payload...] |
        label to sync           1=phone→cam   the verb        optional body
```

- **u16, little-endian**: 2-byte number written low-byte-first. 0x9002 travels as `02 90`.
- Three outgoing shapes seen on the wire: bare header (keepalive `0x0001`), header + inline args (login token, param-query id), header + u16 length + payload (time-sync, 17 bytes: `"GPVENDOR"` + datetime).
- Responses from the camera always use the length form.

Our `GpsocketClient` encodes frames (`writeBare`/`writeArgs`/`writePayload`), replays the observed session-init (login → param queries → time-sync → status flags), maintains the `0x0001` keepalive, and **parses the camera's stream** by scanning for the magic, reading the declared length, and emitting `Frame(type, cmd, payload)` events.

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
- `build.gradle.kts` (*app*) — dependencies: Compose, Material 3, Media3 ExoPlayer (+ RTSP, for later), OkHttp, coroutines
- `tools/` — deploy/diagnostic scripts used by the dev loop (§6)

---

## 8. Glossary

- **APK** — the installable Android app file (the compiler's output)
- **Dispatcher** — thread pool a coroutine runs on (Main vs IO)
- **Frame (GPSOCKET)** — one command packet: magic + type + command id + optional payload
- **Hotspot / AP** — Wi-Fi created *by the camera*; your phone joins as a client
- **MJPEG over RTSP** — the live-video stream format; a sequence of JPEG photos; plays in VLC/ExoPlayer
- **MVVM** — UI (View) ↔ ViewModel (state/logic) ↔ Model (data/network); UI never opens sockets
- **pcap** — packet-capture file; how we reverse-engineered the protocol (Wireshark/PCAPdroid)
- **StateFlow** — an observable value; UI re-renders whenever it changes
**suspend** — a function that may pause mid-flight without freezing its thread
