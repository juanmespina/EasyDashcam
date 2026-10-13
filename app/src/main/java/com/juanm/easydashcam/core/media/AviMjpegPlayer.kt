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

    /**
     * Scan the file once: reads chunk headers only, so a 60 MB clip indexes
     * in milliseconds. Throws [AviException] on unparseable input.
     */
    suspend fun index() = indexWalk(fullIndex = true)

    /**
     * Cheap header pass — avih duration + first frame reference only, no
     * 'movi' walk. Used to build list thumbnails without indexing every chunk.
     */
    suspend fun probe() = indexWalk(fullIndex = false)

    private suspend fun indexWalk(fullIndex: Boolean) = withContext(Dispatchers.IO) {
        ch.position(0)
        val magic = readBytes(4) ?: throw AviException("empty file")

        // Raw JPEG payload (photo records): no RIFF wrapper.
        if (magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte()) {
            thumbFrame = FrameRef(0, ch.size().toInt())
            if (fullIndex) { frames = listOf(thumbFrame!!); frameMs = 0 }
            return@withContext
        }
        if (!magic.contentEquals("RIFF".toByteArray())) throw AviException("not RIFF/AVI")
        val riffSize = u32le(readBytes(4)!!) + 8
        if (!readBytes(4)!!.contentEquals("AVI ".toByteArray())) throw AviException("not AVI")

        var usecPerFrame = 66_666L
        var totalFrames = 0L
        var pos = 12L
        var inMovi = false
        var moviEnd = 0L
        var streamIsAudio = false
        val refs = ArrayList<FrameRef>()
        val audio = ArrayList<FrameRef>()

        while (pos < riffSize) {
            ch.position(pos)
            val id = readBytes(4) ?: break
            val len = u32le(readBytes(4)!!)
            pos += 8
            val chunkEnd = pos + len + (len and 1)   // RIFF word-aligns odd sizes

            when {
                id.contentEquals("LIST".toByteArray()) -> {
                    val type = readBytes(4) ?: break
                    pos += 4
                    if (type.contentEquals("movi".toByteArray())) {
                        inMovi = true
                        moviEnd = chunkEnd
                    }
                    // Other lists (hdrl, strl, INFO) are descended into — their
                    // chunks hit the normal fourcc handlers below.
                }
                id.contentEquals("avih".toByteArray()) -> {
                    // dwMicroSecPerFrame@0 … dwTotalFrames@16
                    val body = readBytes(len.coerceAtMost(56).toInt())
                    if (body != null) {
                        usecPerFrame = u32le(body, 0)
                        if (body.size >= 20) totalFrames = u32le(body, 16)
                    }
                    pos = chunkEnd
                }
                id.contentEquals("strh".toByteArray()) -> {
                    // fccType@0 tells 'vids' from 'auds' — an audio stream's
                    // dwScale/dwRate must NOT overwrite video timing.
                    val body = readBytes(len.coerceAtMost(64).toInt())
                    streamIsAudio = body != null &&
                        body.copyOfRange(0, 4).contentEquals("auds".toByteArray())
                    if (body != null && !streamIsAudio && len >= 28) {
                        val scale = u32le(body, 20); val rate = u32le(body, 24)
                        if (rate > 0) usecPerFrame = scale * 1_000_000 / rate
                    }
                    pos = chunkEnd
                }
                // WAVEFORMATEX: tag@0 | channels@2 | rate@4 | avgBps@8
                id.contentEquals("strf".toByteArray()) -> {
                    val body = if (len >= 10) readBytes(len.coerceAtMost(16).toInt()) else null
                    if (streamIsAudio && body != null) {
                        val tag = (body[0].toInt() and 0xFF) or (body[1].toInt() and 0xFF shl 8)
                        if (tag == 1) {   // PCM — anything else can't go to AudioTrack
                            audioChannels = body[2].toInt() and 0xFF
                            audioRate = u32le(body, 4).toInt()
                            audioBytesPerSec = u32le(body, 8)
                        }
                    }
                    pos = chunkEnd
                }
                inMovi && isVideoChunk(id) -> {
                    refs += FrameRef(pos, len.toInt())
                    pos = chunkEnd
                    if (!fullIndex) break   // probe: first frame is enough
                }
                inMovi && isAudioChunk(id) -> {
                    audio += FrameRef(pos, len.toInt())
                    pos = chunkEnd
                }
                else -> pos = chunkEnd
            }
            if (inMovi && pos >= moviEnd) break
        }
        thumbFrame = refs.firstOrNull()
        if (totalFrames > 0) probeDurationSec = totalFrames * usecPerFrame / 1e6
        if (!fullIndex) return@withContext
        if (refs.isEmpty()) throw AviException("no video frames in file")
        frameMs = (usecPerFrame / 1000).coerceIn(1, 500)
        frames = refs
        audioRefs = audio
        audioStarts = LongArray(audio.size)
        var acc = 0L
        audio.forEachIndexed { i, r -> audioStarts[i] = acc; acc += r.length }
        audioByteLen = acc
    }

    /** Decode frame [i]; null when the JPEG is corrupt. Safe to call from IO. */
    suspend fun frameAt(i: Int): Bitmap? = withContext(Dispatchers.IO) {
        val ref = frames.getOrNull(i) ?: return@withContext null
        ch.position(ref.offset)
        val jpeg = readBytes(ref.length) ?: return@withContext null
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    }

    /** First frame downsampled to ~[maxDim] px — for grid thumbnails. */
    suspend fun thumbnail(maxDim: Int): Bitmap? = withContext(Dispatchers.IO) {
        val ref = thumbFrame ?: frames.firstOrNull() ?: return@withContext null
        ch.position(ref.offset)
        val jpeg = readBytes(ref.length) ?: return@withContext null
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
            ch.position(ref.offset + skip)
            val data = readBytes(n) ?: return@withContext -1
            System.arraycopy(data, 0, out, off, n)
            n
        }

    override fun close() = runCatching { ch.close() }.let { }

    private fun isVideoChunk(id: ByteArray) =
        id[2] == 'd'.code.toByte() && (id[3] == 'c'.code.toByte() || id[3] == 'b'.code.toByte())

    private fun isAudioChunk(id: ByteArray) =
        id[2] == 'w'.code.toByte() && id[3] == 'b'.code.toByte()

    private fun readBytes(n: Int): ByteArray? {
        val buf = ByteBuffer.allocate(n)
        while (buf.hasRemaining()) if (ch.read(buf) < 0) return null
        return buf.array()
    }

    private fun u32le(b: ByteArray, off: Int = 0): Long =
        ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFF
}
