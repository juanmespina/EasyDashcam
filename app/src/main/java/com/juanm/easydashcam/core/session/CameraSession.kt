package com.juanm.easydashcam.core.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.juanm.easydashcam.core.net.CameraNetworkBinder
import com.juanm.easydashcam.core.net.GpsocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the camera connection. Both the UI (CameraViewModel)
 * and DownloadService talk to the camera through this single session, so a
 * download keeps running while the user navigates or the screen is off.
 */
class CameraSession(context: Context) {

    private val appContext = context.applicationContext
    val client = GpsocketClient()
    private val binder = CameraNetworkBinder(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** id (record id) → transfer state, fed by DownloadService, read by UI. */
    data class DownloadState(
        val received: Long,
        val total: Long,
        val done: Boolean = false,
        val error: String? = null,
        /** Estimated seconds remaining, from measured throughput. */
        val etaSec: Long? = null
    )

    private val _downloads = MutableStateFlow<Map<Int, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<Int, DownloadState>> = _downloads

    fun updateDownload(id: Int, state: DownloadState) {
        _downloads.update { it + (id to state) }
    }

    /** Forget a transfer — e.g. its local file was deleted and the record
     *  should become downloadable again. */
    fun removeDownload(id: Int) {
        _downloads.update { it - id }
    }

    /** Drop all transfer state — a reconnect may be looking at a new card. */
    fun clearDownloads() {
        _downloads.value = emptyMap()
    }

    // ---- Auto-download -----------------------------------------------------
    // When enabled, refreshFiles() enqueues only records newer than the
    // watermark — clips that existed before enabling are left alone.

    private val prefs = appContext.getSharedPreferences("easydashcam", Context.MODE_PRIVATE)

    private val _autoDownload = MutableStateFlow(prefs.getBoolean("auto_download", false))
    val autoDownload: StateFlow<Boolean> = _autoDownload

    fun setAutoDownload(on: Boolean) {
        // Re-enabling after a gap re-bases the watermark — otherwise every
        // clip recorded while it was off would queue as "new" all at once.
        if (on && !_autoDownload.value) {
            autoSyncWatermark = java.time.LocalDateTime.now()
        }
        prefs.edit().putBoolean("auto_download", on).apply()
        _autoDownload.value = on
    }

    var autoSyncWatermark: java.time.LocalDateTime?
        get() = prefs.getString("auto_ts", null)
            ?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }
        set(v) {
            prefs.edit().putString("auto_ts", v?.toString()).apply()
        }

    // Auto-reconnect: once a connection succeeded, a Wi-Fi network coming back
    // (camera rebooted, hotspot re-joined) re-arms the socket — unless the user
    // explicitly disconnected.
    private var hadConnection = false
    private var userDisconnected = false
    private var wifiPresent = false

    init {
        scope.launch {
            client.state.collect {
                if (it is GpsocketClient.State.Connected) hadConnection = true
            }
        }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !wifiPresent) {
                    wifiPresent = true
                    maybeReconnect()
                }
            }
            override fun onLost(n: Network) {
                wifiPresent = false
            }
        })
    }

    private fun maybeReconnect() {
        if (!hadConnection || userDisconnected) return
        if (client.state.replayCache.firstOrNull() is GpsocketClient.State.Disconnected) {
            scope.launch { runCatching { connect() } }
        }
    }

    /** The camera address that answered last — other firmware families run
     *  their hotspot on different subnets (192.168.1.1, 10.0.0.1, …). */
    private val _cameraHost = MutableStateFlow(GpsocketClient.CAMERA_HOST)
    val cameraHost: StateFlow<String> = _cameraHost

    suspend fun connect(): Boolean {
        userDisconnected = false
        // On the camera hotspot the DHCP gateway IS the camera — try it
        // first, then the address this firmware family is known to use.
        val gateway = binder.bindToWifi() ?: return false
        for (host in listOf(gateway, GpsocketClient.CAMERA_HOST).distinct()) {
            client.connect(host)
            if (client.state.replayCache.firstOrNull()
                    is GpsocketClient.State.Connected
            ) {
                _cameraHost.value = host
                break
            }
        }
        // A failed attempt surfaces as State.Error through the normal flow.
        return true
    }

    fun disconnect() {
        userDisconnected = true
        client.disconnect()
        binder.unbind()
    }
}
