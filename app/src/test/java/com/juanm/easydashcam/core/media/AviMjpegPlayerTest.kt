package com.juanm.easydashcam.core.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AviMjpegPlayerTest {

    // ---- synthetic AVI builder -------------------------------------------------

    private fun le32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun fourcc(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun ByteArrayOutputStream.chunk(id: String, body: ByteArray) {
        write(fourcc(id)); write(le32(body.size)); write(body)
        if (body.size and 1 == 1) write(0)   // RIFF word-aligns odd sizes
    }

    private fun listChunk(type: String, body: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        b.chunk("LIST", fourcc(type) + body)
        return b.toByteArray()
    }

    private fun jpeg(seed: Int): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            ByteArray(seed % 7 + 4) { it.toByte() } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun buildAvi(
        avihUsec: Int = 200_000,
        strhScale: Int = 0,
        strhRate: Int = 0,
        frames: List<ByteArray>,
    ): ByteArray {
        val out = ByteArrayOutputStream()

        out.chunk("avih", le32(avihUsec) + ByteArray(52))

        val strh = "vids".toByteArray() + ByteArray(52)
        le32(strhScale).copyInto(strh, 20)
        le32(strhRate).copyInto(strh, 24)
        val strl = ByteArrayOutputStream()
        strl.chunk("strh", strh)
        strl.chunk("strf", ByteArray(40))
        out.write(listChunk("hdrl", listChunk("strl", strl.toByteArray())))

        val movi = ByteArrayOutputStream()
        frames.forEach { movi.chunk("00dc", it) }
        movi.chunk("01wb", ByteArray(16))   // audio chunks must be skipped
        out.write(listChunk("movi", movi.toByteArray()))

        out.chunk("idx1", ByteArray(32))

        val riff = ByteArrayOutputStream()
        riff.write(fourcc("RIFF")); riff.write(le32(out.size() + 4)); riff.write(fourcc("AVI "))
        riff.write(out.toByteArray())
        return riff.toByteArray()
    }

    private fun playerFor(bytes: ByteArray): AviMjpegPlayer {
        val f = File.createTempFile("avi-test", ".bin")
        f.deleteOnExit()
        f.writeBytes(bytes)
        return AviMjpegPlayer(RandomAccessFile(f, "r").channel)
    }

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
        val p = playerFor(buildAvi(avihUsec = 200_000, strhScale = 1, strhRate = 30,
            frames = listOf(jpeg(1))))
        p.index()
        assertEquals(33L, p.frameMs)   // 1/30 s, not avih's 200 ms
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
}
