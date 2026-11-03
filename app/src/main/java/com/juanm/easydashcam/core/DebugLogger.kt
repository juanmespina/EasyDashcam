package com.juanm.easydashcam.core

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The retail tablet's logd silently drops app logs even with log.tag props
 * set — debug builds mirror diagnostics to a file so `run-as … cat
 * files/debug.log` works without a live logcat. Release builds never call
 * [init], so they write no file.
 *
 * Callers include the socket read loop and the main thread, so lines are
 * appended on one background thread rather than on the caller's.
 */
object DebugLogger {
    @Volatile private var out: File? = null
    private const val MAX_BYTES = 256 * 1024
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "debug-log").apply { isDaemon = true } }

    // Only the writer thread formats, so the non-thread-safe formatter is safe.
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(filesDir: File) {
        out = File(filesDir, "debug.log")
    }

    /** The on-device log — for the Debug screen's share button. */
    val logFile: File? get() = out

    fun log(tag: String, msg: String) {
        Log.i(tag, msg)
        val f = out ?: return
        val at = System.currentTimeMillis()
        writer.execute {
            runCatching {
                if (f.length() > MAX_BYTES) {
                    // keep the tail — a rewritten file beats an ever-growing one
                    val bytes = f.readBytes()
                    f.writeBytes(bytes.copyOfRange(bytes.size - MAX_BYTES / 2, bytes.size))
                }
                f.appendText("${stamp.format(Date(at))} $tag: $msg\n")
            }
        }
    }

    /** Wait until every queued line is on disk — before the file is shared. */
    fun flush() {
        writer.submit {}.get()
    }
}
