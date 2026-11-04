package com.juanm.easydashcam.core.download

import com.juanm.easydashcam.core.net.GpsocketClient.CameraFile
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipNameTest {

    private val at = LocalDateTime.of(2025, 10, 11, 14, 30, 22)

    @Test
    fun `every label maps back to its record`() {
        val records = listOf(
            CameraFile('A'.code, 0xe507, at, 1),
            CameraFile('J'.code, 0xe507, at, 1),
            CameraFile('B'.code, 0x0001, at, 1),
            CameraFile('A'.code, 0xffff, null, 1)
        )
        for (f in records) {
            for (partial in listOf(false, true)) {
                val name = ClipStore.labelFor(f, partial)
                assertEquals(name, f.uniqueId, ClipStore.parseClipName(name))
            }
        }
    }

    @Test
    fun `label shape is stable`() {
        assertEquals("20251011_143022_e507A.avi", ClipStore.labelFor(CameraFile('A'.code, 0xe507, at, 1)))
        assertEquals("cam_partial_0001A.avi", ClipStore.labelFor(CameraFile('A'.code, 1, null, 1), partial = true))
    }

    @Test
    fun `photo renamed to jpg still maps back`() {
        assertEquals(CameraFile('J'.code, 0xe507, at, 1).uniqueId, ClipStore.parseClipName("20251011_143022_e507J.jpg"))
    }

    @Test
    fun `names this app did not write are ignored`() {
        assertNull(ClipStore.parseClipName("snap_20251011_143022.jpg"))
        assertNull(ClipStore.parseClipName("holiday.mp4"))
        // MediaStore's duplicate suffix is not recognised (documents current behaviour).
        assertNull(ClipStore.parseClipName("20251011_143022_e507A (1).avi"))
    }
}
