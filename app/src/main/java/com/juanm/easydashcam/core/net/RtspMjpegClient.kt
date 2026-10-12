package com.juanm.easydashcam.core.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
    private val extraSockets = ArrayList<DatagramSocket>()   // audio track sockets
    private var audioRtp: DatagramSocket? = null
    private var sessionId: String? = null
    private var cseq = 0
    private var audioPt: Pair<Int, Int>? = null   // (payload type, sample rate)
    private val muted = java.util.concurrent.atomic.AtomicBoolean(false)

    class RtspException(msg: String) : Exception(msg)

    /** Silence/resume the L16 audio channel without touching the stream. */
    fun setAudioMuted(m: Boolean) = muted.set(m)

    /**
     * Runs the session until cancelled or the stream fails; [onFrame] gets one
     * decoded JPEG per camera frame, called on Dispatchers.IO.
     * Throws [RtspException] on handshake errors.
     */
    suspend fun run(onFrame: (Bitmap) -> Unit) = withContext(Dispatchers.IO) {
        val base = "rtsp://$host:$port$path"
        sessionId = null   // a re-run is a fresh connection; drop the old session
        audioPt = null

        // The firmware only allows one RTSP session and answers TEARDOWN with
        // "501 Not Implemented" — after a leave/re-enter it may still hold the
        // old session and 404 the new SETUP. Retry the whole handshake.
        var rd: BufferedReader? = null
        var wr: java.io.OutputStream? = null
        var lastError: Exception? = null
        var handshakeOk = false

        fun request(rd: BufferedReader, wr: java.io.OutputStream,
                    method: String, url: String, extra: String = ""): Map<String, String> {
            cseq++
            // User-Agent matches Viidure's FFmpeg client — cheap firmware is
            // known to gate on it.
            val req = "$method $url RTSP/1.0\r\nCSeq: $cseq\r\n" +
                "User-Agent: Lavf58.12.100\r\n" +
                (sessionId?.let { "Session: $it\r\n" } ?: "") + extra + "\r\n"
            wr.write(req.toByteArray(Charsets.US_ASCII))
            wr.flush()
            val status = rd.readLine() ?: throw RtspException("no reply to $method")
            Log.i("RTSP", "$method -> $status")
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

        var setup: Map<String, String> = emptyMap()
        var rtpSock: DatagramSocket? = null
        var rtcpSock: DatagramSocket? = null
        for (attempt in 1..3) {
            try {
                cseq = 0   // Viidure restarts at 1 per session; the firmware may
                           // reject out-of-sequence cseq on a fresh TCP conn
                val s = Socket(host, port).also { it.soTimeout = 8000; ctrl = it }
                rd = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                wr = s.getOutputStream()
                request(rd!!, wr!!, "OPTIONS", base)
                val desc = request(rd!!, wr!!, "DESCRIBE", base, "Accept: application/sdp\r\n")
                val sdp = desc["#body"] ?: throw RtspException("no SDP in DESCRIBE")
                Log.i("RTSP", "SDP: ${sdp.replace('\n', ' ').take(300)}")
                val tracks = Regex("""a=control:(\S+)""").findAll(sdp)
                    .map { it.groupValues[1] }.toList()
                if (tracks.isEmpty()) throw RtspException("SDP has no track")

                // L16 payload type is usually dynamic — read it from the SDP
                // rtpmap so we can filter the audio socket correctly.
                audioPt = Regex("""a=rtpmap:(\d+)\s+L16/(\d+)""").find(sdp)
                    ?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                if (audioPt != null) Log.i("RTSP", "audio: L16 pt=${audioPt!!.first} rate=${audioPt!!.second}")

                // Viidure SETUPs every advertised track (video + audio) before
                // PLAY — replicate it; the audio socket feeds an AudioTrack.
                tracks.forEachIndexed { i, track ->
                    val rs = DatagramSocket(0).also { it.soTimeout = 8000 }
                    val cs = try { DatagramSocket(rs.localPort + 1) } catch (_: Exception) { DatagramSocket(0) }
                    cs.soTimeout = 8000
                    setup = request(
                        rd!!, wr!!, "SETUP", "$base/$track",
                        "Transport: RTP/AVP/UDP;unicast;client_port=${rs.localPort}-${cs.localPort}\r\n"
                    )
                    sessionId = setup["session"]?.substringBefore(';')
                    if (i == 0) { rtpSock = rs; rtcpSock = cs; rtp = rs; rtcp = cs }
                    else if (audioPt != null && audioRtp == null) { audioRtp = rs; extraSockets += cs }
                    else { extraSockets += rs; extraSockets += cs }
                }
                request(rd!!, wr!!, "PLAY", "$base/", "Range: npt=0.000-\r\n")
                handshakeOk = true
                break
            } catch (e: Exception) {
                lastError = e
                Log.i("RTSP", "attempt $attempt failed: ${e.message}")
                runCatching { ctrl?.close() }; ctrl = null
                stop()
                if (attempt < 3) delay(2000)
            }
        }
        if (!handshakeOk) throw RtspException(lastError?.message ?: "handshake failed")
        Log.i("RTSP", "PLAY ok, waiting for RTP on :${rtpSock!!.localPort}")

        val cam = InetAddress.getByName(host)
        val serverRtp = setup["transport"]?.let {
            Regex("""server_port=(\d+)""").find(it)?.groupValues?.get(1)?.toInt()
        }
        // Viidure pings the server ports right after SETUP — likely so the
        // firmware learns our reachable socket. Cheap to replicate.
        serverRtp?.let {
            runCatching {
                rtpSock!!.send(DatagramPacket(byteArrayOf(0, 0, 0, 0, 0, 0), 6, cam, it))
                rtcpSock!!.send(DatagramPacket(byteArrayOf(0, 0, 0, 0, 0), 5, cam, it + 1))
            }
        }

        // Keep the session alive; the camera accepts OPTIONS anytime.
        val keepalive = launch {
            while (isActive) {
                delay(20_000)
                runCatching { request(rd!!, wr!!, "OPTIONS", base) }
            }
        }
        val audioJob = launch { runAudio() }

        try {
            var frame = ByteArray(MAX_FRAME)
            var expectedOffset = -1   // -1 = waiting for a fresh frame start
            var end = 0
            val pkt = DatagramPacket(ByteArray(65535), 65535)
            while (isActive) {
                rtpSock!!.receive(pkt)
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
            audioJob.cancel()
            // TEARDOWN must go out before ctrl is closed — otherwise the camera
            // keeps a stale session and 404s the next SETUP.
            runCatching { rd?.let { request(it, wr!!, "TEARDOWN", "$base/") } }
            close()
        }
        Unit
    }

    /**
     * Plays the camera's L16 audio track. Runs until the socket is closed;
     * no-ops entirely when the SDP advertised no audio.
     */
    private fun runAudio() {
        val sock = audioRtp ?: return
        val (pt, rate) = audioPt ?: return
        val buf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4 * 1024) * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(buf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        Log.i("RTSP", "audio playing on :${sock.localPort} (${rate} Hz)")
        val pkt = DatagramPacket(ByteArray(4096), 4096)
        val pcm = ByteArray(4096)
        try {
            track.play()
            while (true) {
                sock.receive(pkt)
                val d = pkt.data
                val off = pkt.offset
                if (pkt.length < 12) continue
                val csrc = d[off].toInt() and 0x0F
                if ((d[off + 1].toInt() and 0x7F) != pt) continue
                val start = off + 12 + csrc * 4
                val len = pkt.length - (start - off)
                if (len <= 0) continue
                if (muted.get()) continue   // drain packets, drop audio
                // L16 on the wire is big-endian; Android PCM_16BIT is little.
                var i = 0
                while (i + 1 < len) {
                    pcm[i] = d[start + i + 1]
                    pcm[i + 1] = d[start + i]
                    i += 2
                }
                track.write(pcm, 0, len and 1.inv())
            }
        } catch (_: Exception) {
            // socket closed or playback failure — either way the stream is over
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Close only the media sockets — unblocks receive() so run() can send a
     *  clean TEARDOWN and finish. Called on ON_STOP/dispose. */
    fun stop() {
        runCatching { rtp?.close() }
        runCatching { rtcp?.close() }
        runCatching { audioRtp?.close() }
        audioRtp = null
        extraSockets.forEach { runCatching { it.close() } }
        extraSockets.clear()
        rtp = null; rtcp = null
    }

    fun close() {
        stop()
        runCatching { ctrl?.close() }
        ctrl = null
    }

    private companion object {
        const val PT_JPEG = 26
        const val MAX_FRAME = 4 * 1024 * 1024
    }
}
