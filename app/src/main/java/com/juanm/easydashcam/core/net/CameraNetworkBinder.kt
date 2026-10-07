package com.juanm.easydashcam.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * The camera's hotspot has no internet, so Android may route sockets to
 * cellular by default. Binding the process to the Wi-Fi network forces all
 * traffic to the camera.
 */
class CameraNetworkBinder(context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Bind app sockets to the Wi-Fi network. False if no Wi-Fi network exists. */
    fun bindToWifi(): Boolean {
        val wifi = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return false
        return cm.bindProcessToNetwork(wifi)
    }

    fun unbind() {
        cm.bindProcessToNetwork(null)
    }
}
