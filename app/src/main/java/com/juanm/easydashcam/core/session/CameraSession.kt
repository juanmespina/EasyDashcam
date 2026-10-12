package com.juanm.easydashcam.core.session

import android.content.Context
import com.juanm.easydashcam.core.net.CameraNetworkBinder
import com.juanm.easydashcam.core.net.GpsocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide owner of the camera connection. Both the UI (CameraViewModel)
 * and DownloadService talk to the camera through this single session, so a
 * download keeps running while the user navigates or the screen is off.
 */
class CameraSession(context: Context) {

    val client = GpsocketClient()
    private val binder = CameraNetworkBinder(context.applicationContext)

    /** id (record id) → transfer state, fed by DownloadService, read by UI. */
    data class DownloadState(
        val received: Long,
        val total: Long,
        val done: Boolean = false,
        val error: String? = null
    )

    private val _downloads = MutableStateFlow<Map<Int, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<Int, DownloadState>> = _downloads

    fun updateDownload(id: Int, state: DownloadState) {
        _downloads.update { it + (id to state) }
    }

    suspend fun connect(): Boolean {
        if (!binder.bindToWifi()) return false
        client.connect()
        return true
    }

    fun disconnect() {
        client.disconnect()
        binder.unbind()
    }
}
