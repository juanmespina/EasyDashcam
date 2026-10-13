package com.juanm.easydashcam.ui.camera

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.juanm.easydashcam.EasyDashcamApp
import com.juanm.easydashcam.core.download.ClipStore
import com.juanm.easydashcam.core.download.DownloadService
import com.juanm.easydashcam.core.net.GpsocketClient
import com.juanm.easydashcam.core.session.CameraSession
import com.juanm.easydashcam.core.settings.MenuParser
import com.juanm.easydashcam.core.settings.SettingCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val session = (app as EasyDashcamApp).session
    private val client = session.client

    private val _state = MutableStateFlow<GpsocketClient.State>(GpsocketClient.State.Disconnected)
    val state: StateFlow<GpsocketClient.State> = _state

    /** Download progress per record id, fed by DownloadService via the session. */
    val downloads = session.downloads

    /** True while any transfer is in flight — downloads emits per chunk, so
     *  this is distinctUntilChanged to avoid recomposition churn. */
    val transferActive: StateFlow<Boolean> = session.downloads
        .map { m -> m.values.any { !it.done } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    /** Address of the connected camera — RTSP targets the same host. */
    val cameraHost = session.cameraHost

    /** Auto-download-new-clips preference (persisted). */
    val autoDownload = session.autoDownload
    fun toggleAutoDownload() = session.setAutoDownload(!session.autoDownload.value)

    /** uniqueId → MediaStore uri for files already on the phone. */
    private val _localFiles = MutableStateFlow<Map<Int, Uri>>(emptyMap())
    val localFiles: StateFlow<Map<Int, Uri>> = _localFiles
    /** uniqueIds whose local file is a truncated partial-stream save. */
    private val _partialLocal = MutableStateFlow<Set<Int>>(emptySet())
    val partialLocal: StateFlow<Set<Int>> = _partialLocal

    /** null = not loaded yet; empty = connected but camera reported none. */
    private val _files = MutableStateFlow<List<GpsocketClient.CameraFile>?>(null)
    val files: StateFlow<List<GpsocketClient.CameraFile>?> = _files

    private val _filesLoading = MutableStateFlow(false)
    val filesLoading: StateFlow<Boolean> = _filesLoading

    private val _settings = MutableStateFlow<List<SettingCategory>?>(null)
    val settings: StateFlow<List<SettingCategory>?> = _settings

    /** Current value per setting id (from the last getParams round). */
    private val _settingValues = MutableStateFlow<Map<Int, ByteArray>>(emptyMap())
    val settingValues: StateFlow<Map<Int, ByteArray>> = _settingValues

    private val _settingsLoading = MutableStateFlow(false)
    val settingsLoading: StateFlow<Boolean> = _settingsLoading

    // Tab-entry refresh TTL — the socket is a single shared channel and a
    // full fetch is ~50 round-trips, so re-fetching on every tab switch made
    // navigation feel slow. Pull-to-refresh passes force=true.
    private var filesFetchedAt = 0L
    private var settingsLoadedAt = 0L

    // The last file list also lives on disk: a cold start (or a reconnect
    // while a download holds the socket) shows the gallery instantly and
    // refreshes in the background instead of blocking on ~50 round-trips.
    private val filesCache = java.io.File(app.filesDir, "files_cache.txt")

    /** One-shot user messages (errors, applied settings). */
    private val _notices = MutableStateFlow<List<String>>(emptyList())
    val notices: StateFlow<List<String>> = _notices

    // Debug: rolling log of incoming camera frames, to map the protocol live.
    private val _frameLog = MutableStateFlow<List<String>>(emptyList())
    val frameLog: StateFlow<List<String>> = _frameLog

    init {
        viewModelScope.launch {
            client.state.collect { s ->
                val wasConnected = _state.value is GpsocketClient.State.Connected
                _state.value = s
                // A fresh session may see a different card — drop caches.
                if (s is GpsocketClient.State.Connected && !wasConnected) {
                    filesFetchedAt = 0
                    settingsLoadedAt = 0
                    _settings.value = null
                    session.clearDownloads()
                    restorePersistedFiles()   // show last-known list while refetching
                }
            }
        }
        viewModelScope.launch {
            // ~1 Hz heartbeat/status frames flood the log — record them only
            // when the payload changes, in full. That diff is what the status
            // blob (recording state, storage %, GPS if any) decodes from.
            // 0x0503 download chunks are bulk data (each payload differs, so
            // the dedupe below would log all ~1900 of them per clip).
            val silentCmds = setOf(0x0503)
            val quietCmds = setOf(0x0100, 0x0303, 0x0304)
            val lastSeen = HashMap<Int, ByteArray>()
            client.frames.collect { f ->
                if (f.cmd in silentCmds) return@collect
                if (f.cmd in quietCmds) {
                    if (lastSeen[f.cmd].contentEquals(f.payload)) return@collect
                    lastSeen[f.cmd] = f.payload
                    val hex = f.payload.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                    _frameLog.update {
                        (it + "cmd=0x%04x len=${f.payload.size}  $hex".format(f.cmd)).takeLast(60)
                    }
                    return@collect
                }
                val head = f.payload.take(32).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                val line = "type=${f.type} cmd=0x%04x len=${f.payload.size}  $head".format(f.cmd)
                _frameLog.update { (it + line).takeLast(60) }
            }
        }
        viewModelScope.launch {
            downloads.collect { m ->
                if (m.values.any { it.done && it.error == null }) refreshLocalFiles()
            }
        }
        restorePersistedFiles()
    }

    /** Load the disk-cached file list — used on cold start and on reconnect
     *  so the gallery has content while the fresh fetch is in flight. */
    private fun restorePersistedFiles() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                filesCache.readLines().mapNotNull { line ->
                    val p = line.split('|')
                    if (p.size < 4) return@mapNotNull null
                    GpsocketClient.CameraFile(
                        kind = p[0].toInt(), id = p[1].toInt(),
                        timestamp = p[2].takeIf { it.isNotEmpty() }
                            ?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() },
                        size = p[3].toLong()
                    )
                }
            }.onSuccess { if (it.isNotEmpty() && _files.value == null) _files.value = it }
        }
    }

    /** Re-scan Movies/EasyDashcam for clips we already downloaded. */
    fun refreshLocalFiles() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // New names are <stamp>_<id4><kind>.<ext>; legacy cam_<id4><kind>.
            // The suffix anchored at the end matches both.
            val nameRx = Regex("""([0-9a-fA-F]{4})([A-Z])\.(avi|jpg)$""")
            val out = mutableMapOf<Int, Uri>()
            val partials = mutableSetOf<Int>()
            if (Build.VERSION.SDK_INT < 29) {
                // Pre-29 downloads live in app-private storage — MediaStore
                // can't see them, so scan the directory directly.
                val dir = getApplication<Application>()
                    .getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
                dir?.listFiles()?.forEach { f ->
                    val m = nameRx.find(f.name) ?: return@forEach
                    val uid = (m.groupValues[1].toInt(16) shl 8) or m.groupValues[2][0].code
                    out[uid] = Uri.fromFile(f)
                    if ("_partial_" in f.name) partials += uid
                }
            } else {
                val collection = MediaStore.Video.Media
                    .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                getApplication<Application>().contentResolver.query(
                    collection,
                    arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME),
                    "(${MediaStore.Video.Media.RELATIVE_PATH} LIKE 'Movies/EasyDashcam/%'" +
                        " OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE 'Pictures/EasyDashcam/%')",
                    null, null
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val name = c.getString(nameCol)
                        val m = nameRx.find(name) ?: continue
                        val uid = (m.groupValues[1].toInt(16) shl 8) or m.groupValues[2][0].code
                        out[uid] = ContentUris.withAppendedId(collection, c.getLong(idCol))
                        if ("_partial_" in name) partials += uid
                    }
                }
            }
            _localFiles.value = out
            _partialLocal.value = partials
            // Reconcile: a "done" entry whose MediaStore file was deleted
            // should become downloadable again, not marked downloaded forever.
            session.downloads.value.forEach { (uid, d) ->
                if (d.done && uid !in out) session.removeDownload(uid)
            }
        }
    }

    fun connect() {
        _frameLog.value = emptyList()
        viewModelScope.launch {
            if (!session.connect()) {
                _state.value = GpsocketClient.State.Error(
                    IllegalStateException("No Wi-Fi network — join the camera hotspot first.")
                )
            }
        }
    }

    fun disconnect() = session.disconnect()

    /** Arm the camera's RTSP push before the player prepares. */
    suspend fun enterPreviewMode() = client.enterPreviewMode()

    /** Drop preview mode when leaving Live — releases the camera's RTSP
     *  session (it never honors TEARDOWN). Fire-and-forget: runs on the
     *  viewModel scope since composable disposal cannot suspend. */
    fun exitPreviewMode() {
        viewModelScope.launch { runCatching { client.exitPreviewMode() } }
    }

    // ---- Files --------------------------------------------------------------

    fun refreshFiles(force: Boolean = false) {
        if (_filesLoading.value) return
        if (!force && _files.value != null &&
            System.currentTimeMillis() - filesFetchedAt < 30_000) return
        viewModelScope.launch {
            _filesLoading.value = true
            runCatching { client.listFiles() }
                .onSuccess {
                    val sorted = it.sortedByDescending { f -> f.timestamp }
                    _files.value = sorted
                    filesFetchedAt = System.currentTimeMillis()
                    persistFiles(sorted)
                    if (session.autoDownload.value) autoEnqueueNew(it)
                }
                .onFailure { notice("file list failed: ${it.message}") }
            _filesLoading.value = false
        }
    }

    /** Remove a downloaded clip/photo from MediaStore and refresh the marks. */
    fun deleteLocal(uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val rows = runCatching {
                getApplication<Application>().contentResolver.delete(uri, null, null)
            }.getOrDefault(0)
            notice(if (rows > 0) "Deleted from device" else "Delete failed")
            refreshLocalFiles()
        }
    }

    fun download(f: GpsocketClient.CameraFile) {
        ContextCompat.startForegroundService(
            getApplication(), DownloadService.downloadIntent(getApplication(), f))
    }

    /**
     * Pull a recording into [out] while it plays — progressive playback.
     * Same wire path as a download, so it serializes behind any in-flight
     * transfer on the socket's requestLock. No session.downloads updates:
     * a preview isn't a download and must not churn the gallery.
     */
    suspend fun streamFile(
        f: GpsocketClient.CameraFile,
        out: java.io.OutputStream,
        onProgress: (Long, Long) -> Unit
    ): GpsocketClient.DownloadResult =
        client.downloadFile(f, out, onProgress = onProgress)

    /**
     * Adopt a streamed temp file into the gallery — the bytes are
     * already local, so saving is a copy, not a re-download. A partial
     * stream saves a truncated (still playable) AVI under a marked name.
     */
    suspend fun saveStreamed(
        f: GpsocketClient.CameraFile, src: java.io.File, partial: Boolean = false
    ): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val dest = ClipStore.open(getApplication(), f, partial)
                    ?: return@runCatching false
                val head = ByteArray(4)
                java.io.FileInputStream(src).use { it.read(head, 0, 4) }
                val isJpeg = head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
                dest.out.use { o -> src.inputStream().use { it.copyTo(o) } }
                ClipStore.commit(getApplication(), dest, isJpeg)
                session.updateDownload(f.uniqueId,
                    CameraSession.DownloadState(src.length(), src.length(), done = true))
                refreshLocalFiles()
                true
            }.getOrDefault(false)
        }

    private fun persistFiles(files: List<GpsocketClient.CameraFile>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                filesCache.writeText(files.joinToString("\n") { f ->
                    "${f.kind}|${f.id}|${f.timestamp ?: ""}|${f.size}"
                })
            }
        }
    }

    /** Enqueue records newer than the sync watermark — clips that existed
     *  before auto-download was enabled are intentionally left alone. */
    private fun autoEnqueueNew(files: List<GpsocketClient.CameraFile>) {
        val wm = session.autoSyncWatermark ?: return
        val local = _localFiles.value
        val dl = session.downloads.value
        val fresh = files.filter {
            it.kindChar != 'L' && it.timestamp != null && it.timestamp.isAfter(wm) &&
                local[it.uniqueId] == null && dl[it.uniqueId] == null
        }.sortedBy { it.timestamp }.take(10)
        if (fresh.isEmpty()) return
        fresh.forEach(::download)
        session.autoSyncWatermark = fresh.last().timestamp
        notice("Auto-downloading ${fresh.size} new clip(s)")
    }

    // ---- Settings -----------------------------------------------------------

    fun loadSettings(force: Boolean = false) {
        if (_settingsLoading.value) return
        if (!force && _settings.value != null &&
            System.currentTimeMillis() - settingsLoadedAt < 30_000) return
        viewModelScope.launch {
            _settingsLoading.value = true
            runCatching {
                val xml = client.lastMenuXml ?: client.requestMenuXml()
                val cats = MenuParser.parse(xml)
                // Query everything but actions (type 0x01) — read-only rows
                // like Version need their value too.
                val ids = cats.flatMap { c -> c.settings.filter { it.type != 0x01 }.map { it.id } }
                cats to client.getParams(ids)
            }.onSuccess { (cats, values) ->
                _settings.value = cats
                _settingValues.value = values
                settingsLoadedAt = System.currentTimeMillis()
            }.onFailure { notice("settings failed: ${it.message}") }
            _settingsLoading.value = false
        }
    }

    fun setSetting(id: Int, value: Int) {
        val prev = _settingValues.value[id]
        _settingValues.update { it + (id to byteArrayOf(value.toByte())) }
        viewModelScope.launch {
            if (!client.setParam(id, value)) {
                notice("setting 0x%04x rejected".format(id))
                if (prev != null) _settingValues.update { it + (id to prev) }
            }
        }
    }

    // ---- Debug actions -------------------------------------------------------

    /** Live-probing API: send a raw GPSOCKET command live. */
    fun probe(cmd: Int, args: ByteArray = byteArrayOf()) {
        val argHex = args.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        _frameLog.update { (it + "→ TX cmd=0x%04x $argHex".format(cmd)).takeLast(60) }
        viewModelScope.launch { client.sendRaw(cmd, args) }
    }

    fun fetchMenu() {
        viewModelScope.launch {
            runCatching { client.requestMenuXml() }
                .onSuccess { log("menu XML: ${it.length} chars") }
                .onFailure {
                    val cached = client.lastMenuXml
                    log("menu re-request failed: ${it.message}" +
                        (cached?.let { c -> " (init cache: ${c.length} chars)" } ?: ""))
                }
        }
    }

    fun fetchFiles() {
        viewModelScope.launch {
            runCatching {
                val count = client.requestFileCount()
                log("file count: ${count ?: "?"}")
                client.listFiles()
            }.onSuccess { files ->
                log("${files.size} records:")
                files.take(8).forEach { log("  $it") }
                if (files.size > 8) log("  …")
            }.onFailure { log("file list failed: ${it.message}") }
        }
    }

    fun fetchSettings() {
        viewModelScope.launch {
            runCatching { client.getParams(SETTING_PARAM_IDS) }
                .onSuccess { values ->
                    SETTING_PARAM_IDS.forEach { id ->
                        val v = values[id]
                        val disp = when {
                            v == null || v.isEmpty() -> "no reply"
                            v.size == 1 -> "0x%02x".format(v[0])
                            else -> v.toString(Charsets.UTF_8).trimEnd('\u0000')
                        }
                        log("param 0x%04x = %s".format(id, disp))
                    }
                }
                .onFailure { log("get params failed: ${it.message}") }
        }
    }

    /** Debug: download the smallest recording into app storage. */
    fun downloadFirst() {
        viewModelScope.launch {
            runCatching {
                val files = client.listFiles()
                val target = files.minByOrNull { it.size }
                    ?: return@runCatching log("no files on camera")
                val dest = java.io.File(
                    getApplication<Application>().filesDir,
                    "cam_%04x.avi".format(target.id)
                )
                log("downloading $target -> ${dest.name}")
                var lastLogged = -1L
                val result = dest.outputStream().use { out ->
                    client.downloadFile(target, out) { got, total ->
                        val mb = got / (1024 * 1024)
                        if (mb != lastLogged) { lastLogged = mb; log("  ${got / 1024}/${total / 1024} KiB") }
                    }
                }
                when (result) {
                    is GpsocketClient.DownloadResult.Success ->
                        log("done: ${result.bytes} B -> ${dest.absolutePath}")
                    is GpsocketClient.DownloadResult.Refused ->
                        log("camera refused (feff) — stale id or selection expired")
                    is GpsocketClient.DownloadResult.Incomplete ->
                        log("stalled at ${result.bytes}/${result.expected} B")
                    is GpsocketClient.DownloadResult.Failed ->
                        log("failed: ${result.reason}")
                }
            }.onFailure { log("download failed: ${it.message}") }
        }
    }

    private fun log(line: String) = _frameLog.update { (it + "» $line").takeLast(60) }
    private fun notice(msg: String) = _notices.update { (it + msg).takeLast(3) }

    /** Drop a notice after the snackbar that showed it is dismissed. */
    fun consumeNotice(msg: String) = _notices.update { it - msg }

    /** User-visible one-shot message (snackbar). */
    fun postNotice(msg: String) = notice(msg)

    companion object {
        private val SETTING_PARAM_IDS = listOf(
            0x0000, 0x0003, 0x0004, 0x0005,
            0x0100, 0x0101, 0x0108,
            0x0200, 0x0201, 0x0205, 0x0209,
            0x0300
        )
    }

    override fun onCleared() {
        // Session lives in the Application — do not tear the socket down here.
    }
}
