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
import com.juanm.easydashcam.R
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

    init {
        viewModelScope.launch {
            client.state.collect { s ->
                val wasConnected = _state.value is GpsocketClient.State.Connected
                _state.value = s
                // A fresh session may see a different card — drop caches.
                // Download state stays: it lives in the process-wide session,
                // this ViewModel is new on every app reopen, and queued or
                // failed rows must survive both. refreshLocalFiles drops
                // finished entries whose file is gone.
                if (s is GpsocketClient.State.Connected && !wasConnected) {
                    filesFetchedAt = 0
                    settingsLoadedAt = 0
                    _settings.value = null
                    restorePersistedFiles() // show last-known list while refetching
                }
            }
        }
        viewModelScope.launch {
            // Emit only when the set of successful ids changes — otherwise a
            // MediaStore scan ran on every progress tick (~2/s per download).
            downloads
                .map { m -> m.filterValues { it.done && it.error == null }.keys }
                .distinctUntilChanged()
                .collect { if (it.isNotEmpty()) refreshLocalFiles() }
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
                        kind = p[0].toInt(),
                        id = p[1].toInt(),
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
            val out = mutableMapOf<Int, Uri>()
            val partials = mutableSetOf<Int>()
            if (Build.VERSION.SDK_INT < 29) {
                // Pre-29 downloads live in app-private storage — MediaStore
                // can't see them, so scan the directory directly.
                val dir = getApplication<Application>()
                    .getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
                dir?.listFiles()?.forEach { f ->
                    val uid = ClipStore.parseClipName(f.name) ?: return@forEach
                    out[uid] = Uri.fromFile(f)
                    if ("_partial_" in f.name) partials += uid
                }
            } else {
                // Clips live in the Video collection; photos got their MIME
                // flipped to image/jpeg at commit, so they land in Images.
                // Query both — the other never sees them.
                for (collection in listOf(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                )) {
                    getApplication<Application>().contentResolver.query(
                        collection,
                        arrayOf(
                            MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.DISPLAY_NAME
                        ),
                        "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'Movies/EasyDashcam/%'" +
                            " OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'Pictures/EasyDashcam/%')",
                        null,
                        null
                    )?.use { c ->
                        val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        while (c.moveToNext()) {
                            val name = c.getString(nameCol)
                            val uid = ClipStore.parseClipName(name) ?: continue
                            out[uid] = ContentUris.withAppendedId(collection, c.getLong(idCol))
                            if ("_partial_" in name) partials += uid
                        }
                    }
                }
            }
            _localFiles.value = out
            _partialLocal.value = partials
            // Reconcile: a "done" entry whose MediaStore file was deleted
            // should become downloadable again, not marked downloaded forever.
            session.downloads.value.forEach { (uid, d) ->
                // Only successful entries reconcile against disk — a failed
                // download is also done, and dropping it erases the error mark.
                if (d.done && d.error == null && uid !in out) session.removeDownload(uid)
            }
        }
    }

    fun connect() {
        viewModelScope.launch {
            if (!session.connect()) {
                _state.value = GpsocketClient.State.Error(
                    IllegalStateException(
                        getApplication<Application>().getString(R.string.err_no_wifi)
                    )
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
        if (!force &&
            _files.value != null &&
            System.currentTimeMillis() - filesFetchedAt < 30_000
        ) {
            return
        }
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
                .onFailure {
                    // A failed fetch shouldn't empty the gallery — the disk
                    // cache still lists every record the camera last served,
                    // and On device matches it against MediaStore copies.
                    if (_files.value == null) restorePersistedFiles()
                    notice(
                        getApplication<Application>()
                            .getString(R.string.notice_file_list_failed, it.message)
                    )
                }
            _filesLoading.value = false
        }
    }

    /** Remove a downloaded clip/photo from MediaStore and refresh the marks. */
    fun deleteLocal(uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Pre-29 copies are file:// URIs, which ContentResolver.delete
            // rejects ("Unknown URL") — delete the file itself.
            val rows = runCatching {
                if (uri.scheme == "file") {
                    if (java.io.File(requireNotNull(uri.path)).delete()) 1 else 0
                } else {
                    getApplication<Application>().contentResolver.delete(uri, null, null)
                }
            }.getOrDefault(0)
            notice(
                getApplication<Application>().getString(
                    if (rows > 0) R.string.notice_deleted else R.string.notice_delete_failed
                )
            )
            refreshLocalFiles()
        }
    }

    fun download(f: GpsocketClient.CameraFile) {
        // Only a failed entry may re-queue — in-flight and completed entries
        // would duplicate the MediaStore write.
        val prev = session.downloads.value[f.uniqueId]
        if (prev != null && !(prev.done && prev.error != null)) return
        // Mark queued immediately — otherwise the intent is in flight for a
        // while and the row still shows Download, inviting a duplicate queue.
        session.updateDownload(
            f.uniqueId,
            CameraSession.DownloadState(0, f.sizeBytes)
        )
        runCatching {
            ContextCompat.startForegroundService(
                getApplication(),
                DownloadService.downloadIntent(getApplication(), f)
            )
        }.onFailure {
            // Queued marker would otherwise show a spinner forever.
            session.removeDownload(f.uniqueId)
            notice(
                getApplication<Application>()
                    .getString(R.string.notice_file_list_failed, it.message)
            )
        }
    }

    fun cancelDownload(uniqueId: Int) {
        runCatching {
            getApplication<Application>()
                .startService(DownloadService.cancelIntent(getApplication(), uniqueId))
        }
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
    ): GpsocketClient.DownloadResult = client.downloadFile(f, out, onProgress = onProgress)

    /**
     * Adopt a streamed temp file into the gallery — the bytes are
     * already local, so saving is a copy, not a re-download. A partial
     * stream saves a truncated (still playable) AVI under a marked name.
     */
    suspend fun saveStreamed(f: GpsocketClient.CameraFile, src: java.io.File, partial: Boolean = false): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val dest = ClipStore.open(getApplication(), f, partial)
                    ?: return@runCatching false
                val head = ByteArray(4)
                java.io.FileInputStream(src).use { it.read(head, 0, 4) }
                val isJpeg = head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
                dest.out.use { o -> src.inputStream().use { it.copyTo(o) } }
                ClipStore.commit(getApplication(), dest, isJpeg)
                session.updateDownload(
                    f.uniqueId,
                    CameraSession.DownloadState(src.length(), src.length(), done = true)
                )
                refreshLocalFiles()
                true
            }.getOrDefault(false)
        }

    private fun persistFiles(files: List<GpsocketClient.CameraFile>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                filesCache.writeText(
                    files.joinToString("\n") { f ->
                        "${f.kind}|${f.id}|${f.timestamp ?: ""}|${f.size}"
                    }
                )
            }
        }
    }

    /** Enqueue records newer than the sync watermark — clips that existed
     *  before auto-download was enabled are intentionally left alone. */
    private fun autoEnqueueNew(files: List<GpsocketClient.CameraFile>) {
        val wm = session.autoSyncWatermark ?: return
        val known = _localFiles.value.keys + session.downloads.value.keys
        val fresh = selectAutoDownloads(files, wm, known)
        if (fresh.isEmpty()) return
        fresh.forEach(::download)
        session.autoSyncWatermark = fresh.last().timestamp
        notice(
            getApplication<Application>().resources.getQuantityString(
                R.plurals.notice_auto_download,
                fresh.size,
                fresh.size
            )
        )
    }

    // ---- Settings -----------------------------------------------------------

    fun loadSettings(force: Boolean = false) {
        if (_settingsLoading.value) return
        if (!force &&
            _settings.value != null &&
            System.currentTimeMillis() - settingsLoadedAt < 30_000
        ) {
            return
        }
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
            }.onFailure {
                notice(
                    getApplication<Application>()
                        .getString(R.string.notice_settings_failed, it.message)
                )
            }
            _settingsLoading.value = false
        }
    }

    fun setSetting(id: Int, value: Int) {
        val prev = _settingValues.value[id]
        _settingValues.update { it + (id to byteArrayOf(value.toByte())) }
        viewModelScope.launch {
            // A dead link throws from the socket write before the read loop
            // notices (up to its 15 s timeout); treat it as a refusal.
            val applied = try {
                client.setParam(id, value)
            } catch (_: java.io.IOException) {
                false
            }
            if (!applied) {
                notice(
                    getApplication<Application>()
                        .getString(R.string.notice_setting_rejected, id)
                )
                _settingValues.update { if (prev != null) it + (id to prev) else it - id }
            }
        }
    }

    private fun notice(msg: String) = _notices.update { (it + msg).takeLast(3) }

    /** Drop a notice after the snackbar that showed it is dismissed. */
    fun consumeNotice(msg: String) = _notices.update { it - msg }

    /** User-visible one-shot message (snackbar). */
    fun postNotice(msg: String) = notice(msg)

    override fun onCleared() {
        // Session lives in the Application — do not tear the socket down here.
    }
}

/**
 * Records auto-download should queue: servable, timestamped after [watermark],
 * not already on the phone or queued ([known] uniqueIds), oldest first, at
 * most [limit] — except that records sharing the last chosen timestamp all
 * go together. The watermark moves to that timestamp and the next pass only
 * takes records strictly after it, so cutting a photo+video pair in half
 * would skip its other half forever.
 */
internal fun selectAutoDownloads(
    files: List<GpsocketClient.CameraFile>,
    watermark: java.time.LocalDateTime,
    known: Set<Int>,
    limit: Int = 10
): List<GpsocketClient.CameraFile> {
    val candidates = files
        .filter { it.kindChar != 'L' && it.timestamp?.isAfter(watermark) == true && it.uniqueId !in known }
        .sortedBy { it.timestamp }
    if (candidates.size <= limit) return candidates
    val cutAt = candidates[limit - 1].timestamp
    return candidates.take(limit) + candidates.drop(limit).takeWhile { it.timestamp == cutAt }
}
