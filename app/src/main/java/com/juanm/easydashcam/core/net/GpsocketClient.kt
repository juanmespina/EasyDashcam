package com.juanm.easydashcam.core.net

import com.juanm.easydashcam.core.DebugLogger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.time.Duration
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Client for the camera's GPSOCKET protocol (TCP 8081 on 192.168.100.1).
 *
 * Wire format (little-endian), reverse-engineered from pcaps of the Viidure app:
 *
 *   | "GPSOCKET" (8 bytes magic) | type u16 | cmd u16 | body — shape depends on cmd |
 *
 * type: 1 = phone -> camera, 2 = camera -> phone (u16 len + payload),
 *       3 = camera -> phone short reply (fixed 2-byte arg, no len field).
 * Outgoing frames fall in three shapes observed on the wire:
 *   - bare      : header only (12 bytes total), e.g. cmd 0x0100 keepalive
 *   - fixed-arg : header + inline argument bytes, e.g. cmd 0x0004 param get (u32 param id),
 *                 cmd 0x0500 login (u32 random session token)
 *   - length    : header + u16 length + payload, e.g. cmd 0x00ff time sync
 * Incoming camera frames always use the length form (len may be 0).
 */
class GpsocketClient(private val host: String = CAMERA_HOST, private val port: Int = CAMERA_PORT) {
    companion object {
        const val CAMERA_HOST = "192.168.100.1"
        const val CAMERA_PORT = 8081
        val MAGIC = "GPSOCKET".toByteArray(Charsets.US_ASCII)

        // Outgoing frame type
        const val TYPE_REQUEST = 1

        // Command ids — verified against the labeled 2026-10-11 pcap.
        const val CMD_LOGIN = 0x0500 // + u32 random token; RX len=6 session key
        const val CMD_GET_PARAM = 0x0004 // + u32 param id; RX value (u8 enum, or string)
        const val CMD_SET_PARAM = 0x0104 // + param u16 | 00 00 | 0x01 | value u8; RX empty ack
        const val CMD_KEEPALIVE = 0x0100 // bare; RX 27-byte status blob
        const val CMD_MENU_XML = 0x0200 // bare; RX menu XML in ~498B chunks + empty terminator
        const val CMD_TIMESYNC = 0x00FF // len=17, "GPVENDOR\0\0" + datetime
        const val CMD_STATUS_FLAGS = 0x0000 // + u8 mode byte: 0x00 idle, 0x02 file list, 0x03 settings
        const val CMD_SESSION_001 = 0x0001 // bare session step
        const val CMD_MODE_400 = 0x0400 // bare; sent before the settings param refresh
        const val CMD_AUTH_ROUND = 0x0700 // + u32 challenge; RX len=4 response
        const val CMD_SESSION_800 = 0x0800 // + u8 0x00 session step

        const val MODE_IDLE = 0x00
        const val MODE_PREVIEW = 0x01 // hypothesis: enables the RTSP media push
        const val MODE_FILE_LIST = 0x02
        const val MODE_SETTINGS = 0x03
        const val CMD_FILE_COUNT = 0x0203 // bare; RX u16 total record count
        const val CMD_FILE_LIST = 0x0303 // start: len=1 payload 0x00; resume: 0x00+last id
        const val CMD_FILE_SELECT = 0x0003 // + u16 record id; selects a file before download
        const val CMD_MEDIA_CLASS = 0x0603 // + u8 record kind byte; download media class
        const val CMD_DOWNLOAD = 0x0503 // + u16 record id; RX 32KB chunks + len=0 terminator
        const val CMD_REC_QUERY_403 = 0x0403 // + u16 record id; RX arg f8ff — per-record probe (thumbnail?)

        const val FILE_PAGE_SIZE = 16
        const val FILE_RECORD_SIZE = 13

        private val INIT_PARAM_IDS = intArrayOf(0x9002, 0x9003, 0x9004, 0x9009, 0x0300)

        /** No 0x0503 frame for this long ends an aborted stream's drain. */
        private const val DRAIN_QUIET_MS = 1_500L

        /** A drain may take this long plus the rest of the file at [DRAIN_MIN_BYTES_PER_SEC]. */
        private const val DRAIN_BASE_MS = 30_000L
        private const val DRAIN_MIN_BYTES_PER_SEC = 100L * 1024

        /**
         * Split a camera byte stream into complete frames. Returns the frames
         * and the bytes to keep for the next read: an incomplete frame, or,
         * when no magic is found, the last 7 bytes in case the magic is split
         * across two TCP reads. Type-3 frames have a fixed 2-byte arg and no
         * length field; every other type carries a u16 length.
         */
        internal fun splitFrames(data: ByteArray): Pair<List<Frame>, ByteArray> {
            val frames = ArrayList<Frame>()
            var pos = 0
            var keepFrom = -1
            while (keepFrom < 0 && pos + 8 <= data.size) {
                val idx = findMagic(data, pos)
                val end = if (idx < 0) -1 else frameEnd(data, idx)
                when {
                    idx < 0 -> keepFrom = (data.size - 7).coerceAtLeast(pos)
                    end < 0 -> keepFrom = idx
                    else -> {
                        val type = u16le(data, idx + 8)
                        val payloadStart = if (type == 3) idx + 12 else idx + 14
                        frames += Frame(type, u16le(data, idx + 10), data.copyOfRange(payloadStart, end))
                        pos = end
                    }
                }
            }
            return frames to data.copyOfRange(if (keepFrom < 0) pos else keepFrom, data.size)
        }

        /** End offset of the frame whose magic is at [idx], or -1 while it is incomplete. */
        private fun frameEnd(data: ByteArray, idx: Int): Int {
            val end = when {
                data.size - idx < 14 -> -1
                u16le(data, idx + 8) == 3 -> idx + 14
                else -> idx + 14 + u16le(data, idx + 12)
            }
            return if (end > data.size) -1 else end
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

        private fun u32le(data: ByteArray, off: Int): Long = (data[off].toLong() and 0xFF) or
            ((data[off + 1].toLong() and 0xFF) shl 8) or
            ((data[off + 2].toLong() and 0xFF) shl 16) or
            ((data[off + 3].toLong() and 0xFF) shl 24)
    }

    data class Frame(val type: Int, val cmd: Int, val payload: ByteArray) {
        override fun equals(other: Any?) =
            other is Frame && type == other.type && cmd == other.cmd && payload.contentEquals(other.payload)
        override fun hashCode() = 31 * (31 * type + cmd) + payload.contentHashCode()
        override fun toString(): String = "type=$type cmd=0x%04x len=%d".format(cmd, payload.size)
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

    /** Serializes request/response exchanges — get-param replies are positional. */
    private val requestLock = Mutex()

    /** Serializes state emissions — unordered emits from connect/disconnect/
     *  the read-loop finally could otherwise leave a stale terminal state. */
    private val stateMutex = Mutex()

    /** Bumped per connection attempt and disconnect — a stale read-loop
     *  finally checks it before emitting Disconnected or closing fields. */
    private var socketGen = 0

    /** Last settings menu document received (init or on-demand request). */
    var lastMenuXml: String? = null
        private set

    suspend fun connect(host: String? = null) {
        stateMutex.withLock {
            if (_state.replayCache.firstOrNull() is State.Connecting ||
                _state.replayCache.firstOrNull() is State.Connected
            ) {
                return
            }
            _state.emit(State.Connecting)
        }
        val target = host ?: this.host
        try {
            withContext(Dispatchers.IO) {
                val s = Socket()
                s.tcpNoDelay = true
                // The camera's status blob arrives ~1/s; if nothing arrives for
                // 15 s the link is dead (a dropped hotspot never sends FIN).
                s.soTimeout = 15_000
                s.connect(InetSocketAddress(target, port), 5000)
                socket = s
                input = BufferedInputStream(s.getInputStream(), 65536)
                output = BufferedOutputStream(s.getOutputStream(), 8192)

                startReadLoop(++socketGen)
                runInitSequence()
            }
            startHeartbeat()
            stateMutex.withLock { _state.emit(State.Connected) }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Without this the state stays Connecting forever and every later
            // connect() early-returns.
            socketGen++
            closeQuietly()
            stateMutex.withLock { _state.emit(State.Disconnected) }
            throw ce
        } catch (t: Throwable) {
            socketGen++
            closeQuietly()
            stateMutex.withLock { _state.emit(State.Error(t)) }
        }
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        socketGen++ // a dying read loop must not clobber this emit
        closeQuietly()
        scope.launch { stateMutex.withLock { _state.emit(State.Disconnected) } }
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

    /** Blocking network write, always on the IO dispatcher, mutex-serialized. */
    private suspend fun rawWrite(bytes: ByteArray) {
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                val out = output ?: return@withContext
                out.write(bytes)
                out.flush()
            }
        }
    }

    private suspend fun writeBare(cmd: Int) {
        if (cmd != CMD_KEEPALIVE) {
            // heartbeats would drown the log buffer
            DebugLogger.log("GPSOCKET", "TX cmd=0x%04x bare".format(cmd))
        }
        val frame = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        frame.put(MAGIC)
        putU16(frame, TYPE_REQUEST)
        putU16(frame, cmd)
        rawWrite(frame.array())
    }

    private suspend fun writeArgs(cmd: Int, args: ByteArray) {
        DebugLogger.log(
            "GPSOCKET",
            "TX cmd=0x%04x args=%s".format(
                cmd,
                args.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            )
        )
        val frame = ByteBuffer.allocate(12 + args.size).order(ByteOrder.LITTLE_ENDIAN)
        frame.put(MAGIC)
        putU16(frame, TYPE_REQUEST)
        putU16(frame, cmd)
        frame.put(args)
        rawWrite(frame.array())
    }

    private suspend fun writePayload(cmd: Int, payload: ByteArray) {
        val frame = ByteBuffer.allocate(14 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        frame.put(MAGIC)
        putU16(frame, TYPE_REQUEST)
        putU16(frame, cmd)
        putU16(frame, payload.size)
        frame.put(payload)
        rawWrite(frame.array())
    }

    // ---- Public commands -----------------------------------------------------

    suspend fun sendLogin() {
        val token = ByteArray(4)
        SecureRandom().nextBytes(token)
        writeArgs(CMD_LOGIN, token)
    }

    suspend fun sendParamQuery(paramId: Int) {
        val args = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(paramId).array()
        writeArgs(CMD_GET_PARAM, args)
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

    private suspend fun sendAuthRound() {
        val token = ByteArray(4)
        SecureRandom().nextBytes(token)
        writeArgs(CMD_AUTH_ROUND, token)
    }

    // ---- Request/response exchanges -----------------------------------------
    // The reply collector is subscribed BEFORE the request is sent, so no reply
    // can be missed. requestLock serializes exchanges: get-param replies carry
    // only the value, so queries must stay positional and uninterleaved.

    /**
     * Send [send], then collect [count] camera frames for [cmd]. Returns fewer
     * than [count] frames on timeout.
     */
    private suspend fun exchange(cmd: Int, count: Int, timeoutMs: Long, send: suspend () -> Unit): List<Frame> {
        val ch = kotlinx.coroutines.channels.Channel<Frame>(count + 8)
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            _frames.collect { if (it.cmd == cmd) ch.trySend(it) }
        }
        return try {
            send()
            val out = ArrayList<Frame>(count)
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { repeat(count) { out += ch.receive() } }
            out
        } finally {
            job.cancel()
        }
    }

    // Viidure gates feature groups behind mode commands on cmd 0x0000
    // (+0x0400 for settings) — the camera ignores params/file ops in idle mode.
    private suspend fun enterMode(mode: Int) {
        writeArgs(CMD_STATUS_FLAGS, byteArrayOf(mode.toByte()))
        delay(50) // ack comes in ~10 ms; give the camera time to switch
    }

    /** Put the camera into preview mode so it starts pushing RTP media on the
     *  RTSP session. In the Viidure pcaps the camera answers RTSP PLAY with
     *  200 OK but sends zero media packets — mode 0x01 is the untested value. */
    suspend fun enterPreviewMode() {
        requestLock.withLock { enterMode(MODE_PREVIEW) }
    }

    /** Leave preview mode. The camera answers RTSP TEARDOWN with 501, so
     *  dropping to idle is what actually frees its one live session —
     *  otherwise the next SETUP races a stale session and gets a 404. */
    suspend fun exitPreviewMode() {
        requestLock.withLock { enterMode(MODE_IDLE) }
    }

    private suspend fun enterSettingsMode() {
        writeBare(CMD_MODE_400)
        delay(30)
        enterMode(MODE_SETTINGS)
    }

    /** Query current values for [paramIds]; replies are positional — send each
     *  query and await its reply before the next, like Viidure does. The camera
     *  drops back-to-back bursts. */
    suspend fun getParams(paramIds: List<Int>, timeoutMs: Long = 1000): Map<Int, ByteArray> = requestLock.withLock {
        enterSettingsMode()
        val out = LinkedHashMap<Int, ByteArray>(paramIds.size)
        for (id in paramIds) {
            val r = exchange(CMD_GET_PARAM, 1, timeoutMs) {
                writeArgs(
                    CMD_GET_PARAM,
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(id).array()
                )
            }
            // type-3 frames are error replies (feff/f8ff) — they echo the
            // request cmd, so a refusal would otherwise become the "value".
            out[id] = r.firstOrNull()?.takeIf { it.type == 2 }?.payload
                ?: ByteArray(0)
        }
        out
    }

    /** Set an enum/u8 setting. Body: param u16 LE | 00 00 | type 0x01 | value u8. */
    suspend fun setParam(paramId: Int, value: Int, timeoutMs: Long = 2000): Boolean = requestLock.withLock {
        enterSettingsMode()
        val args = byteArrayOf(
            (paramId and 0xFF).toByte(),
            (paramId shr 8 and 0xFF).toByte(),
            0,
            0,
            0x01,
            (value and 0xFF).toByte()
        )
        exchange(CMD_SET_PARAM, 1, timeoutMs) { writeArgs(CMD_SET_PARAM, args) }
            .any { it.type == 2 } // type-3 reply = refused, not an ack
    }

    /** Fetch the settings menu XML (chunks of ~498 B + empty terminator). */
    suspend fun requestMenuXml(timeoutMs: Long = 5000): String = requestLock.withLock {
        val ch = kotlinx.coroutines.channels.Channel<Frame>(64)
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            _frames.collect { if (it.cmd == CMD_MENU_XML) ch.trySend(it) }
        }
        try {
            writeBare(CMD_MENU_XML)
            val raw = ByteArrayOutputStream()
            kotlinx.coroutines.withTimeout(timeoutMs) {
                while (true) {
                    val f = ch.receive()
                    if (f.type == 3) throw java.io.IOException("menu request refused")
                    if (f.payload.isEmpty()) break
                    // Chunk boundaries can split multi-byte UTF-8 — decode
                    // once after the terminator, not per chunk.
                    raw.write(f.payload)
                }
            }
            val sb = String(raw.toByteArray(), Charsets.UTF_8)
            sb.also { lastMenuXml = it }
        } finally {
            job.cancel()
        }
    }

    /** Total record count the camera advertises (u16), or null on timeout. */
    suspend fun requestFileCount(timeoutMs: Long = 2000): Int? = requestLock.withLock {
        exchange(CMD_FILE_COUNT, 1, timeoutMs) { writeBare(CMD_FILE_COUNT) }
            .firstOrNull()?.takeIf { it.type == 2 }
            ?.let { if (it.payload.size >= 2) u16le(it.payload, 0) else null }
    }

    /**
     * Page through the file list. Each 0x0303 page starts with 0x10 and carries
     * up to [FILE_PAGE_SIZE] 13-byte records; the camera expects the last
     * record's id echoed back as 0x00+id u16 to continue. Ends on a short page, or
     * when the camera wraps: some firmware serves a circular list and starts
     * over at the first page instead of ever sending a short one.
     */
    suspend fun listFiles(maxPages: Int = 64, timeoutMs: Long = 2000): List<CameraFile> = requestLock.withLock {
        val ch = kotlinx.coroutines.channels.Channel<Frame>(maxPages + 8)
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            _frames.collect { if (it.cmd == CMD_FILE_LIST) ch.trySend(it) }
        }
        try {
            enterMode(MODE_FILE_LIST)
            val files = ArrayList<CameraFile>()
            val seen = HashSet<CameraFile>()
            // The camera drops a request now and then — resend the same
            // start/resume a couple of times instead of discarding the
            // whole list over one lost frame.
            var resume: ByteArray? = null
            var lastPageHead = -1
            var retries = 0
            var p = 0
            while (p < maxPages) {
                resume?.let { writeArgs(CMD_FILE_LIST, it) }
                    ?: writePayload(CMD_FILE_LIST, byteArrayOf(0))
                val page = try {
                    kotlinx.coroutines.withTimeout(timeoutMs) { ch.receive() }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    if (++retries <= 2) continue
                    throw java.io.IOException(
                        "file list: no reply after $retries requests"
                    )
                }
                // A type-3 error frame echoes the cmd — surface it instead
                // of letting an empty parse truncate the list silently.
                if (page.type == 3) throw java.io.IOException("file list refused by camera")
                val recs = parseFileListPage(page.payload)
                if (recs.isEmpty()) break
                // A resend after the camera already answered can queue a
                // second copy of the page — skip it or records duplicate.
                if (recs.first().id == lastPageHead) continue
                // The camera serves the list circularly, so a record already
                // seen means the page wrapped: keep its new records and stop.
                // A late stale copy of an older page also ends the list here
                // (accepted risk, rarer than the wrap).
                val fresh = recs.filter { seen.add(it) }
                files += fresh
                if (fresh.size < recs.size) break
                retries = 0
                p++
                if (recs.size < FILE_PAGE_SIZE) break
                lastPageHead = recs.first().id
                val last = recs.last().id
                resume = byteArrayOf(0, (last and 0xFF).toByte(), (last shr 8 and 0xFF).toByte())
            }
            // Parsed records — kind letters are the only media/channel
            // discriminator the protocol offers.
            DebugLogger.log(
                "FILES",
                "${files.size} records: " +
                    files.joinToString(" | ") { it.toString() }
            )
            files
        } finally {
            job.cancel()
        }
    }

