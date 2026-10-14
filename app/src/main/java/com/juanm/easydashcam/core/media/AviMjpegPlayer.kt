package com.juanm.easydashcam.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Minimal RIFF/AVI + Motion-JPEG player for downloaded camera clips.
 *
 * The camera writes AVI files whose video chunks are plain JPEGs, so playback
 * is demux (RIFF walk → 'movi' → '00dc'/'00db' chunks) + BitmapFactory per
 * frame. A one-time index pass records every frame's file offset, which makes
 * seeking instant. Photo payloads may not even be RIFF — they start with the
 * JPEG SOI marker directly and are exposed as a single-frame clip.
 */
class AviMjpegPlayer(private val ch: FileChannel) : Closeable {

    class AviException(msg: String) : Exception(msg)

    /** Byte range of one JPEG frame inside the file. */
    data class FrameRef(val offset: Long, val length: Int)

    /** Seek targets for every video chunk, in order. */
    var frames: List<FrameRef> = emptyList()
        private set

    /** Byte ranges of the audio stream's chunks ('xxwb'), in order. */
    var audioRefs: List<FrameRef> = emptyList()
        private set

    /** Cumulative byte offset of each audio chunk in the PCM stream. */
    private var audioStarts: LongArray = longArrayOf()

    /** WAVEFORMATEX fields from the audio stream's 'strf'. */
    var audioRate = 0
        private set
    var audioChannels = 0
        private set
    var audioBytesPerSec = 0L
        private set
    var audioByteLen = 0L
        private set
    val hasAudio: Boolean get() = audioRefs.isNotEmpty() && audioBytesPerSec > 0

    /** ms per frame, from 'strh'/'avih' (defaults to ~15 fps). */
    var frameMs = 66L
        private set

    /** Total clip duration in seconds, for the seek bar label. */
    val durationSec: Double get() = frames.size * frameMs / 1000.0

    /** Duration from the avih header (total frames × frame interval), filled
     *  by a probe pass — no full index needed. */
    var probeDurationSec: Double? = null
        private set

    /** Byte range of the first video frame — filled by probe/index. */
    var thumbFrame: FrameRef? = null
        private set

    /** True once the scan reached the end of the 'movi' list. */
    var fullyIndexed = false
        private set

    // Index state lives on the instance (not the scan call) so a growing
    // file — a clip being streamed over the socket — resumes scanning where
    // the previous pass ran out of data instead of rescanning from byte 0.
    private var scanPos = 0L
    private var riffSize = Long.MAX_VALUE
    private var inMovi = false
    private var moviEnd = Long.MAX_VALUE
    private var streamIsAudio = false
    private var usecPerFrame = 66_666L
    private var totalFrames = 0L
    private var headerDone = false
    private val videoRefs = ArrayList<FrameRef>()
    private val audioRefsFound = ArrayList<FrameRef>()

    /**
     * Scan the file once: reads chunk headers only, so a 60 MB clip indexes
     * in milliseconds. Throws [AviException] on unparseable input.
     */
    suspend fun index() {
        scan(stopAfterFirstFrame = false)
        if (frames.isEmpty()) throw AviException("no video frames in file")
    }

    /**
     * Cheap header pass — avih duration + first frame reference only, no
     * 'movi' walk. Used to build list thumbnails without indexing every chunk.
     */
    suspend fun probe() {
        scan(stopAfterFirstFrame = true)
    }

    /**
     * Streaming playback: the file grows while it downloads. Resumes the
     * scan at the last complete chunk and indexes whatever has arrived —
     * returns the number of new video frames found. A partially written
     * tail chunk is left for the next call.
     */
    suspend fun extendIndex(): Int = scan(stopAfterFirstFrame = false)

