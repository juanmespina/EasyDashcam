package com.juanm.easydashcam.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Minimal RIFF/AVI + Motion-JPEG player for downloaded camera clips.
 *
 * The camera writes AVI files whose video chunks are plain JPEGs, so playback
 * is demux (RIFF walk → 'movi' → '00dc'/'00db' chunks) + BitmapFactory per
 * frame. Photo payloads may not even be RIFF — they start with the JPEG SOI
 * marker directly, which is handled as a single-frame stream.
 */
class AviMjpegPlayer(private val open: () -> InputStream) {

    class AviException(msg: String) : Exception(msg)

    /** Decoded frame pacing comes from 'avih' dwMicroSecPerFrame. */
    suspend fun play(onFrame: (Bitmap) -> Unit) = withContext(Dispatchers.IO) {
        open().use { inp -> stream(inp, onFrame) }
        Unit
    }

    private suspend fun stream(inp: InputStream, onFrame: (Bitmap) -> Unit) {
        val magic = readN(inp, 4) ?: throw AviException("empty file")

        // Raw JPEG payload (photo records): no RIFF wrapper.
        if (magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte()) {
            val rest = magic + inp.readBytes()
            BitmapFactory.decodeByteArray(rest, 0, rest.size)?.let(onFrame)
                ?: throw AviException("cannot decode JPEG")
            return
        }
        if (!magic.contentEquals("RIFF".toByteArray())) throw AviException("not RIFF/AVI")
        val riffSize = u32le(readN(inp, 4)!!) + 8
        if (!readN(inp, 4)!!.contentEquals("AVI ".toByteArray())) throw AviException("not AVI")

        var usecPerFrame = 66_666L   // ~15 fps fallback
        var pos = 12L
        var inMovi = false
        var moviEnd = 0L

        while (pos < riffSize && coroutineContext.isActive) {
            val id = readN(inp, 4) ?: break
            val len = u32le(readN(inp, 4)!!)
            pos += 8
            val chunkEnd = pos + len + (len and 1)   // RIFF word-aligns odd sizes

            when {
                id.contentEquals("LIST".toByteArray()) -> {
                    val type = readN(inp, 4) ?: break
                    pos += 4
                    if (type.contentEquals("movi".toByteArray())) {
                        inMovi = true
                        moviEnd = chunkEnd
                    } else {
                        skip(inp, chunkEnd - pos); pos = chunkEnd
                    }
                }
                id.contentEquals("avih".toByteArray()) -> {
                    val body = readN(inp, len.toInt())!!
                    usecPerFrame = u32le(body, 0)
                    skipPad(inp, len); pos = chunkEnd
                }
                inMovi && isVideoChunk(id) -> {
                    val jpeg = readN(inp, len.toInt()) ?: break
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let(onFrame)
                    skipPad(inp, len); pos = chunkEnd
                    val wait = usecPerFrame / 1000
                    if (wait in 1..500) delay(wait)
                }
                else -> { skip(inp, chunkEnd - pos); pos = chunkEnd }
            }
            if (inMovi && pos >= moviEnd) break
        }
    }

    private fun isVideoChunk(id: ByteArray) =
        id[2] == 'd'.code.toByte() && (id[3] == 'c'.code.toByte() || id[3] == 'b'.code.toByte())

    private fun readN(inp: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = inp.read(b, got, n - got)
            if (r < 0) return null
            got += r
        }
        return b
    }

    private fun skip(inp: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = inp.skip(left)
            if (s <= 0) { if (inp.read() < 0) break } else left -= s
        }
    }

    private fun skipPad(inp: InputStream, len: Long) { if (len and 1 == 1L) skip(inp, 1) }

    private fun u32le(b: ByteArray, off: Int = 0): Long =
        (b[off].toLong() and 0xFF) or
            (b[off + 1].toLong() and 0xFF shl 8) or
            (b[off + 2].toLong() and 0xFF shl 16) or
            (b[off + 3].toLong() and 0xFF shl 24)
}
