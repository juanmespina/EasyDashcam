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
    class Dest(val label: String, val uri: Uri?, val fallback: File?, val out: OutputStream)

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

    // The suffix anchored at the end matches every name labelFor has
    // produced, including legacy cam_<id4><kind> and photos renamed to .jpg.
    private val clipNameRx = Regex("""([0-9a-fA-F]{4})([A-Z])\.(avi|jpg)$""")

    /** The [GpsocketClient.CameraFile.uniqueId] a saved file name maps back
     *  to, or null for a name this app did not write. */
    fun parseClipName(name: String): Int? {
        val m = clipNameRx.find(name) ?: return null
        return (m.groupValues[1].toInt(16) shl 8) or m.groupValues[2][0].code
    }

    fun open(context: Context, f: GpsocketClient.CameraFile, partial: Boolean = false): Dest? {
        val label = labelFor(f, partial)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, label)
                put(MediaStore.Video.Media.MIME_TYPE, "video/x-msvideo")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EasyDashcam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            )
            val out = uri?.let { context.contentResolver.openOutputStream(it) } ?: return null
            return Dest(label, uri, null, out)
        }
        val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), label)
        return Dest(label, null, fallback, fallback.outputStream())
    }

    /** Clears IS_PENDING. Photo payloads are raw JPEG — MediaStore rejects a
     *  mime-type flip on a Video row (image/ is not allowed under the video
     *  collection), so the bytes are moved to a fresh Images row instead. */
    fun commit(context: Context, dest: Dest, isJpeg: Boolean) {
        if (Build.VERSION.SDK_INT >= 29 && dest.uri != null) {
            if (!isJpeg) {
                context.contentResolver.update(
                    dest.uri,
                    ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                )
                return
            }
            val values = ContentValues().apply {
                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    dest.label.removeSuffix(".avi") + ".jpg"
                )
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/EasyDashcam"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val imgUri = context.contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            ) ?: throw java.io.IOException("photo insert failed")
            try {
                context.contentResolver.openInputStream(dest.uri).use { src ->
                    context.contentResolver.openOutputStream(imgUri).use { dst ->
                        if (src == null || dst == null) {
                            throw java.io.IOException("photo copy failed")
                        }
                        src.copyTo(dst)
                    }
                }
                context.contentResolver.update(
                    imgUri,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                )
                context.contentResolver.delete(dest.uri, null, null)
            } catch (e: Exception) {
                runCatching { context.contentResolver.delete(imgUri, null, null) }
                throw e
            }
        } else if (isJpeg && dest.fallback != null) {
            dest.fallback.renameTo(
                File(dest.fallback.parentFile, dest.label.removeSuffix(".avi") + ".jpg")
            )
        } else {
            // API <29 videos: nothing to clear, the file is already final.
        }
    }

    fun fail(context: Context, dest: Dest) {
        runCatching { dest.uri?.let { context.contentResolver.delete(it, null, null) } }
        dest.fallback?.delete()
    }
}