    private suspend fun scan(stopAfterFirstFrame: Boolean): Int = withContext(Dispatchers.IO) {
        val size = ch.size()
        if (!headerDone) {
            if (size < 4) return@withContext 0   // magic still in flight
            val magic = readBytesAt(0, 4) ?: return@withContext 0

            // Raw JPEG payload (photo records): no RIFF wrapper.
            if (magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte()) {
                thumbFrame = FrameRef(0, size.toInt())
                if (!stopAfterFirstFrame) { frames = listOf(thumbFrame!!); frameMs = 0 }
                headerDone = true
                fullyIndexed = true
                return@withContext 1
            }
            if (!magic.contentEquals("RIFF".toByteArray())) throw AviException("not RIFF/AVI")
            if (size < 12) return@withContext 0   // rest of RIFF header in flight
            riffSize = u32le(readBytesAt(4, 4) ?: return@withContext 0) + 8
            if (!(readBytesAt(8, 4) ?: return@withContext 0)
                    .contentEquals("AVI ".toByteArray())) throw AviException("not AVI")
            scanPos = 12
            headerDone = true
        }

        var added = 0
        while (scanPos + 8 <= minOf(riffSize, size)) {
            val hdr = readBytesAt(scanPos, 8) ?: break
            val id = hdr.copyOfRange(0, 4)
            val len = u32le(hdr, 4)
            val chunkEnd = scanPos + 8 + len + (len and 1)   // RIFF word-aligns odd sizes
            val content = scanPos + 8
            val isList = id.contentEquals("LIST".toByteArray())
            // A LIST only needs its 4-byte subtype present — the walk
            // descends and stops at the incomplete tail by itself. movi
            // spans nearly the whole file, so requiring its full body here
            // would block indexing until the download is ~100% done.
            if (chunkEnd > size && !(isList && content + 4 <= size)) break

            when {
                isList -> {
                    val type = if (len >= 4) readBytesAt(content, 4) else null
                    scanPos = content + 4
                    if (type != null && type.contentEquals("movi".toByteArray())) {
                        inMovi = true
                        moviEnd = chunkEnd
                    }
                    // Other lists (hdrl, strl, INFO) are descended into — their
                    // chunks hit the normal fourcc handlers below.
                }
                id.contentEquals("avih".toByteArray()) -> {
                    // dwMicroSecPerFrame@0 … dwTotalFrames@16
                    val body = readBytesAt(content, len.coerceAtMost(56).toInt())
                    if (body != null) {
                        usecPerFrame = u32le(body, 0)
                        if (body.size >= 20) totalFrames = u32le(body, 16)
                    }
                    scanPos = chunkEnd
                }
                id.contentEquals("strh".toByteArray()) -> {
                    // fccType@0 tells 'vids' from 'auds' — an audio stream's
                    // dwScale/dwRate must NOT overwrite video timing.
                    val body = readBytesAt(content, len.coerceAtMost(64).toInt())
                    streamIsAudio = body != null &&
                        body.copyOfRange(0, 4).contentEquals("auds".toByteArray())
                    if (body != null && !streamIsAudio && len >= 28) {
                        val scale = u32le(body, 20); val rate = u32le(body, 24)
                        if (rate > 0) usecPerFrame = scale * 1_000_000 / rate
                    }
                    scanPos = chunkEnd
                }
                // WAVEFORMATEX: tag@0 | channels@2 | rate@4 | avgBps@8
                id.contentEquals("strf".toByteArray()) -> {
                    val body = if (len >= 10) readBytesAt(content, len.coerceAtMost(16).toInt()) else null
                    if (streamIsAudio && body != null) {
                        val tag = (body[0].toInt() and 0xFF) or (body[1].toInt() and 0xFF shl 8)
                        if (tag == 1) {   // PCM — anything else can't go to AudioTrack
                            audioChannels = body[2].toInt() and 0xFF
                            audioRate = u32le(body, 4).toInt()
                            audioBytesPerSec = u32le(body, 8)
                        }
                    }
                    scanPos = chunkEnd
                }
                inMovi && isVideoChunk(id) -> {
                    videoRefs += FrameRef(content, len.toInt())
                    added++
                    scanPos = chunkEnd
                    if (stopAfterFirstFrame) break   // probe: first frame is enough
                }
                inMovi && isAudioChunk(id) -> {
                    audioRefsFound += FrameRef(content, len.toInt())
                    scanPos = chunkEnd
                }
                else -> scanPos = chunkEnd
            }
            if (inMovi && scanPos >= moviEnd) { fullyIndexed = true; break }
        }
        if (scanPos >= riffSize) fullyIndexed = true
        if (!stopAfterFirstFrame) {
            // Bind the live lists — during streaming they keep growing, so
            // readers must see the same object rather than a snapshot.
            frames = videoRefs
            audioRefs = audioRefsFound
        }
        if (thumbFrame == null) thumbFrame = videoRefs.firstOrNull()
        if (totalFrames > 0) probeDurationSec = totalFrames * usecPerFrame / 1e6
        if (videoRefs.isNotEmpty()) frameMs = (usecPerFrame / 1000).coerceIn(1, 500)
        // Cumulative audio offsets — rebuilt only when the audio index grew.
        if (audioStarts.size != audioRefsFound.size) {
            audioStarts = LongArray(audioRefsFound.size)
            var acc = 0L
            audioRefsFound.forEachIndexed { i, r -> audioStarts[i] = acc; acc += r.length }
            audioByteLen = acc
        }
        added
    }