    /** One file-list record: type tag, id, timestamp, size in KiB (verified:
     *  size*1024 == exact bytes streamed by a download). */
    data class CameraFile(val kind: Int, val id: Int, val timestamp: LocalDateTime?, val size: Long) {
        val kindChar: Char get() = kind.toChar()
        val sizeBytes: Long get() = size * 1024

        /** Record ids are NOT unique (photo+video pairs share one). The kind
         *  byte disambiguates — same reason the protocol has 0x0603. */
        val uniqueId: Int get() = (id shl 8) or kind

        /** The camera feff-refuses records it is still writing, so only a
         *  record stamped within the last few minutes can be one. */
        fun maybeStillRecording(now: LocalDateTime = LocalDateTime.now()): Boolean =
            timestamp?.let { Duration.between(it, now).abs().toMinutes() < STILL_RECORDING_MINUTES } == true
        override fun toString(): String = "%c id=0x%04x %s size=%dKiB".format(kindChar, id, timestamp ?: "?", size)

        private companion object {
            const val STILL_RECORDING_MINUTES = 3L
        }
    }

    sealed class DownloadResult {
        data class Success(val bytes: Long) : DownloadResult()

        /** Camera answered feff: file not selectable (stale id, or selection expired). */
        data object Refused : DownloadResult()
        data class Incomplete(val bytes: Long, val expected: Long) : DownloadResult()
        data class Failed(val reason: String) : DownloadResult()

