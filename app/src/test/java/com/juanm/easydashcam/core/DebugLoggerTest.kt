package com.juanm.easydashcam.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLoggerTest {

    @Test
    fun `lines reach the file in order once flushed`() {
        val dir = Files.createTempDirectory("dbglog").toFile().apply { deleteOnExit() }
        DebugLogger.init(dir)
        repeat(50) { DebugLogger.log("T", "line $it") }
        DebugLogger.flush()
        val lines = DebugLogger.logFile!!.readLines()
        assertEquals(50, lines.size)
        assertEquals((0 until 50).map { "line $it" }, lines.map { it.substringAfter("T: ") })
    }

    @Test
    fun `a full log is trimmed to its newer half`() {
        val dir = Files.createTempDirectory("dbglog").toFile().apply { deleteOnExit() }
        DebugLogger.init(dir)
        val filler = "x".repeat(1_000)
        repeat(300) { DebugLogger.log("T", "$it $filler") }
        DebugLogger.flush()
        val text = DebugLogger.logFile!!.readText()
        assertTrue("size ${text.length}", text.length <= 256 * 1024)
        assertTrue(text.trimEnd().endsWith("299 $filler"))
    }
}
