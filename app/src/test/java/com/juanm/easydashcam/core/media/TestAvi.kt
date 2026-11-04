package com.juanm.easydashcam.core.media

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic RIFF/AVI clips for tests: MJPEG video, optional PCM audio. */
object TestAvi {
    private fun le32(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun le16(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    private fun fourcc(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun ByteArrayOutputStream.chunk(id: String, body: ByteArray) {
        write(fourcc(id))
        write(le32(body.size))
        write(body)
        if (body.size and 1 == 1) write(0) // RIFF word-aligns odd sizes
    }

    private fun listChunk(type: String, body: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        b.chunk("LIST", fourcc(type) + body)
        return b.toByteArray()
    }

    fun jpeg(seed: Int): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
        ByteArray(seed % 7 + 4) { it.toByte() } +
        byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    // Every parameter has a default and tests pass them by name.
    @Suppress("LongParameterList")
    fun buildAvi(
        avihUsec: Int = 200_000,
        strhScale: Int = 0,
        strhRate: Int = 0,
        frames: List<ByteArray>,
        audioChunks: List<ByteArray> = emptyList(),
        audioRate: Int = 16000,
        audioStrhRate: Int = 0,
        totalFrames: Int = 0
    ): ByteArray {
        val out = ByteArrayOutputStream()

        val avih = le32(avihUsec) + ByteArray(52)
        le32(totalFrames).copyInto(avih, 16)
        out.chunk("avih", avih)

        val strh = "vids".toByteArray() + ByteArray(52)
        le32(strhScale).copyInto(strh, 20)
        le32(strhRate).copyInto(strh, 24)
        val strl = ByteArrayOutputStream()
        strl.chunk("strh", strh)
        strl.chunk("strf", ByteArray(40))
        val hdrl = ByteArrayOutputStream()
        hdrl.write(listChunk("strl", strl.toByteArray()))
        if (audioChunks.isNotEmpty()) {
            val astrh = "auds".toByteArray() + ByteArray(52)
            le32(1).copyInto(astrh, 20)
            le32(audioStrhRate).copyInto(astrh, 24)
            // WAVEFORMATEX: PCM, mono, rate, avgBps = rate*2, align 2, 16-bit
            val wave = le16(1) + le16(1) + le32(audioRate) + le32(audioRate * 2) +
                le16(2) + le16(16)
            val astrl = ByteArrayOutputStream()
            astrl.chunk("strh", astrh)
            astrl.chunk("strf", wave)
            hdrl.write(listChunk("strl", astrl.toByteArray()))
        }
        out.write(listChunk("hdrl", hdrl.toByteArray()))

        val movi = ByteArrayOutputStream()
        frames.forEach { movi.chunk("00dc", it) }
        audioChunks.forEach { movi.chunk("01wb", it) }
        out.write(listChunk("movi", movi.toByteArray()))

        out.chunk("idx1", ByteArray(32))

        val riff = ByteArrayOutputStream()
        riff.write(fourcc("RIFF"))
        riff.write(le32(out.size() + 4))
        riff.write(fourcc("AVI "))
        riff.write(out.toByteArray())
        return riff.toByteArray()
    }

    fun playerFor(bytes: ByteArray): AviMjpegPlayer {
        val f = File.createTempFile("avi-test", ".bin")
        f.deleteOnExit()
        f.writeBytes(bytes)
        return AviMjpegPlayer(RandomAccessFile(f, "r").channel)
    }
}
