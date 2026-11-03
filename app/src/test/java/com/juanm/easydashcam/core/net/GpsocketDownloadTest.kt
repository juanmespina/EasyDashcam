package com.juanm.easydashcam.core.net

import com.juanm.easydashcam.core.net.GpsocketClient.CameraFile
import com.juanm.easydashcam.core.net.GpsocketClient.DownloadResult
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
}
