package com.juanm.easydashcam.core.net

import com.juanm.easydashcam.core.net.GpsocketClient.CameraFile
import com.juanm.easydashcam.core.net.GpsocketClient.DownloadResult
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** End-to-end transfers against [FakeCamera] over a real localhost socket. */
class GpsocketDownloadTest {

    private lateinit var camera: FakeCamera
    private lateinit var client: GpsocketClient

    /** A clip-shaped payload: RIFF magic, then a pattern unique to [seed]. */
    private fun clip(kib: Int, seed: Int): ByteArray {
        val data = ByteArray(kib * 1024) { (it * 31 + seed).toByte() }
        "RIFF".toByteArray().copyInto(data)
        return data
    }

    private fun record(id: Int, data: ByteArray) = CameraFile('A'.code, id, null, data.size / 1024L)

    @Before
    fun setUp() = runBlocking {
        camera = FakeCamera()
        client = GpsocketClient("127.0.0.1", camera.port)
        client.connect()
        assertEquals(GpsocketClient.State.Connected, client.state.replayCache.first())
    }

    @After
    fun tearDown() {
        client.disconnect()
        camera.close()
    }

    @Test
    fun `download writes exactly the served bytes`() = runBlocking {
        val data = clip(96, seed = 1)
        camera.files[0x0101] = data
        val out = ByteArrayOutputStream()
        val result = client.downloadFile(record(0x0101, data), out)
        assertEquals(DownloadResult.Success(data.size.toLong()), result)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun `camera refusal is reported as Refused`() = runBlocking {
        val result = client.downloadFile(CameraFile('A'.code, 0x0999, null, 64), ByteArrayOutputStream())
        assertEquals(DownloadResult.Refused, result)
    }

    /** The real camera keeps streaming a file for seconds after the app
     *  stops reading (device test, 2026-11-03), longer than the next
     *  download's ~2.4 s preamble: serve [id] at ~60 ms per chunk from a
     *  side thread, whatever else the app sends meanwhile. */
    private fun serveSlowly(id: Int, data: ByteArray) {
        val default = camera.onCommand
        camera.onCommand = { cmd, body ->
            if (cmd == GpsocketClient.CMD_DOWNLOAD && FakeCamera.u16(body, 0) == id) {
                thread {
                    data.toList().chunked(FakeCamera.CHUNK).forEach {
                        camera.reply(GpsocketClient.CMD_DOWNLOAD, it.toByteArray())
                        Thread.sleep(60)
                    }
                    camera.reply(GpsocketClient.CMD_DOWNLOAD)
                }
            } else {
                default(cmd, body)
            }
        }
    }

    @Test
    fun `an aborted transfer does not leak into the next download`() = runBlocking {
        val big = clip(2048, seed = 2)
        val small = clip(96, seed = 3)
        serveSlowly(0x0a01, big)
        camera.files[0x0a02] = small

        val aborted = runCatching {
            client.downloadFile(record(0x0a01, big), ByteArrayOutputStream()) { _, _ ->
                throw CancellationException("user cancelled")
            }
        }
        assertTrue(aborted.exceptionOrNull() is CancellationException)

        val out = ByteArrayOutputStream()
        assertEquals(DownloadResult.Success(small.size.toLong()), client.downloadFile(record(0x0a02, small), out))
        assertArrayEquals(small, out.toByteArray())
    }

    @Test
    fun `data that is neither AVI nor JPEG fails instead of being saved`() = runBlocking {
        val junk = ByteArray(64 * 1024) { 7 }
        camera.files[0x0c01] = junk
        val result = client.downloadFile(record(0x0c01, junk), ByteArrayOutputStream())
        assertTrue(result.toString(), result is DownloadResult.Failed)
    }
}
