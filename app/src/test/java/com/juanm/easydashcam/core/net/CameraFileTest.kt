package com.juanm.easydashcam.core.net

import com.juanm.easydashcam.core.net.GpsocketClient.CameraFile
import java.time.LocalDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFileTest {

    private val now = LocalDateTime.of(2026, 11, 7, 21, 47, 34)

    private fun fileAt(timestamp: LocalDateTime?) = CameraFile('V'.code, 0x503c, timestamp, 4096)

    @Test
    fun `a clip stamped seconds ago may still be recording`() {
        assertTrue(fileAt(now.minusSeconds(30)).maybeStillRecording(now))
    }

    @Test
    fun `a clip recorded an hour ago is not still recording`() {
        assertFalse(fileAt(now.minusHours(1)).maybeStillRecording(now))
    }

    @Test
    fun `the window closes at three minutes`() {
        assertTrue(fileAt(now.minusMinutes(2).minusSeconds(59)).maybeStillRecording(now))
        assertFalse(fileAt(now.minusMinutes(3)).maybeStillRecording(now))
    }

    @Test
    fun `a camera clock slightly ahead of the phone still counts as recent`() {
        assertTrue(fileAt(now.plusMinutes(1)).maybeStillRecording(now))
    }

    @Test
    fun `a record without a timestamp is not flagged`() {
        assertFalse(fileAt(null).maybeStillRecording(now))
    }
}
