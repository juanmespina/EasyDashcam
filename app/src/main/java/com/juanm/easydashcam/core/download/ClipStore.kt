package com.juanm.easydashcam.core.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.juanm.easydashcam.core.net.GpsocketClient
import java.io.File
import java.io.OutputStream

/**
 * Where a clip lands on the phone: MediaStore on API 29+, the app-private
 * external dir below. Shared by [DownloadService] (socket → store) and the
 * streaming player (temp file → store when the user keeps a streamed clip).
 */
object ClipStore {

    /** An open destination for a clip's bytes. */
    class Dest(
        val label: String,
        val uri: Uri?,
        val fallback: File?,
        val out: OutputStream
    )

    /** Timestamped names sort naturally and the id+kind suffix lets
     *  refreshLocalFiles map the file back to its camera record:
     *  20251011_143022_e507A.avi — partial stream saves keep the suffix so
     *  the mapping still works but stay distinguishable:
     *  20251011_143022_partial_e507A.avi */
    fun labelFor(f: GpsocketClient.CameraFile, partial: Boolean = false): String {
        val stamp = f.timestamp
            ?.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return (stamp?.let { "${it}_" } ?: "cam_") +
            (if (partial) "partial_" else "") +
            "%04x%c.avi".format(f.id, f.kindChar)
    }

    fun open(context: Context, f: GpsocketClient.CameraFile,
             partial: Boolean = false): Dest? {
        val label = labelFor(f, partial)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, label)
                put(MediaStore.Video.Media.MIME_TYPE, "video/x-msvideo")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EasyDashcam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            val out = uri?.let { context.contentResolver.openOutputStream(it) } ?: return null
            return Dest(label, uri, null, out)
        }
        val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), label)
        return Dest(label, null, fallback, fallback.outputStream())
    }

    /** Clears IS_PENDING; photo payloads are raw JPEG, so they get renamed
     *  into Pictures with the right MIME type. */
    fun commit(context: Context, dest: Dest, isJpeg: Boolean) {
        if (Build.VERSION.SDK_INT >= 29 && dest.uri != null) {
            context.contentResolver.update(dest.uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
                if (isJpeg) {
                    put(MediaStore.Video.Media.DISPLAY_NAME,
                        dest.label.removeSuffix(".avi") + ".jpg")
                    put(MediaStore.Video.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/EasyDashcam")
                }
            }, null, null)
        } else if (isJpeg && dest.fallback != null) {
            dest.fallback.renameTo(
                File(dest.fallback.parentFile, dest.label.removeSuffix(".avi") + ".jpg"))
        }
    }

    fun fail(context: Context, dest: Dest) {
        runCatching { dest.uri?.let { context.contentResolver.delete(it, null, null) } }
        dest.fallback?.delete()
    }
}