    /** Decode frame [i]; null when the JPEG is corrupt. Safe to call from IO. */
    suspend fun frameAt(i: Int): Bitmap? = withContext(Dispatchers.IO) {
        val ref = frames.getOrNull(i) ?: return@withContext null
        val jpeg = readBytesAt(ref.offset, ref.length) ?: return@withContext null
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    }

    /** First frame downsampled to ~[maxDim] px — for grid thumbnails. */
    suspend fun thumbnail(maxDim: Int): Bitmap? = withContext(Dispatchers.IO) {
        val ref = thumbFrame ?: frames.firstOrNull() ?: return@withContext null
        val jpeg = readBytesAt(ref.offset, ref.length) ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Byte offset in the concatenated PCM stream for time [ms] — PCM is
     *  constant-rate, so seeking is linear. */
    fun audioPosForMs(ms: Long): Long =
        (ms * audioBytesPerSec / 1000).coerceIn(0, audioByteLen)

    /**
     * Read up to [len] bytes of PCM at stream offset [pos] into [out].
     * Returns the byte count, or -1 at end of audio. Chunk boundaries are
     * meaningless for PCM, but the chunks aren't contiguous in the file so a
     * read never crosses one — the caller loops.
     */
    suspend fun audioRead(pos: Long, out: ByteArray, off: Int = 0, len: Int = out.size): Int =
        withContext(Dispatchers.IO) {
            if (!hasAudio || pos < 0 || pos >= audioByteLen) return@withContext -1
            var lo = 0
            var hi = audioStarts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (audioStarts[mid] <= pos) lo = mid else hi = mid - 1
            }
            val ref = audioRefs[lo]
            val skip = (pos - audioStarts[lo]).toInt()
            if (skip >= ref.length) return@withContext -1
            val n = minOf(len, ref.length - skip)
            val data = readBytesAt(ref.offset + skip, n) ?: return@withContext -1
            System.arraycopy(data, 0, out, off, n)
            n
        }

    override fun close() = runCatching { ch.close() }.let { }

    private fun isVideoChunk(id: ByteArray) =
        id[2] == 'd'.code.toByte() && (id[3] == 'c'.code.toByte() || id[3] == 'b'.code.toByte())

    private fun isAudioChunk(id: ByteArray) =
        id[2] == 'w'.code.toByte() && id[3] == 'b'.code.toByte()

    /** Positional read — doesn't touch the channel's file position, so the
     *  video decode loop and the audio feeder can share [ch] without a lock.
     *  A close() racing an in-flight read shows up as an IOException —
     *  callers treat it as "no data". */
    private fun readBytesAt(offset: Long, n: Int): ByteArray? {
        val buf = ByteBuffer.allocate(n)
        var pos = offset
        try {
            while (buf.hasRemaining()) {
                val r = ch.read(buf, pos)
                if (r < 0) return null
                pos += r
            }
        } catch (_: java.io.IOException) {
            return null
        }
        return buf.array()
    }

    private fun u32le(b: ByteArray, off: Int = 0): Long =
        ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFF
}
