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

    override fun onCleared() {
        client.disconnect()
        binder.unbind()
    }
}
