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

    /** ms per frame, from 'strh'/'avih' (defaults to ~15 fps). */
    var frameMs = 66L
        private set

    /** Total clip duration in seconds, for the seek bar label. */
    val durationSec: Double get() = frames.size * frameMs / 1000.0

    /**
     * Scan the file once: reads chunk headers only, so a 60 MB clip indexes
     * in milliseconds. Throws [AviException] on unparseable input.
     */
    suspend fun index() = withContext(Dispatchers.IO) {
        ch.position(0)
        val magic = readBytes(4) ?: throw AviException("empty file")

        // Raw JPEG payload (photo records): no RIFF wrapper.
        if (magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte()) {
            frames = listOf(FrameRef(0, ch.size().toInt()))
            frameMs = 0
            return@withContext
        }
        if (!magic.contentEquals("RIFF".toByteArray())) throw AviException("not RIFF/AVI")
        val riffSize = u32le(readBytes(4)!!) + 8
        if (!readBytes(4)!!.contentEquals("AVI ".toByteArray())) throw AviException("not AVI")

        var usecPerFrame = 66_666L
        var pos = 12L
        var inMovi = false
        var moviEnd = 0L
        val refs = ArrayList<FrameRef>()

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
                    usecPerFrame = u32le(readBytes(4)!!)
                    pos = chunkEnd
                }
                id.contentEquals("strh".toByteArray()) -> {
                    // dwScale/dwRate → frame interval (more reliable than avih)
                    if (len >= 28) {
                        val body = readBytes(28)!!
                        val scale = u32le(body, 20); val rate = u32le(body, 24)
                        if (rate > 0) usecPerFrame = scale * 1_000_000 / rate
                    }
                    pos = chunkEnd
                }
                inMovi && isVideoChunk(id) -> {
                    refs += FrameRef(pos, len.toInt())
                    pos = chunkEnd
                }
                else -> pos = chunkEnd
            }
            if (inMovi && pos >= moviEnd) break
        }
        if (refs.isEmpty()) throw AviException("no video frames in file")
        frameMs = (usecPerFrame / 1000).coerceIn(1, 500)
        frames = refs
    }

    /** Decode frame [i]; null when the JPEG is corrupt. Safe to call from IO. */
    suspend fun frameAt(i: Int): Bitmap? = withContext(Dispatchers.IO) {
        val ref = frames.getOrNull(i) ?: return@withContext null
        ch.position(ref.offset)
        val jpeg = readBytes(ref.length) ?: return@withContext null
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    }

    override fun close() = runCatching { ch.close() }.let { }

    private fun isVideoChunk(id: ByteArray) =
        id[2] == 'd'.code.toByte() && (id[3] == 'c'.code.toByte() || id[3] == 'b'.code.toByte())

    private fun readBytes(n: Int): ByteArray? {
        val buf = ByteBuffer.allocate(n)
        while (buf.hasRemaining()) if (ch.read(buf) < 0) return null
        return buf.array()
    }

    private fun u32le(b: ByteArray, off: Int = 0): Long =
        ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFF
}
