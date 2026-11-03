package com.juanm.easydashcam.ui.files

import com.juanm.easydashcam.core.media.TestAvi.buildAvi
import com.juanm.easydashcam.core.media.TestAvi.jpeg
import com.juanm.easydashcam.core.media.TestAvi.playerFor
import com.juanm.easydashcam.core.net.GpsocketClient.DownloadResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipPlaybackTest {

    /** 30 frames at 10 ms each: plays through in ~0.3 s. */
    private fun clip() = playerFor(buildAvi(avihUsec = 10_000, frames = (1..30).map { jpeg(it) }))

    @Test
    fun `a local clip plays to its last frame and pauses there`() = runBlocking {
        val playback = ClipPlayback(clip(), isImage = false, fetch = null)
        val job = launch { playback.run() }
        val end = withTimeout(5_000) { playback.state.first { it.paused } }
        job.cancel()
        assertEquals(30, end.frameCount)
        assertEquals(30, end.pos)
        assertTrue(end.indexed)
    }

    @Test
    fun `seek, skip and pause follow the frame bounds`() = runBlocking {
        val playback = ClipPlayback(clip(), isImage = false, fetch = null)
        val job = launch { playback.run() }
        withTimeout(5_000) { playback.state.first { it.indexed } }
        playback.togglePause()
        playback.seekTo(500)
        assertEquals(29, playback.state.value.pos)
        playback.skip(-100)
        assertEquals(0, playback.state.value.pos)
        assertFalse(playback.state.value.paused)
        job.cancel()
    }

    @Test
    fun `a refused stream reports the transfer failure`() = runBlocking {
        val file = kotlin.io.path.createTempFile().toFile().apply { deleteOnExit() }
        val player = com.juanm.easydashcam.core.media.AviMjpegPlayer(java.io.RandomAccessFile(file, "r").channel)
        val playback = ClipPlayback(player, isImage = false, fetch = { DownloadResult.Refused })
        withTimeout(5_000) { playback.run() }
        assertEquals(ClipPlayback.Failure.Transfer(DownloadResult.Refused), playback.state.value.failure)
    }
}
