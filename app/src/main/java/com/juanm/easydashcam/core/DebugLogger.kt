package com.juanm.easydashcam.core

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The retail tablet's logd silently drops app logs even with log.tag props
 * set — diagnostics are mirrored to a file so `run-as … cat files/debug.log`
 * works on debug builds without a live logcat.
 */
object DebugLogger {
    private var out: File? = null
    private const val MAX_BYTES = 256 * 1024
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(filesDir: File) {
        out = File(filesDir, "debug.log")
    }

    /** The on-device log — for the Debug screen's share button. */
    val logFile: File? get() = out

    @Synchronized
    fun log(tag: String, msg: String) {
        Log.i(tag, msg)
        val f = out ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                // keep the tail — a rewritten file beats an ever-growing one
                val bytes = f.readBytes()
                f.writeBytes(bytes.copyOfRange(bytes.size - MAX_BYTES / 2, bytes.size))
            }
            f.appendText("${stamp.format(Date())} $tag: $msg\n")
        }
    }
}
