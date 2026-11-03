package com.juanm.easydashcam.core.net

import com.juanm.easydashcam.core.net.FakeCamera.Companion.frame
import com.juanm.easydashcam.core.net.FakeCamera.Companion.le16
import com.juanm.easydashcam.core.net.GpsocketClient.Frame
import java.time.LocalDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsocketFramingTest {

    private val status = Frame(2, 0x0100, ByteArray(27) { it.toByte() })
    private val refusal = Frame(3, 0x0503, byteArrayOf(0xFE.toByte(), 0xFF.toByte()))
    private val ack = Frame(2, 0x0003, ByteArray(0))

    private fun bytesOf(f: Frame): ByteArray = if (f.type == 3) {
        GpsocketClient.MAGIC + le16(3) + le16(f.cmd) + f.payload
    } else {
        frame(f.cmd, f.payload, f.type)
    }

    /** Feed [chunks] the way the read loop does: carry the leftover bytes
     *  into the next read. */
    private fun feed(vararg chunks: ByteArray): Pair<List<Frame>, ByteArray> {
        val frames = ArrayList<Frame>()
        var rest = ByteArray(0)
        for (c in chunks) {
            val (f, r) = GpsocketClient.splitFrames(rest + c)
            frames += f
            rest = r
        }
        return frames to rest
    }

    @Test
    fun `every split point of a mixed stream yields the same frames`() {
        val garbage = byteArrayOf(1, 2, 3, 'G'.code.toByte(), 'P'.code.toByte())
        val stream = garbage + bytesOf(status) + bytesOf(refusal) + bytesOf(ack)
        for (i in 1 until stream.size) {
            val (frames, rest) = feed(stream.copyOfRange(0, i), stream.copyOfRange(i, stream.size))
            assertEquals("split at $i", listOf(status, refusal, ack), frames)
            assertEquals("split at $i leaves nothing", 0, rest.size)
        }
    }

    @Test
    fun `byte-by-byte delivery yields the same frames`() {
        val stream = bytesOf(ack) + bytesOf(status) + bytesOf(refusal)
        val (frames, _) = feed(*stream.map { byteArrayOf(it) }.toTypedArray())
        assertEquals(listOf(ack, status, refusal), frames)
    }

    @Test
    fun `incomplete frame is kept whole from its magic`() {
        val partial = bytesOf(status).copyOfRange(0, 20)
        val (frames, rest) = GpsocketClient.splitFrames(byteArrayOf(9, 9) + partial)
        assertTrue(frames.isEmpty())
        assertArrayEquals(partial, rest)
    }

    @Test
    fun `garbage without magic keeps only the last 7 bytes`() {
        val junk = ByteArray(40) { (it + 1).toByte() }
        val (frames, rest) = GpsocketClient.splitFrames(junk)
        assertTrue(frames.isEmpty())
        assertArrayEquals(junk.copyOfRange(33, 40), rest)
    }

    @Test
    fun `empty input yields nothing`() {
        val (frames, rest) = GpsocketClient.splitFrames(ByteArray(0))
        assertTrue(frames.isEmpty())
        assertEquals(0, rest.size)
    }

    private fun record(kind: Char, id: Int, at: LocalDateTime, sizeKib: Long): ByteArray = byteArrayOf(
        kind.code.toByte(),
        id.toByte(),
        (id shr 8).toByte(),
        (at.year - 2000).toByte(),
        at.monthValue.toByte(),
        at.dayOfMonth.toByte(),
        at.hour.toByte(),
        at.minute.toByte(),
        at.second.toByte(),
        sizeKib.toByte(),
        (sizeKib shr 8).toByte(),
        (sizeKib shr 16).toByte(),
        (sizeKib shr 24).toByte()
    )

    private val day = LocalDateTime.of(2025, 1, 10, 10, 20, 30)

    private val parser = GpsocketClient()

    @Test
    fun `file list page parses every whole record`() {
        val page = byteArrayOf(0x10) + record('A', 0xea9a, day, 12000) + record('J', 0x0102, day, 192)
        val files = parser.parseFileListPage(page)
        assertEquals(2, files.size)
        assertEquals(
            GpsocketClient.CameraFile('A'.code, 0xea9a, day, 12000),
            files[0]
        )
        assertEquals(192L * 1024, files[1].sizeBytes)
    }

    @Test
    fun `file list page ignores a trailing partial record`() {
        val page = byteArrayOf(0x10) + record('A', 1, day, 1) + ByteArray(5)
        assertEquals(1, parser.parseFileListPage(page).size)
    }

    @Test
    fun `file list record with an impossible date keeps the record without a timestamp`() {
        val files = parser.parseFileListPage(byteArrayOf(0x10) + record('A', 7, day, 1).also { it[4] = 13 })
        assertEquals(1, files.size)
        assertNull(files[0].timestamp)
    }

    @Test
    fun `file list page with a wrong header or no bytes is empty`() {
        assertTrue(parser.parseFileListPage(ByteArray(0)).isEmpty())
        assertTrue(parser.parseFileListPage(byteArrayOf(0x11) + record('A', 1, day, 1)).isEmpty())
    }
}
