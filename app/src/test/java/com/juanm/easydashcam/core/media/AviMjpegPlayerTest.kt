package com.juanm.easydashcam.core.media

import com.juanm.easydashcam.core.media.TestAvi.buildAvi
import com.juanm.easydashcam.core.media.TestAvi.jpeg
import com.juanm.easydashcam.core.media.TestAvi.playerFor
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AviMjpegPlayerTest {

    // ---- tests -----------------------------------------------------------------

    @Test
    fun `indexes every video chunk and skips audio`() = runBlocking {
        val p = playerFor(buildAvi(frames = listOf(jpeg(1), jpeg(2), jpeg(3))))
        p.index()
        assertEquals(3, p.frames.size)
        assert(p.frames.zipWithNext().all { (a, b) -> b.offset > a.offset + a.length })
        p.close()
    }

    @Test
    fun `strh rate wins over avih`() = runBlocking {
        val p = playerFor(
            buildAvi(
                avihUsec = 200_000,
                strhScale = 1,
                strhRate = 30,
                frames = listOf(jpeg(1))
            )
        )
        p.index()
        assertEquals(33L, p.frameMs) // 1/30 s, not avih's 200 ms
        p.close()
    }

    @Test
    fun `avih used when strh has no rate`() = runBlocking {
        val p = playerFor(buildAvi(avihUsec = 200_000, frames = listOf(jpeg(1))))
        p.index()
        assertEquals(200L, p.frameMs)
        p.close()
    }

    @Test
    fun `odd-size chunk keeps the next frame aligned`() = runBlocking {
        val p = playerFor(buildAvi(frames = listOf(ByteArray(5) { 1 }, jpeg(9))))
        p.index()
        assertEquals(2, p.frames.size)
        p.close()
    }

    @Test
    fun `raw jpeg payload becomes a single frame`() = runBlocking {
        val jpg = jpeg(42)
        val p = playerFor(jpg)
        p.index()
        assertEquals(1, p.frames.size)
        assertEquals(0L, p.frames[0].offset)
        assertEquals(jpg.size, p.frames[0].length)
        assertEquals(0L, p.frameMs)
        p.close()
    }

    @Test(expected = AviMjpegPlayer.AviException::class)
    fun `non-riff input is rejected`() {
        runBlocking {
            playerFor("NOTAVI__".toByteArray()).index()
        }
    }

    @Test(expected = AviMjpegPlayer.AviException::class)
    fun `avi with no video chunks is rejected`() {
        runBlocking {
            playerFor(buildAvi(frames = emptyList())).index()
        }
    }

    @Test
    fun `frameAt out of range returns null`() = runBlocking {
        val p = playerFor(buildAvi(frames = listOf(jpeg(1))))
        p.index()
        assertNull(p.frameAt(5))
        p.close()
    }

    @Test
    fun `audio stream is indexed with format`() = runBlocking {
        val pcm = ByteArray(4) { 7 } + ByteArray(4) { 9 }
        val p = playerFor(
            buildAvi(
                frames = listOf(jpeg(1)),
                audioChunks = listOf(pcm.copyOfRange(0, 4), pcm.copyOfRange(4, 8))
            )
        )
        p.index()
        assertEquals(2, p.audioRefs.size)
        assertEquals(16000, p.audioRate)
        assertEquals(1, p.audioChannels)
        assertEquals(32000L, p.audioBytesPerSec)
        assertEquals(8L, p.audioByteLen)
        assert(p.hasAudio)
        p.close()
    }

    @Test
    fun `audio strh does not clobber video timing`() = runBlocking {
        // auds strh rate 32000 would give ~31µs/frame if it overwrote the
        // video stream's 1/30 timing.
        val p = playerFor(
            buildAvi(
                strhScale = 1,
                strhRate = 30,
                frames = listOf(jpeg(1)),
                audioChunks = listOf(ByteArray(8)),
                audioStrhRate = 32000
            )
        )
        p.index()
        assertEquals(33L, p.frameMs)
        p.close()
    }

    @Test
    fun `audioRead serves pcm across chunk boundaries`() = runBlocking {
        val p = playerFor(
            buildAvi(
                frames = listOf(jpeg(1)),
                audioChunks = listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8))
            )
        )
        p.index()
        val buf = ByteArray(3)
        assertEquals(3, p.audioRead(0, buf))
        assert(byteArrayOf(1, 2, 3).contentEquals(buf))
        assertEquals(1, p.audioRead(3, buf)) // stops at the chunk boundary…
        assertEquals(4, buf[0].toInt())
        assertEquals(3, p.audioRead(4, buf)) // …caller's next read crosses it
        assert(byteArrayOf(5, 6, 7).contentEquals(buf))
        assertEquals(-1, p.audioRead(8, buf))
        p.close()
    }

    @Test
    fun `audioPosForMs is linear for pcm`() = runBlocking {
        val p = playerFor(
            buildAvi(
                frames = listOf(jpeg(1)),
                audioChunks = listOf(ByteArray(64))
            )
        )
        p.index()
        assertEquals(32L, p.audioPosForMs(1)) // 1 ms = 32 B @ 32 000 B/s
        // A position past the audio end clamps to the stream length.
        assertEquals(p.audioByteLen, p.audioPosForMs(999_999))
        p.close()
    }

    @Test
    fun `probe reads duration and first frame without indexing`() = runBlocking {
        val p = playerFor(
            buildAvi(
                avihUsec = 200_000,
                totalFrames = 30,
                frames = listOf(jpeg(1), jpeg(2), jpeg(3))
            )
        )
        p.probe()
        assertEquals(6.0, p.probeDurationSec!!, 0.01)
        assert(p.thumbFrame != null)
        // No 'movi' walk — the frame list stays empty.
        assert(p.frames.isEmpty())
        p.close()
    }

    @Test
    fun `probe on raw jpeg exposes the frame`() = runBlocking {
        val jpg = jpeg(42)
        val p = playerFor(jpg)
        p.probe()
        assertEquals(0L, p.thumbFrame!!.offset)
        assertEquals(jpg.size, p.thumbFrame!!.length)
        p.close()
    }

    @Test
    fun `no audio stream means no audio`() = runBlocking {
        val p = playerFor(buildAvi(frames = listOf(jpeg(1))))
        p.index()
        assert(!p.hasAudio)
        p.close()
    }

    @Test
    fun `extendIndex resumes where a truncated scan stopped`() = runBlocking {
        // Simulate a streaming download: the file arrives in pieces and each
        // pass must index only newly complete chunks.
        val avi = buildAvi(
            frames = listOf(
                ByteArray(500) { 1 },
                ByteArray(500) { 2 },
                ByteArray(500) { 3 }
            )
        )
        val f = File.createTempFile("avi-stream", ".bin")
        f.deleteOnExit()
        val writer = RandomAccessFile(f, "rw")
        try {
            // Cut mid-movi — the tail lands inside a chunk payload.
            val cut = avi.size - 700
            writer.write(avi, 0, cut)
            val p = AviMjpegPlayer(writer.channel)

            p.extendIndex()
            val before = p.frames.size
            // The leading complete frame must index even though the movi
            // list declares the full file size — requiring its whole body
            // would hold indexing hostage until the download finishes.
            assert(before in 1..2)
            assert(!p.fullyIndexed)

            // A dangling half-written chunk header must not crash or consume.
            writer.write(avi, cut, 4)
            p.extendIndex()
            assertEquals(before, p.frames.size)

            writer.write(avi, cut + 4, avi.size - cut - 4)
            p.extendIndex()
            assertEquals(3, p.frames.size)
            assert(p.fullyIndexed)
            p.close()
        } finally {
            writer.close()
        }
    }

    @Test
    fun `reads on a closed channel return empty instead of throwing`() = runBlocking {
        // close() can race an in-flight audioRead/frameAt on another
        // coroutine — a dead channel must read as "no data", not crash.
        val p = playerFor(
            buildAvi(
                frames = listOf(jpeg(1)),
                audioChunks = listOf(ByteArray(4))
            )
        )
        p.index()
        p.close()
        assertNull(p.frameAt(0))
        assertNull(p.thumbnail(160))
        assertEquals(-1, p.audioRead(0, ByteArray(64)))
    }
}