        /** No camera session: nothing was sent. */
        data object NotConnected : DownloadResult()
    }

    /**
     * Download a recording. Verified wire sequence (Oct-6 pcap):
     *   0x0400 → 0x0003 +id → 0x0603 +kind → 0x0503 +id
     *   → camera streams len-prefixed 0x0503 chunks (~32 KiB) until a len=0
     *   terminator; total == record.size*1024. No per-chunk acks exist.
     * The steps must run back-to-back — a 24 s gap between select and request
     * made the camera answer feff in the capture. Payload is a RIFF AVI.
     */
    suspend fun downloadFile(
        file: CameraFile,
        out: java.io.OutputStream,
        timeoutMs: Long = 10_000,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }
    ): DownloadResult = requestLock.withLock {
        // Writes are silent no-ops without a socket; failing here saves the
        // 10 s select timeout per queued file.
        // disconnect() clears output at once but publishes its state from a
        // coroutine, so check both.
        if (output == null || _state.replayCache.firstOrNull() !is State.Connected) {
            return@withLock DownloadResult.NotConnected
        }
        heartbeatJob?.cancel() // Viidure sends no keepalives mid-transfer
        // UNLIMITED: trySend on a 64-slot channel silently dropped chunks
        // whenever the disk writer stalled ~1 s, ending transfers as
        // Incomplete. The camera streams one finite file, so backlog size
        // is bounded by file size — suspending send() would instead stall
        // the shared _frames collector and starve other commands.
        val ch = kotlinx.coroutines.channels.Channel<Frame>(
            kotlinx.coroutines.channels.Channel.UNLIMITED
        )
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            _frames.collect {
                if (it.cmd == CMD_FILE_SELECT || it.cmd == CMD_MEDIA_CLASS || it.cmd == CMD_DOWNLOAD) {
                    ch.trySend(it)
                }
            }
        }
        // Set once 0x0503 is sent and cleared when the camera's terminator
        // arrives: an aborted stream must be drained before the lock is
        // released, or its chunks land in the next download.
        var streamOpen = false
        var received = 0L
        try {
            val idArgs = byteArrayOf(
                (file.id and 0xFF).toByte(),
                (file.id shr 8 and 0xFF).toByte()
            )
            // Restore file mode — Live leaves the session in preview (0x01)
            // and Settings in 0x03, and the camera feffs 0x0003/0x0503 from
            // the wrong mode. listFiles used to leave 0x02 behind by luck.
            enterMode(MODE_FILE_LIST)
            writeBare(CMD_MODE_400)
            // Viidure paces these steps ~1 s apart; a 150 ms burst works on
            // some states but the camera feffs 0x0503 on others.
            delay(800)
            writeArgs(CMD_FILE_SELECT, idArgs)
            val sel = awaitCmd(ch, CMD_FILE_SELECT, timeoutMs)
            when {
                sel == null -> return@withLock DownloadResult.Failed("no select ack")
                sel.type == 3 -> return@withLock DownloadResult.Refused
            }
            delay(800)
            writeArgs(CMD_MEDIA_CLASS, byteArrayOf(file.kind.toByte()))
            val cls = awaitCmd(ch, CMD_MEDIA_CLASS, timeoutMs)
            when {
                cls == null -> return@withLock DownloadResult.Failed("no media-class ack")
                cls.type == 3 -> return@withLock DownloadResult.Refused
            }
            delay(800)
            while (ch.tryReceive().isSuccess) {
                // Stragglers from an earlier stream must not count as this file's bytes.
            }
            writeArgs(CMD_DOWNLOAD, idArgs)
            streamOpen = true

            val expected = file.sizeBytes
            var result: DownloadResult = DownloadResult.Incomplete(0, expected)
            while (true) {
                val f = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { ch.receive() } ?: break // stall -> Incomplete
                if (f.type == 3) { // feff arg = refused
                    result = DownloadResult.Refused
                    break
                }
                if (f.payload.isEmpty()) { // len=0 terminator
                    streamOpen = false
                    result = when {
                        received == 0L -> DownloadResult.Refused
                        received >= expected -> DownloadResult.Success(received)
                        // Short stream — a mid-transfer abort lands here.
                        else -> DownloadResult.Incomplete(received, expected)
                    }
                    break
                }
                if (received == 0L && !startsLikeMedia(f.payload)) {
                    result = DownloadResult.Failed("unexpected data")
                    break
                }
                out.write(f.payload)
                received += f.payload.size
                onProgress(received, expected)
                result = DownloadResult.Incomplete(received, expected)
                if (received >= expected) {
                    result = DownloadResult.Success(received)
                    break
                }
            }
            result
        } finally {
            if (streamOpen) drainAbortedStream(ch, file.sizeBytes - received)
            out.flush()
            job.cancel()
            if (_state.replayCache.firstOrNull() is State.Connected) startHeartbeat()
        }
    }

    /**
     * Discard the rest of a transfer the app stopped reading. The camera has
     * no abort command and keeps streaming the whole file (seconds for a
     * large clip), so wait for its empty terminator, or for a quiet gap.
     * NonCancellable: a cancelled caller still holds the lock until the
     * socket is clean.
     */
    private suspend fun drainAbortedStream(ch: kotlinx.coroutines.channels.Channel<Frame>, remainingBytes: Long) =
        withContext(kotlinx.coroutines.NonCancellable) {
            val startMs = System.currentTimeMillis()
            var bytes = 0L
            // Front-channel clips reach ~120 MB (~160 s at the measured
            // ~750 KB/s); a fixed cap would cut their tail into the next
            // transfer. The quiet gap still ends a stream that stops early.
            val capMs = DRAIN_BASE_MS + remainingBytes * 1000 / DRAIN_MIN_BYTES_PER_SEC
            kotlinx.coroutines.withTimeoutOrNull(capMs) {
                while (true) {
                    val f = kotlinx.coroutines.withTimeoutOrNull(DRAIN_QUIET_MS) { ch.receive() } ?: break
                    if (f.cmd != CMD_DOWNLOAD || f.type == 3) continue
                    if (f.payload.isEmpty()) break
                    bytes += f.payload.size
                }
            }
            if (bytes > 0) {
                DebugLogger.log(
                    "GPSOCKET",
                    "drained $bytes B of an aborted transfer in ${System.currentTimeMillis() - startMs} ms"
                )
            }
        }

    /** A recording starts with a RIFF (AVI) header or a JPEG SOI marker. */
    private fun startsLikeMedia(b: ByteArray): Boolean = b.size >= 4 &&
        (
            (
                b[0] == 'R'.code.toByte() &&
                    b[1] == 'I'.code.toByte() &&
                    b[2] == 'F'.code.toByte() &&
                    b[3] == 'F'.code.toByte()
                ) ||
                (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte())
            )

    private suspend fun awaitCmd(ch: kotlinx.coroutines.channels.Channel<Frame>, cmd: Int, timeoutMs: Long): Frame? =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            var f = ch.receive()
            while (f.cmd != cmd) f = ch.receive()
            f
        }

    fun parseFileListPage(payload: ByteArray): List<CameraFile> {
        if (payload.isEmpty() || payload[0] != 0x10.toByte()) return emptyList()
        val out = ArrayList<CameraFile>()
        var i = 1
        while (i + FILE_RECORD_SIZE <= payload.size) {
            val kind = payload[i].toInt() and 0xFF
            val id = u16le(payload, i + 1)
            val ts = runCatching {
                LocalDateTime.of(
                    2000 + (payload[i + 3].toInt() and 0xFF),
                    payload[i + 4].toInt() and 0xFF,
                    payload[i + 5].toInt() and 0xFF,
                    payload[i + 6].toInt() and 0xFF,
                    payload[i + 7].toInt() and 0xFF,
                    payload[i + 8].toInt() and 0xFF
                )
            }.getOrNull()
            out += CameraFile(kind, id, ts, u32le(payload, i + 9))
            i += FILE_RECORD_SIZE
        }
        return out
    }

    // ---- Session lifecycle -------------------------------------------------

    private suspend fun runInitSequence() {
        sendLogin()
        delay(600) // the login reply took ~550 ms in the pcap
        writeBare(CMD_KEEPALIVE)
        for (id in INIT_PARAM_IDS) {
            sendParamQuery(id)
            delay(20) // Viidure paces queries by awaiting each reply
        }
        // Also caches into lastMenuXml. A refused or slow menu must not
        // fail the session; cancellation of connect() must still propagate.
        try {
            requestMenuXml()
        } catch (_: java.io.IOException) {
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        }
        sendTimeSync()
        writeBare(CMD_KEEPALIVE)
        sendParamQuery(0x9006)
        sendAuthRound()
        delay(30)
        writeArgs(CMD_STATUS_FLAGS, byteArrayOf(MODE_IDLE.toByte()))
        writeBare(CMD_SESSION_001)
        writeArgs(CMD_SESSION_800, byteArrayOf(0x00))
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

    private fun startReadLoop(gen: Int) {
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
                // Only the current socket's loop may stop the heartbeat and
                // report the disconnect — a stale loop dying after a
                // reconnect would clobber the new session.
                if (gen == socketGen) {
                    heartbeatJob?.cancel()
                    heartbeatJob = null
                    stateMutex.withLock { _state.emit(State.Disconnected) }
                    closeQuietly()
                }
            }
        }
    }

    private suspend fun drainFrames(buf: ByteArrayOutputStream) {
        val (frames, rest) = splitFrames(buf.toByteArray())
        buf.reset()
        buf.write(rest)
        for (frame in frames) {
            _frames.emit(frame)
            logRx(frame)
        }
    }

    private fun logRx(frame: Frame) {
        // Bulk frames (32 KiB download chunks, ~1 Hz heartbeats) are data,
        // not signal — logging their full payload was ~2x the transfer's
        // cost in hex-string building alone. Cap the rest at 48 bytes.
        if (frame.type != 3 && (frame.cmd == CMD_KEEPALIVE || frame.cmd == CMD_DOWNLOAD)) return
        val hex = frame.payload.take(48).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        DebugLogger.log(
            "GPSOCKET",
            if (frame.type == 3) {
                "RX cmd=0x%04x type=3 arg=%s".format(frame.cmd, hex)
            } else {
                "RX cmd=0x%04x type=%d len=%d %s".format(frame.cmd, frame.type, frame.payload.size, hex)
            }
        )
    }
}
