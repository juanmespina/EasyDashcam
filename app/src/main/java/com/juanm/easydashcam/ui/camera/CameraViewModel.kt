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
import com.juanm.easydashcam.core.download.DownloadService
import com.juanm.easydashcam.core.net.GpsocketClient
import com.juanm.easydashcam.core.settings.MenuParser
import com.juanm.easydashcam.core.settings.SettingCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val session = (app as EasyDashcamApp).session
    private val client = session.client

    private val _state = MutableStateFlow<GpsocketClient.State>(GpsocketClient.State.Disconnected)
    val state: StateFlow<GpsocketClient.State> = _state

    /** Download progress per record id, fed by DownloadService via the session. */
    val downloads = session.downloads

    /** uniqueId → MediaStore uri for files already on the phone. */
    private val _localFiles = MutableStateFlow<Map<Int, Uri>>(emptyMap())
    val localFiles: StateFlow<Map<Int, Uri>> = _localFiles

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

    /** One-shot user messages (errors, applied settings). */
    private val _notices = MutableStateFlow<List<String>>(emptyList())
    val notices: StateFlow<List<String>> = _notices

    // Debug: rolling log of incoming camera frames, to map the protocol live.
    private val _frameLog = MutableStateFlow<List<String>>(emptyList())
    val frameLog: StateFlow<List<String>> = _frameLog

    init {
        viewModelScope.launch {
            client.state.collect { _state.value = it }
        }
        viewModelScope.launch {
            client.frames.collect { f ->
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
    }

    fun localFile(f: GpsocketClient.CameraFile): Uri? = _localFiles.value[f.uniqueId]

    /** Re-scan Movies/EasyDashcam for clips we already downloaded. */
    fun refreshLocalFiles() {
        if (Build.VERSION.SDK_INT < 29) return   // pre-29 path used app-private storage
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val out = mutableMapOf<Int, Uri>()
            getApplication<Application>().contentResolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME),
                "(${MediaStore.Video.Media.RELATIVE_PATH} LIKE 'Movies/EasyDashcam/%'" +
                    " OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE 'Pictures/EasyDashcam/%')",
                null, null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val nameRx = Regex("""cam_([0-9a-fA-F]{4})([A-Z])\.(avi|jpg)""")
                while (c.moveToNext()) {
                    val m = nameRx.matchEntire(c.getString(nameCol)) ?: continue
                    val uid = (m.groupValues[1].toInt(16) shl 8) or m.groupValues[2][0].code
                    out[uid] = ContentUris.withAppendedId(collection, c.getLong(idCol))
                }
            }
            _localFiles.value = out
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

    fun refreshFiles() {
        if (_filesLoading.value) return
        viewModelScope.launch {
            _filesLoading.value = true
            runCatching { client.listFiles() }
                .onSuccess { _files.value = it.sortedByDescending { f -> f.timestamp } }
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

    // ---- Settings -----------------------------------------------------------

    fun loadSettings() {
        if (_settingsLoading.value) return
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
