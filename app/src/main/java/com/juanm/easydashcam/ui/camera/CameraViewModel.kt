package com.juanm.easydashcam.ui.camera

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.juanm.easydashcam.core.net.CameraNetworkBinder
import com.juanm.easydashcam.core.net.GpsocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val client = GpsocketClient()
    private val binder = CameraNetworkBinder(app)

    private val _state = MutableStateFlow<GpsocketClient.State>(GpsocketClient.State.Disconnected)
    val state: StateFlow<GpsocketClient.State> = _state

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
    }

    fun connect() {
        if (!binder.bindToWifi()) {
            _state.value = GpsocketClient.State.Error(
                IllegalStateException("No Wi-Fi network — join the camera hotspot first.")
            )
            return
        }
        _frameLog.value = emptyList()
        viewModelScope.launch { client.connect() }
    }

    fun disconnect() {
        client.disconnect()
        binder.unbind()
    }

    /** Send a raw GPSOCKET command live, to map protocol semantics by observation. */
    fun probe(cmd: Int, args: ByteArray = byteArrayOf()) {
        val argHex = args.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        _frameLog.update { (it + "\u2192 TX cmd=0x%04x $argHex".format(cmd)).takeLast(60) }
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

    /** Debug: fetch a fresh file list, pick the smallest recording, download it
     *  into app storage and report progress in the frame log. */
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

    private fun log(line: String) = _frameLog.update { (it + "» $line").takeLast(60) }

    companion object {
        // From camera_menu.xml (docs/recon): record, capture and system settings
        // ids + the 0x0300 device-name string param.
        private val SETTING_PARAM_IDS = listOf(
            0x0000, 0x0003, 0x0004, 0x0005,
            0x0100, 0x0101, 0x0108,
            0x0200, 0x0201, 0x0205, 0x0209,
            0x0300
        )
    }

    override fun onCleared() {
        client.disconnect()
        binder.unbind()
    }
}
