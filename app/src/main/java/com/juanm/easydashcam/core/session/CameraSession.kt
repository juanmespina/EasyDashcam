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

    suspend fun connect(): Boolean {
        userDisconnected = false
        if (!binder.bindToWifi()) return false
        client.connect()
        return true
    }

    fun disconnect() {
        userDisconnected = true
        client.disconnect()
        binder.unbind()
    }
}
