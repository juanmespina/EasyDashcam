package com.juanm.easydashcam.ui.camera

import com.juanm.easydashcam.core.net.GpsocketClient.CameraFile
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoEnqueueTest {

    private val watermark = LocalDateTime.of(2026, 11, 3, 12, 0, 0)

    private fun at(sec: Long) = watermark.plusSeconds(sec)

    private fun clip(id: Int, sec: Long, kind: Char = 'A') = CameraFile(kind.code, id, at(sec), 1)

    @Test
    fun `records sharing the last timestamp are not split by the limit`() {
        val files = (1..9).map { clip(it, it * 60L) } + clip(10, 600) + clip(10, 600, 'J')
        val picked = selectAutoDownloads(files, watermark, emptySet())
        assertEquals(11, picked.size)
        assertEquals(setOf('A', 'J'), picked.filter { it.id == 10 }.map { it.kindChar }.toSet())
    }

    @Test
    fun `the limit holds when the next record is newer`() {
        val files = (1..12).map { clip(it, it * 60L) }
        assertEquals((1..10).toList(), selectAutoDownloads(files, watermark, emptySet()).map { it.id })
    }

    @Test
    fun `locked, undated, already known and not-newer records are skipped`() {
        val known = clip(3, 180)
        val files = listOf(
            clip(1, 0),
            clip(2, -60),
            clip(4, 240, 'L'),
            CameraFile('A'.code, 5, null, 1),
            known,
            clip(6, 360)
        )
        assertEquals(listOf(6), selectAutoDownloads(files, watermark, setOf(known.uniqueId)).map { it.id })
    }

    @Test
    fun `oldest first`() {
        val files = listOf(clip(2, 120), clip(1, 60), clip(3, 180))
        assertEquals(listOf(1, 2, 3), selectAutoDownloads(files, watermark, emptySet()).map { it.id })
    }
}
