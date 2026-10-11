package com.juanm.easydashcam.core.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.time.LocalDateTime

/**
 * Client for the camera's GPSOCKET protocol (TCP 8081 on 192.168.100.1).
 *
 * Wire format (little-endian), reverse-engineered from pcaps of the Viidure app:
 *
 *   | "GPSOCKET" (8 bytes magic) | type u16 | cmd u16 | [len u16 | payload (len bytes)] |
 *
 * type: 1 = phone -> camera, 2 = camera -> phone, 3 = bulk-transfer marker.
 * Outgoing frames fall in three shapes observed on the wire:
 *   - bare      : header only (12 bytes total), e.g. cmd 0x0001 keepalive
 *   - fixed-arg : header + inline argument bytes, e.g. cmd 0x0004 param query (u32 param id),
 *                 cmd 0x0500 login (u32 random session token)
 *   - length    : header + u16 length + payload, e.g. cmd 0x00ff time sync
 * Incoming camera frames always use the length form.
 */
class GpsocketClient(
    private val host: String = CAMERA_HOST,
    private val port: Int = CAMERA_PORT
) {
    companion object {
        const val CAMERA_HOST = "192.168.100.1"
        const val CAMERA_PORT = 8081
        val MAGIC = "GPSOCKET".toByteArray(Charsets.US_ASCII)

        // Outgoing frame type
        const val TYPE_REQUEST = 1

        // Command ids seen on the wire (payloads documented where known)
        const val CMD_KEEPALIVE = 0x0001      // bare, sent constantly by Viidure
        const val CMD_LOGIN = 0x0500          // + u32 random token
        const val CMD_PARAM_QUERY = 0x0004    // + u32 param id, e.g. 0x9002/0x9003/0x9004/0x9009/0x0300
        const val CMD_PRE_TIMESYNC = 0x0002   // bare, sent right before time sync
        const val CMD_TIMESYNC = 0x00FF       // len=17, "GPVENDOR\0\0" + datetime
        const val CMD_STATUS_FLAGS = 0x0000   // + u8 flag byte (0x00/0x02/0x03 seen)
        const val CMD_UNKNOWN_8 = 0x0008      // + u8 0x00
        const val CMD_UNKNOWN_100 = 0x0100    // bare
        const val CMD_UNKNOWN_302 = 0x0302    // bare
        const val CMD_HEARTBEAT = 0x0303      // status exchange; camera replies 209-byte blob

        private val INIT_PARAM_IDS = intArrayOf(0x9002, 0x9003, 0x9004, 0x9009, 0x0300, 0x9006)
    }

    data class Frame(val type: Int, val cmd: Int, val payload: ByteArray) {
        override fun equals(other: Any?) =
            other is Frame && type == other.type && cmd == other.cmd && payload.contentEquals(other.payload)
        override fun hashCode() = 31 * (31 * type + cmd) + payload.contentHashCode()
        override fun toString(): String =
            "type=$type cmd=0x%04x len=%d".format(cmd, payload.size)
    }

    sealed class State {
        data object Disconnected : State()
        data object Connecting : State()
        data object Connected : State()
        data class Error(val cause: Throwable) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private val writeLock = Mutex()

    private val _frames = MutableSharedFlow<Frame>(extraBufferCapacity = 256)
    val frames: SharedFlow<Frame> = _frames

    private val _state = MutableSharedFlow<State>(replay = 1)
    val state: SharedFlow<State> = _state

    private var heartbeatJob: Job? = null

    suspend fun connect() {
        if (_state.replayCache.firstOrNull() is State.Connecting ||
            _state.replayCache.firstOrNull() is State.Connected
        ) return
        _state.emit(State.Connecting)
        try {
            withContext(Dispatchers.IO) {
                val s = Socket()
                s.tcpNoDelay = true
                s.soTimeout = 0
                s.connect(InetSocketAddress(host, port), 5000)
                socket = s
                input = BufferedInputStream(s.getInputStream(), 65536)
                output = BufferedOutputStream(s.getOutputStream(), 8192)

                startReadLoop()
                runInitSequence()
            }
            startHeartbeat()
            _state.emit(State.Connected)
        } catch (t: Throwable) {
            closeQuietly()
            _state.emit(State.Error(t))
        }
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        closeQuietly()
        scope.launch { _state.emit(State.Disconnected) }
    }

    private fun closeQuietly() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    // ---- Write helpers -----------------------------------------------------

    private fun putU16(buf: ByteBuffer, v: Int) {
        buf.putShort(v.toShort())
    }

    private suspend fun writeBare(cmd: Int) {
        writeLock.withLock {
            val out = output ?: return
            val frame = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            frame.put(MAGIC)
            putU16(frame, TYPE_REQUEST)
            putU16(frame, cmd)
            out.write(frame.array())
            out.flush()
        }
    }

    private suspend fun writeArgs(cmd: Int, args: ByteArray) {
        writeLock.withLock {
            val out = output ?: return
            val frame = ByteBuffer.allocate(12 + args.size).order(ByteOrder.LITTLE_ENDIAN)
            frame.put(MAGIC)
            putU16(frame, TYPE_REQUEST)
            putU16(frame, cmd)
            frame.put(args)
            out.write(frame.array())
            out.flush()
        }
    }

    private suspend fun writePayload(cmd: Int, payload: ByteArray) {
        writeLock.withLock {
            val out = output ?: return
            val frame = ByteBuffer.allocate(14 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            frame.put(MAGIC)
            putU16(frame, TYPE_REQUEST)
            putU16(frame, cmd)
            putU16(frame, payload.size)
            frame.put(payload)
            out.write(frame.array())
            out.flush()
        }
    }

    // ---- Public commands -----------------------------------------------------

    suspend fun sendLogin() {
        val token = ByteArray(4)
        SecureRandom().nextBytes(token)
        writeArgs(CMD_LOGIN, token)
    }

    suspend fun sendParamQuery(paramId: Int) {
        val args = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(paramId).array()
        writeArgs(CMD_PARAM_QUERY, args)
    }

    suspend fun sendTimeSync(now: LocalDateTime = LocalDateTime.now()) {
        val payload = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        payload.put("GPVENDOR".toByteArray(Charsets.US_ASCII))
        payload.put(0)
        payload.put(0)
        payload.putShort(now.year.toShort())
        payload.put(now.monthValue.toByte())
        payload.put(now.dayOfMonth.toByte())
        payload.put(now.hour.toByte())
        payload.put(now.minute.toByte())
        payload.put(now.second.toByte())
        writePayload(CMD_TIMESYNC, payload.array())
    }

    suspend fun sendHeartbeatStatusQuery() = writeBare(CMD_HEARTBEAT)

    // ---- Session lifecycle -------------------------------------------------

    private suspend fun runInitSequence() {
        sendLogin()
        for (id in INIT_PARAM_IDS) sendParamQuery(id)
        writeBare(CMD_PRE_TIMESYNC)
        sendTimeSync()
        writeBare(CMD_KEEPALIVE)
        writeArgs(CMD_STATUS_FLAGS, byteArrayOf(0x00))
        writeArgs(CMD_UNKNOWN_8, byteArrayOf(0x00))
        writeBare(CMD_PARAM_QUERY)
        writeBare(CMD_UNKNOWN_100)
        writeArgs(CMD_STATUS_FLAGS, byteArrayOf(0x02))
        writeBare(CMD_UNKNOWN_302)
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                runCatching { writeBare(CMD_KEEPALIVE) }
                delay(800)
            }
        }
    }

    // ---- Read loop ---------------------------------------------------------

    private fun startReadLoop() {
        val ins = input ?: return
        scope.launch {
            val streamBuf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(65536)
            try {
                while (isActive) {
                    val n = ins.read(chunk)
                    if (n < 0) break
                    streamBuf.write(chunk, 0, n)
                    drainFrames(streamBuf)
                }
            } catch (_: Throwable) {
                // socket closed
            } finally {
                _state.emit(State.Disconnected)
                closeQuietly()
            }
        }
    }

    private suspend fun drainFrames(buf: ByteArrayOutputStream) {
        val data = buf.toByteArray()
        var pos = 0
        while (pos + 8 <= data.size) {
            val idx = findMagic(data, pos)
            if (idx < 0) {
                // Drop everything except the last 7 bytes, in case the magic
                // is split across two TCP reads.
                val keepFrom = (data.size - 7).coerceAtLeast(0)
                buf.reset()
                buf.write(data, keepFrom, data.size - keepFrom)
                return
            }
            if (data.size - idx < 14) {
                buf.reset()
                buf.write(data, idx, data.size - idx)
                return
            }
            val type = u16le(data, idx + 8)
            val cmd = u16le(data, idx + 10)
            val len = u16le(data, idx + 12)
            if (data.size - idx < 14 + len) {
                buf.reset()
                buf.write(data, idx, data.size - idx)
                return
            }
            _frames.emit(Frame(type, cmd, data.copyOfRange(idx + 14, idx + 14 + len)))
            pos = idx + 14 + len
        }
        buf.reset()
        buf.write(data, pos, data.size - pos)
    }

    private fun findMagic(data: ByteArray, from: Int): Int {
        outer@ for (i in maxOf(from, 0)..data.size - 8) {
            for (j in 0..7) if (data[i + j] != MAGIC[j]) continue@outer
            return i
        }
        return -1
    }

    private fun u16le(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
}
