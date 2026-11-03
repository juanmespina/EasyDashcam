package com.juanm.easydashcam.core.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.juanm.easydashcam.core.DebugLogger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val path: String = "/?action=stream"
) {
    private var ctrl: Socket? = null
    private var rtp: DatagramSocket? = null
    private var rtcp: DatagramSocket? = null
    private val extraSockets = ArrayList<DatagramSocket>() // audio track sockets
    private var audioRtp: DatagramSocket? = null
    private var sessionId: String? = null
    private var cseq = 0
    private var audioPt: Pair<Int, Int>? = null // (payload type, sample rate)
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
        sessionId = null // a re-run is a fresh connection; drop the old session
        audioPt = null

        // The firmware only allows one RTSP session and answers TEARDOWN with
        // "501 Not Implemented" — after a leave/re-enter it may still hold the
        // old session and 404 the new SETUP. Retry the whole handshake.
        var rd: BufferedReader? = null
        var wr: java.io.OutputStream? = null
        var lastError: Exception? = null
        var handshakeOk = false

        fun request(
            rd: BufferedReader,
            wr: java.io.OutputStream,
            method: String,
            url: String,
            extra: String = ""
        ): Map<String, String> {
            cseq++
            // User-Agent matches Viidure's FFmpeg client — cheap firmware is
            // known to gate on it.
            val req = "$method $url RTSP/1.0\r\nCSeq: $cseq\r\n" +
                "User-Agent: Lavf58.12.100\r\n" +
                (sessionId?.let { "Session: $it\r\n" } ?: "") + extra + "\r\n"
            wr.write(req.toByteArray(Charsets.US_ASCII))
            wr.flush()
            val status = rd.readLine() ?: throw RtspException("no reply to $method")
            DebugLogger.log("RTSP", "$method -> $status")
            if (!status.contains(" 200")) throw RtspException("$method: $status")
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = rd.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i >
                    0
                ) {
                    headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
            }
            headers["content-length"]?.toIntOrNull()?.let { n ->
                // read(char[]) is a short-read — loop until the body is full
                // or the stream ends, or a truncated SDP gets parsed.
                val body = CharArray(n)
                var got = 0
                while (got < n) {
                    val r = rd.read(body, got, n - got)
                    if (r < 0) break
                    got += r
                }
                headers["#body"] = String(body, 0, got)
            }
            return headers
        }

        var rtpSock: DatagramSocket? = null
        var rtcpSock: DatagramSocket? = null
        // Per-track (rtp, rtcp, server_port) — captured inside the SETUP loop
        // because `setup` is overwritten on every iteration.
        val trackSocks = ArrayList<Triple<DatagramSocket, DatagramSocket, Int>>()
        for (attempt in 1..3) {
            // Sockets stay local until the attempt succeeds — a mid-SETUP
            // failure previously leaked them (unreachable from stop()) and
            // left stale entries in trackSocks.
            val attemptTracks = ArrayList<Triple<DatagramSocket, DatagramSocket, Int>>()
            try {
                cseq = 0 // Viidure restarts at 1 per session; the firmware may
                // reject out-of-sequence cseq on a fresh TCP conn
                sessionId = null // …and must not send a dead Session header
                val s = Socket(host, port).also {
                    it.soTimeout = 8000
                    ctrl = it
                }
                rd = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                wr = s.getOutputStream()
                request(rd!!, wr!!, "OPTIONS", base)
                val desc = request(rd!!, wr!!, "DESCRIBE", base, "Accept: application/sdp\r\n")
                val sdp = desc["#body"] ?: throw RtspException("no SDP in DESCRIBE")
                DebugLogger.log("RTSP", "SDP: ${sdp.replace('\n', ' ').take(300)}")
                val tracks = Regex("""a=control:(\S+)""").findAll(sdp)
                    .map { it.groupValues[1] }.toList()
                if (tracks.isEmpty()) throw RtspException("SDP has no track")

                // L16 payload type is usually dynamic — read it from the SDP
                // rtpmap so we can filter the audio socket correctly. Cheap
                // firmware may instead use a static PT (10/11) with no rtpmap:
                // fall back to whatever the m=audio line lists.
                audioPt = Regex("""a=rtpmap:(\d+)\s+L16/(\d+)""").find(sdp)
                    ?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                    ?: Regex("""m=audio\s+\d+\s+RTP/AVP\s+(\d+)""").find(sdp)
                        ?.let { it.groupValues[1].toInt() to 16000 }
                DebugLogger.log(
                    "RTSP",
                    "audio: ${audioPt?.let {
                        "pt=${it.first} rate=${it.second}"
                    } ?: "none in SDP"}"
                )

                // Viidure SETUPs every advertised track (video + audio) before
                // PLAY — replicate it; the audio socket feeds an AudioTrack.
                tracks.forEachIndexed { i, track ->
                    val rs = DatagramSocket(0).also { it.soTimeout = 8000 }
                    val cs = try {
                        DatagramSocket(rs.localPort + 1)
                    } catch (_: Exception) {
                        DatagramSocket(0)
                    }
                    cs.soTimeout = 8000
                    val setup = request(
                        rd!!,
                        wr!!,
                        "SETUP",
                        "$base/$track",
                        "Transport: RTP/AVP/UDP;unicast;client_port=${rs.localPort}-${cs.localPort}\r\n"
                    )
                    sessionId = setup["session"]?.substringBefore(';')
                    attemptTracks += Triple(
                        rs,
                        cs,
                        setup["transport"]?.let {
                            Regex("""server_port=(\d+)""").find(it)?.groupValues?.get(1)?.toInt()
                        } ?: -1
                    )
                    if (i == 0) {
                        rtpSock = rs
                        rtcpSock = cs
                    } else if (audioPt != null && audioRtp == null) {
                        audioRtp = rs
                    } else { /* extra track sockets live in attemptTracks */ }
                }
                request(rd!!, wr!!, "PLAY", "$base/", "Range: npt=0.000-\r\n")
                // Handshake succeeded — commit this attempt's sockets. stop()
                // owns every socket that isn't the video rtp/rtcp pair or the
                // audio socket, so they all land in extraSockets.
                trackSocks += attemptTracks
                rtp = rtpSock
                rtcp = rtcpSock
                attemptTracks.forEach { (rs, cs, _) ->
                    if (rs !== rtpSock && rs !== audioRtp) extraSockets += rs
                    if (cs !== rtcpSock) extraSockets += cs
                }
                handshakeOk = true
                break
            } catch (e: kotlinx.coroutines.CancellationException) {
                attemptTracks.forEach {
                    runCatching { it.first.close() }
                    runCatching { it.second.close() }
                }
                throw e
            } catch (e: Exception) {
                lastError = e
                DebugLogger.log("RTSP", "attempt $attempt failed: ${e.message}")
                attemptTracks.forEach {
                    runCatching { it.first.close() }
                    runCatching { it.second.close() }
                }
                runCatching { ctrl?.close() }
                ctrl = null
                audioRtp = null
                if (attempt < 3) delay(2000)
            }
        }
        if (!handshakeOk) throw RtspException(lastError?.message ?: "handshake failed")
        DebugLogger.log("RTSP", "PLAY ok, waiting for RTP on :${rtpSock!!.localPort}")

        val cam = InetAddress.getByName(host)
        // Viidure pings every track's server_port from that track's own
        // socket right after SETUP — the firmware learns the peer from the
        // inbound packet rather than honoring client_port, so pinging the
        // audio port from the video socket makes it send audio to the wrong
        // local port (observed: zero packets on the audio socket).
        trackSocks.forEach { (rs, cs, serverPort) ->
            if (serverPort <= 0) return@forEach
            runCatching {
                rs.send(DatagramPacket(byteArrayOf(0, 0, 0, 0, 0, 0), 6, cam, serverPort))
                cs.send(DatagramPacket(byteArrayOf(0, 0, 0, 0, 0), 5, cam, serverPort + 1))
            }
        }
        DebugLogger.log("RTSP", "pings: ${trackSocks.joinToString { "${it.first.localPort}->:${it.third}" }}")

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
            var expectedOffset = -1 // -1 = waiting for a fresh frame start
            var end = 0
            var nonJpegSeen = 0
            val pkt = DatagramPacket(ByteArray(65535), 65535)
            while (isActive) {
                rtpSock!!.receive(pkt)
                val d = pkt.data
                val off = pkt.offset
                if (pkt.length < 12 + 8) continue
                val csrcCount = d[off].toInt() and 0x0F
                val marker = d[off + 1].toInt() and 0x80 != 0
                val pt = d[off + 1].toInt() and 0x7F
                if (pt != PT_JPEG) {
                    // Audio packets landing here mean the camera ignored
                    // client_port — the ping fix above should prevent it.
                    if (nonJpegSeen <
                        8
                    ) {
                        nonJpegSeen++
                        DebugLogger.log("RTSP", "video sock: pt=$pt len=${pkt.length}")
                    }
                    continue
                }

                var p = off + 12 + csrcCount * 4
                // RFC 2435 payload header: 1B type-specific | 3B frag offset | type | Q | w/8 | h/8
                p++ // type-specific
                val fragOff = (d[p].toInt() and 0xFF shl 16) or
                    (d[p + 1].toInt() and 0xFF shl 8) or
                    (d[p + 2].toInt() and 0xFF)
                p += 3
                val jpegType = d[p].toInt() and 0xFF
                val q = d[p + 1].toInt() and 0xFF
                p += 4
                if (jpegType >= 64) p += 4 // restart marker hdr (2B type + 2B data)
                // RFC 2435: dynamic quant tables only for Q >= 128, and only
                // in the frame's first fragment — skipping them mid-frame
                // would corrupt the payload offset.
                if (q >= 128 && fragOff == 0) {
                    val qtLen = (d[p + 2].toInt() and 0xFF shl 8) or (d[p + 3].toInt() and 0xFF)
                    p += 4 + qtLen // DRI(2)+len(2)+tables
                }

                val payloadLen = pkt.length - (p - off)
                if (payloadLen <= 0 || fragOff + payloadLen > MAX_FRAME) continue

                if (fragOff == 0) {
                    expectedOffset = 0
                    end = 0
                } else if (fragOff != end) {
                    expectedOffset = -1
                    continue
                } // gap: drop frame
                if (expectedOffset < 0) continue
                System.arraycopy(d, p, frame, fragOff, payloadLen)
                end = fragOff + payloadLen

                if (marker) {
                    BitmapFactory.decodeByteArray(frame, 0, end)?.let(onFrame)
                    expectedOffset = -1
                }
            }
        } finally {
            // Join the keepalive so an in-flight OPTIONS can't interleave
            // writes on ctrl with the TEARDOWN (bounded by the 8 s timeout).
            keepalive.cancel()
            runCatching { keepalive.join() }
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
        val buf = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4 * 1024) * 2
        if (buf <= 0) {
            DebugLogger.log("RTSP", "audio: getMinBufferSize=$buf")
            return
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(buf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            DebugLogger.log("RTSP", "audio: AudioTrack init failed")
            track.release()
            return
        }
        DebugLogger.log("RTSP", "audio playing on :${sock.localPort} ($rate Hz)")
        val pkt = DatagramPacket(ByteArray(4096), 4096)
        val pcm = ByteArray(4096)
        var seen = 0
        try {
            track.play()
            while (true) {
                // The socket has an 8 s timeout — the camera may simply stop
                // sending audio when there's nothing to hear; keep waiting.
                try {
                    sock.receive(pkt)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                val d = pkt.data
                val off = pkt.offset
                if (pkt.length < 12) continue
                val csrc = d[off].toInt() and 0x0F
                val packetPt = d[off + 1].toInt() and 0x7F
                if (seen < 8) {
                    seen++
                    DebugLogger.log("RTSP", "audio pkt pt=$packetPt len=${pkt.length}")
                }
                if (packetPt != pt) continue
                val start = off + 12 + csrc * 4
                val len = pkt.length - (start - off)
                if (len <= 0) continue
                if (muted.get()) continue // drain packets, drop audio
                // L16 on the wire is big-endian; Android PCM_16BIT is little.
                var i = 0
                while (i + 1 < len) {
                    pcm[i] = d[start + i + 1]
                    pcm[i + 1] = d[start + i]
                    i += 2
                }
                track.write(pcm, 0, len and 1.inv())
            }
        } catch (e: Exception) {
            DebugLogger.log("RTSP", "audio stopped: ${e.message}") // socket closed — stream over
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
        rtp = null
        rtcp = null
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
