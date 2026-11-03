package com.juanm.easydashcam.core.net

import java.io.Closeable
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A localhost stand-in for the dashcam's GPSOCKET server (TCP 8081).
 *
 * It parses what the app sends, records every command, and answers the way
 * the real camera does in the pcaps: acks for session steps, the menu XML,
 * and a download as 32 KiB chunks plus an empty terminator. Tests override
 * [onCommand] to script misbehaviour (refusals, stalls, streams that keep
 * going after the app stops reading).
 */
class FakeCamera : Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    /** Every command the app sent, in order: (cmd, body). */
    val received = CopyOnWriteArrayList<Pair<Int, ByteArray>>()

    /** Record id → bytes the camera serves for it. */
    val files = HashMap<Int, ByteArray>()

    @Volatile var onCommand: (cmd: Int, body: ByteArray) -> Unit = ::defaultReply

    @Volatile private var out: OutputStream? = null
    private var client: Socket? = null

    init {
        thread(isDaemon = true, name = "fake-camera") {
            runCatching {
                val s = server.accept().also { client = it }
                s.tcpNoDelay = true
                out = s.getOutputStream()
                val ins = DataInputStream(s.getInputStream().buffered())
                while (true) {
                    val header = ByteArray(12).also { ins.readFully(it) }
                    val cmd = u16(header, 10)
                    val body = when (val n = ARG_BYTES[cmd]) {
                        null -> error("fake camera: unknown cmd 0x%04x".format(cmd))
                        LENGTH_PREFIXED -> {
                            val len = ByteArray(2).also { ins.readFully(it) }
                            len + ByteArray(u16(len, 0)).also { ins.readFully(it) }
                        }
                        else -> ByteArray(n).also { ins.readFully(it) }
                    }
                    received += cmd to body
                    onCommand(cmd, body)
                }
            }
        }
    }

    fun commands(cmd: Int): List<ByteArray> = received.filter { it.first == cmd }.map { it.second }

    /** A type-2 reply: header, u16 length, payload. */
    fun reply(cmd: Int, payload: ByteArray = ByteArray(0)) = write(frame(cmd, payload))

    /** A type-3 refusal (`feff`), as the camera sends for a rejected step. */
    fun refuse(cmd: Int) = write(
        GpsocketClient.MAGIC + le16(3) + le16(cmd) + byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    )

    /** Stream [data] as 32 KiB download chunks, optionally followed by the
     *  empty terminator. */
    fun stream(data: ByteArray, terminate: Boolean = true) {
        data.toList().chunked(CHUNK).forEach { reply(GpsocketClient.CMD_DOWNLOAD, it.toByteArray()) }
        if (terminate) reply(GpsocketClient.CMD_DOWNLOAD)
    }

    @Synchronized
    fun write(bytes: ByteArray) {
        val o = out ?: return
        runCatching {
            o.write(bytes)
            o.flush()
        }
    }

    fun defaultReply(cmd: Int, body: ByteArray) {
        when (cmd) {
            GpsocketClient.CMD_LOGIN -> reply(cmd, ByteArray(6))
            GpsocketClient.CMD_MENU_XML -> {
                reply(cmd, MENU_XML.toByteArray())
                reply(cmd)
            }
            GpsocketClient.CMD_MODE_400,
            GpsocketClient.CMD_FILE_SELECT,
            GpsocketClient.CMD_MEDIA_CLASS
            -> reply(cmd)
            GpsocketClient.CMD_DOWNLOAD -> {
                val data = files[u16(body, 0)]
                if (data == null) refuse(cmd) else stream(data)
            }
        }
    }

    override fun close() {
        runCatching { client?.close() }
        runCatching { server.close() }
    }

    companion object {
        const val CHUNK = 32 * 1024
        private const val LENGTH_PREFIXED = -1
        const val MENU_XML = "<Menu><Category><Name>Record</Name></Category></Menu>"

        /** Body size per command the app sends (see GpsocketClient's TX helpers). */
        private val ARG_BYTES = mapOf(
            GpsocketClient.CMD_STATUS_FLAGS to 1,
            GpsocketClient.CMD_SESSION_001 to 0,
            GpsocketClient.CMD_FILE_SELECT to 2,
            GpsocketClient.CMD_GET_PARAM to 4,
            GpsocketClient.CMD_TIMESYNC to LENGTH_PREFIXED,
            GpsocketClient.CMD_KEEPALIVE to 0,
            GpsocketClient.CMD_SET_PARAM to 6,
            GpsocketClient.CMD_MENU_XML to 0,
            GpsocketClient.CMD_FILE_COUNT to 0,
            GpsocketClient.CMD_FILE_LIST to 3,
            GpsocketClient.CMD_MODE_400 to 0,
            GpsocketClient.CMD_REC_QUERY_403 to 2,
            GpsocketClient.CMD_LOGIN to 4,
            GpsocketClient.CMD_DOWNLOAD to 2,
            GpsocketClient.CMD_MEDIA_CLASS to 1,
            GpsocketClient.CMD_AUTH_ROUND to 4,
            GpsocketClient.CMD_SESSION_800 to 1
        )

        fun frame(cmd: Int, payload: ByteArray, type: Int = 2): ByteArray =
            GpsocketClient.MAGIC + le16(type) + le16(cmd) + le16(payload.size) + payload

        fun le16(v: Int): ByteArray =
            ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

        fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }
}
