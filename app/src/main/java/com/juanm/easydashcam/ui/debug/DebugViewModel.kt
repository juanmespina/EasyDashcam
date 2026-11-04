package com.juanm.easydashcam.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.juanm.easydashcam.EasyDashcamApp
import com.juanm.easydashcam.core.net.GpsocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Debug screen state: a rolling log of camera frames and one-tap protocol
 * actions. Created only by the Debug route, which exists only in debug
 * builds, so release builds neither format nor keep any of it.
 */
class DebugViewModel(app: Application) : AndroidViewModel(app) {

    private val client = (app as EasyDashcamApp).session.client

    private val _frameLog = MutableStateFlow<List<String>>(emptyList())
    val frameLog: StateFlow<List<String>> = _frameLog

    init {
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
    }

    fun fetchMenu() {
        viewModelScope.launch {
            runCatching { client.requestMenuXml() }
                .onSuccess { log("menu XML: ${it.length} chars") }
                .onFailure {
                    val cached = client.lastMenuXml
                    log(
                        "menu re-request failed: ${it.message}" +
                            (cached?.let { c -> " (init cache: ${c.length} chars)" } ?: "")
                    )
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
                        if (mb != lastLogged) {
                            lastLogged = mb
                            log("  ${got / 1024}/${total / 1024} KiB")
                        }
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
                    is GpsocketClient.DownloadResult.NotConnected ->
                        log("not connected")
                }
            }.onFailure { log("download failed: ${it.message}") }
        }
    }

    private fun log(line: String) = _frameLog.update { (it + "» $line").takeLast(60) }

    companion object {
        private val SETTING_PARAM_IDS = listOf(
            0x0000, 0x0003, 0x0004, 0x0005,
            0x0100, 0x0101, 0x0108,
            0x0200, 0x0201, 0x0205, 0x0209,
            0x0300
        )
    }
}
