package com.juanm.easydashcam.core.net

import com.juanm.easydashcam.core.net.GpsocketClient.CameraFile
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** File-list paging against [FakeCamera] over a real localhost socket. */
class GpsocketFileListTest {

    private lateinit var camera: FakeCamera
    private lateinit var client: GpsocketClient

    @Before
    fun setUp() = runBlocking {
        camera = FakeCamera()
        client = GpsocketClient("127.0.0.1", camera.port)
        client.connect()
    }

    @After
    fun tearDown() {
        client.disconnect()
        camera.close()
    }

    /** Photo and video records share an id, as on the real camera. */
    private fun records(count: Int) = List(count) { i ->
        CameraFile(kind = if (i % 2 == 0) 'A'.code else 'B'.code, id = i / 2 + 1, timestamp = STAMP, size = 100L + i)
    }

    private fun page(recs: List<CameraFile>): ByteArray {
        val out = java.io.ByteArrayOutputStream().also { it.write(0x10) }
        for (r in recs) {
            out.write(r.kind)
            out.write(FakeCamera.le16(r.id))
            out.write(byteArrayOf(24, 11, 1, 10, 0, 0)) // 2024-11-01 10:00:00
            out.write(FakeCamera.le16(r.size.toInt()) + FakeCamera.le16(0))
        }
        return out.toByteArray()
    }

    /** Serves [all] page by page, and after the last page serves the first
     *  again, like the camera in the device log (page 26 == page 0). */
    private fun serveCircular(all: List<CameraFile>) {
        val pages = all.chunked(GpsocketClient.FILE_PAGE_SIZE)
        var next = 0
        val default = camera.onCommand
        camera.onCommand = { cmd, body ->
            if (cmd == GpsocketClient.CMD_FILE_LIST) {
                if (body[0] == 1.toByte()) next = 0
                camera.reply(cmd, page(pages[next % pages.size]))
                next++
            } else {
                default(cmd, body)
            }
        }
    }

    @Test
    fun `circular list that wraps is returned once`() = runBlocking {
        val all = records(GpsocketClient.FILE_PAGE_SIZE * 3)
        serveCircular(all)
        val listed = client.listFiles()
        assertEquals(all, listed)
    }

    /** Serves a sliding 16-record window over [all] that wraps past the end,
     *  so the page after the last full one mixes new and already-seen records. */
    private fun serveWrappingWindows(all: List<CameraFile>) {
        var offset = 0
        val default = camera.onCommand
        camera.onCommand = { cmd, body ->
            if (cmd == GpsocketClient.CMD_FILE_LIST) {
                val window = List(GpsocketClient.FILE_PAGE_SIZE) { all[(offset + it) % all.size] }
                camera.reply(cmd, page(window))
                offset += GpsocketClient.FILE_PAGE_SIZE
            } else {
                default(cmd, body)
            }
        }
    }

    /** Serves [all] once, with a short final page and nothing after it. */
    private fun serveLinear(all: List<CameraFile>) {
        val pages = all.chunked(GpsocketClient.FILE_PAGE_SIZE)
        var next = 0
        val default = camera.onCommand
        camera.onCommand = { cmd, body ->
            if (cmd == GpsocketClient.CMD_FILE_LIST) {
                camera.reply(cmd, page(pages[next++]))
            } else {
                default(cmd, body)
            }
        }
    }

    @Test
    fun `list that wraps mid-page is returned once`() = runBlocking {
        val all = records(GpsocketClient.FILE_PAGE_SIZE + 4)
        serveWrappingWindows(all)
        assertEquals(all, client.listFiles())
    }

    @Test
    fun `list ending in a short page is returned once`() = runBlocking {
        val all = records(GpsocketClient.FILE_PAGE_SIZE + 4)
        serveLinear(all)
        assertEquals(all, client.listFiles())
    }

    private companion object {
        val STAMP: LocalDateTime = LocalDateTime.of(2024, 11, 1, 10, 0, 0)
    }
}
