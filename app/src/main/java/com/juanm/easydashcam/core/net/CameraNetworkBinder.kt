package com.juanm.easydashcam.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The camera's hotspot has no internet, so Android may route sockets to
 * cellular by default. Binding the process to the Wi-Fi network forces all
 * traffic to the camera.
 */
class CameraNetworkBinder(context: Context) {

    private val appContext = context.applicationContext
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Bind app sockets to the Wi-Fi network and return its gateway IP —
     *  on a camera hotspot the gateway IS the camera. Returns null when no
     *  Wi-Fi network exists (or binding failed). */
    @Suppress("DEPRECATION")
    fun bindToWifi(): String? {
        // allNetworks is deprecated (API 31+) but the callback-tracking
        // replacement is more machinery than this one-shot lookup needs.
        val wifi = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        if (!cm.bindProcessToNetwork(wifi)) return null
        return gatewayOf(wifi)
    }

    fun unbind() {
        cm.bindProcessToNetwork(null)
    }

    private fun gatewayOf(n: Network): String? {
        if (Build.VERSION.SDK_INT >= 29) {
            return cm.getLinkProperties(n)?.routes?.firstNotNullOfOrNull { r ->
                r.gateway?.takeIf { it is Inet4Address }?.hostAddress
            }
        }
        // dhcpInfo is deprecated since 31 but the only API on 26–28; the
        // gateway int is little-endian.
        @Suppress("DEPRECATION")
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val gw = wm.dhcpInfo.gateway
        if (gw == 0) return null
        return InetAddress.getByAddress(byteArrayOf(
            gw.toByte(), (gw shr 8).toByte(), (gw shr 16).toByte(), (gw shr 24).toByte()
        )).hostAddress
    }
}
