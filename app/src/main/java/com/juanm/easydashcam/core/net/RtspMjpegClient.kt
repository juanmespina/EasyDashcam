package com.juanm.easydashcam.core.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

/**
 * Minimal RTSP + RTP/JPEG (RFC 2435) client for the camera's MJPEG preview.
 *
 * ExoPlayer's media3-rtsp rejects static payload type 26 (MJPEG), so the
 * handshake and depacketizer live here. Wire shape verified against pcaps:
 * OPTIONS → DESCRIBE (SDP) → SETUP track0 (UDP) → PLAY → RTP datagrams.
 */
class RtspMjpegClient(
    private val host: String = "192.168.100.1",
    private val port: Int = 8080,
    private val path: String = "/?action=stream",
) {
    private var ctrl: Socket? = null
    private var rtp: DatagramSocket? = null
    private var rtcp: DatagramSocket? = null
    private var sessionId: String? = null
    private var cseq = 0

    class RtspException(msg: String) : Exception(msg)

    /**
     * Runs the session until cancelled or the stream fails; [onFrame] gets one
     * decoded JPEG per camera frame, called on Dispatchers.IO.
     * Throws [RtspException] on handshake errors.
     */
    suspend fun run(onFrame: (Bitmap) -> Unit) = withContext(Dispatchers.IO) {
        val base = "rtsp://$host:$port$path"
        val s = Socket(host, port).also {
            it.soTimeout = 8000
            ctrl = it
        }
        val rd = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
        val wr = s.getOutputStream()

        fun request(method: String, url: String, extra: String = ""): Map<String, String> {
            cseq++
            val req = "$method $url RTSP/1.0\r\nCSeq: $cseq\r\n" +
                (sessionId?.let { "Session: $it\r\n" } ?: "") + extra + "\r\n"
            wr.write(req.toByteArray(Charsets.US_ASCII))
            wr.flush()
            val status = rd.readLine() ?: throw RtspException("no reply to $method")
            Log.d("RTSP", "$method -> $status")
            if (!status.contains(" 200")) throw RtspException("$method: $status")
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = rd.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':'); if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            headers["content-length"]?.toIntOrNull()?.let { n ->
                val body = CharArray(n); rd.read(body)
                headers["#body"] = String(body)
            }
            return headers
        }

        request("OPTIONS", base)
        val desc = request("DESCRIBE", base, "Accept: application/sdp\r\n")
        val sdp = desc["#body"] ?: throw RtspException("no SDP in DESCRIBE")
        val track = Regex("""a=control:(\S+)""").find(sdp)?.groupValues?.get(1)
            ?: throw RtspException("SDP has no track")

        // Two adjacent UDP sockets: RTP + RTCP, announced in SETUP.
        val rtpSock = DatagramSocket(0).also { it.soTimeout = 8000; rtp = it }
        val rtcpSock = try { DatagramSocket(rtpSock.localPort + 1) } catch (_: Exception) { DatagramSocket(0) }
        rtcpSock.soTimeout = 8000
        rtcp = rtcpSock
        val setup = request(
            "SETUP", "$base/$track",
            "Transport: RTP/AVP/UDP;unicast;client_port=${rtpSock.localPort}-${rtcpSock.localPort}\r\n"
        )
        sessionId = setup["session"]?.substringBefore(';')
        val cam = InetAddress.getByName(host)
        val serverRtp = setup["transport"]?.let {
            Regex("""server_port=(\d+)""").find(it)?.groupValues?.get(1)?.toInt()
        }

        // Viidure pings the server ports right after SETUP — likely so the
        // firmware learns our reachable socket. Cheap to replicate.
        serverRtp?.let {
            runCatching {
                rtpSock.send(DatagramPacket(byteArrayOf(0, 0, 0, 0, 0, 0), 6, cam, it))
                rtcpSock.send(DatagramPacket(byteArrayOf(0, 0, 0, 0, 0), 5, cam, it + 1))
            }
        }
        request("PLAY", "$base/", "Range: npt=0.000-\r\n")
        Log.d("RTSP", "PLAY ok, waiting for RTP on :${rtpSock.localPort}")

        // Keep the session alive; the camera accepts OPTIONS anytime.
        val keepalive = launch {
            while (isActive) {
                delay(20_000)
                runCatching { request("OPTIONS", base) }
            }
        }

        try {
            var frame = ByteArray(MAX_FRAME)
            var expectedOffset = -1   // -1 = waiting for a fresh frame start
            var end = 0
            val pkt = DatagramPacket(ByteArray(65535), 65535)
            while (isActive) {
                rtpSock.receive(pkt)
                val d = pkt.data
                val off = pkt.offset
                if (pkt.length < 12 + 8) continue
                val csrcCount = d[off].toInt() and 0x0F
                val marker = d[off + 1].toInt() and 0x80 != 0
                val pt = d[off + 1].toInt() and 0x7F
                if (pt != PT_JPEG) continue

                var p = off + 12 + csrcCount * 4
                // RFC 2435 payload header: 1B type-specific | 3B frag offset | type | Q | w/8 | h/8
                p++                                            // type-specific
                val fragOff = (d[p].toInt() and 0xFF shl 16) or
                    (d[p + 1].toInt() and 0xFF shl 8) or
                    (d[p + 2].toInt() and 0xFF)
                p += 3
                val jpegType = d[p].toInt() and 0xFF
                val q = d[p + 1].toInt() and 0xFF
                p += 4
                if (jpegType >= 64) p++                        // restart marker hdr
                if (q >= 100) {                                // per-packet quant tables
                    val qtLen = (d[p + 2].toInt() and 0xFF shl 8) or (d[p + 3].toInt() and 0xFF)
                    p += 4 + qtLen                             // DRI(2)+len(2)+tables
                }

                val payloadLen = pkt.length - (p - off)
                if (payloadLen <= 0 || fragOff + payloadLen > MAX_FRAME) continue

                if (fragOff == 0) { expectedOffset = 0; end = 0 }
                else if (fragOff != end) { expectedOffset = -1; continue }  // gap: drop frame
                if (expectedOffset < 0) continue
                System.arraycopy(d, p, frame, fragOff, payloadLen)
                end = fragOff + payloadLen

                if (marker) {
                    BitmapFactory.decodeByteArray(frame, 0, end)?.let(onFrame)
                    expectedOffset = -1
                }
            }
        } finally {
            keepalive.cancel()
            runCatching { request("TEARDOWN", "$base/") }
            close()
        }
        Unit
    }

    fun close() {
        runCatching { ctrl?.close() }
        runCatching { rtp?.close() }
        runCatching { rtcp?.close() }
        ctrl = null; rtp = null; rtcp = null
    }

    private companion object {
        const val PT_JPEG = 26
        const val MAX_FRAME = 4 * 1024 * 1024
    }
}
