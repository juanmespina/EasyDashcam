package com.juanm.easydashcam.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import com.juanm.easydashcam.core.DebugLogger
import androidx.core.app.NotificationCompat
import com.juanm.easydashcam.EasyDashcamApp
import com.juanm.easydashcam.core.net.GpsocketClient
import com.juanm.easydashcam.core.session.CameraSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream

/**
 * Foreground service that downloads recordings in the background.
 * The GPSOCKET socket serializes transfers anyway, so jobs run FIFO on one
 * worker coroutine — queued requests are simply picked up in order.
 */
class DownloadService : Service() {

    companion object {
        private const val ACTION_DOWNLOAD = "com.juanm.easydashcam.action.DOWNLOAD"
        private const val ACTION_CANCEL = "com.juanm.easydashcam.action.CANCEL"
        private const val EXTRA_ID = "id"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_TS = "ts"
        private const val CHANNEL_ID = "downloads"
        private const val CHANNEL_RESULT_ID = "downloads_done"
        private const val NOTIF_ID = 1
        private const val NOTIF_RESULT_ID = 2
        private const val IDLE_SHUTDOWN_MS = 10_000L

        fun downloadIntent(context: Context, f: GpsocketClient.CameraFile) =
            Intent(context, DownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_ID, f.id)
                putExtra(EXTRA_KIND, f.kind)
                putExtra(EXTRA_SIZE, f.size)
                f.timestamp?.let { putExtra(EXTRA_TS, it.toString()) }
            }

        fun cancelIntent(context: Context, uniqueId: Int) =
            Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_ID, uniqueId)
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<GpsocketClient.CameraFile>(Channel.UNLIMITED)
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private lateinit var session: CameraSession
    private lateinit var nm: NotificationManager
    // Written on main (onStartCommand) and reset by the IO worker.
    @Volatile private var worker = false
    // Queue counters for "file N of M" in the notification.
    @Volatile private var enqueued = 0
    @Volatile private var finished = 0

    override fun onCreate() {
        super.onCreate()
        session = (application as EasyDashcamApp).session
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT_ID, "Download results", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                val f = GpsocketClient.CameraFile(
                    kind = intent.getIntExtra(EXTRA_KIND, 0),
                    id = intent.getIntExtra(EXTRA_ID, 0),
                    timestamp = intent.getStringExtra(EXTRA_TS)
                        ?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() },
                    size = intent.getLongExtra(EXTRA_SIZE, 0)
                )
                queue.trySend(f)
                enqueued++
                ensureWorker()
            }
            ACTION_CANCEL -> {
                cancelled += intent.getIntExtra(EXTRA_ID, -1)
            }
        }
        return START_STICKY
    }

    private fun ensureWorker() {
        if (worker) return
        worker = true
        startForeground(NOTIF_ID, notification("Preparing download…"))
        val pm = getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION") // partial wake lock is still the right tool for a socket transfer
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "easydashcam:download")
        wakeLock.acquire(60 * 60 * 1000L)
        // IO, not Main: every 32 KiB chunk is written to disk from the
        // worker's context — on Main that meant ~1900 main-thread flash
        // writes per clip.
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    // Idle-timeout: shut down when the queue stays empty, so the
                    // foreground notification doesn't linger forever.
                    val f = kotlinx.coroutines.withTimeoutOrNull(IDLE_SHUTDOWN_MS) {
                        queue.receive()
                    } ?: break
                    if (f.uniqueId in cancelled) { finishFile(f.uniqueId, error = "cancelled"); continue }
                    downloadOne(f)
                }
            } finally {
                wakeLock.release()
            }
            worker = false
            enqueued = 0
            finished = 0
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun downloadOne(f: GpsocketClient.CameraFile) {
        // Timestamped names sort naturally and make a clip findable in the
        // gallery: 20251011_143022_e507A.avi — the id+kind suffix stays so
        // refreshLocalFiles can map the file back to its camera record.
        val stamp = f.timestamp
            ?.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val label = (stamp?.let { "${it}_" } ?: "cam_") +
            "%04x%c.avi".format(f.id, f.kindChar)
        val out: OutputStream?
        val uri: Uri?
        val fallback: File?
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, label)
                put(MediaStore.Video.Media.MIME_TYPE, "video/x-msvideo")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EasyDashcam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            uri = contentResolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            out = uri?.let { contentResolver.openOutputStream(it) }
            fallback = null
        } else {
            uri = null
            fallback = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), label)
            out = fallback.outputStream()
        }
        if (out == null) { finishFile(f.uniqueId, error = "no destination"); return }

        // Photo records come back as raw JPEG, not AVI — capture the magic
        // bytes while streaming so the MediaStore entry can be fixed afterwards.
        val head = java.io.ByteArrayOutputStream()
        val sniffed = object : OutputStream() {
            override fun write(b: Int) {
                if (head.size() < 4) head.write(b)
                out.write(b)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (head.size() < 4) head.write(b, off, minOf(len, 4 - head.size()))
                out.write(b, off, len)
            }
            override fun flush() = out.flush()
            override fun close() = out.close()
        }

        val startMs = System.currentTimeMillis()
        var lastNotifMs = 0L
        val result = try {
            sniffed.use {
                session.client.downloadFile(f, it) { got, total ->
                    if (f.uniqueId in cancelled) throw CancellationException("user cancelled")
                    // The callback fires per 32 KiB chunk — every emission
                    // recomposes the whole Files screen, so cap UI progress
                    // at ~2/s (completion always posts).
                    val now = System.currentTimeMillis()
                    if (now - lastNotifMs >= 500 || got >= total) {
                        lastNotifMs = now
                        val elapsed = now - startMs
                        val eta = if (elapsed > 1500 && got in 1 until total)
                            (total - got) * elapsed / got / 1000 else null
                        session.updateDownload(f.uniqueId,
                            CameraSession.DownloadState(got, total, etaSec = eta))
                        nm.notify(NOTIF_ID, notification(
                            "$label — ${got / 1024}/${total / 1024} KiB" +
                                (eta?.let { e -> ", ~$e s left" } ?: "") +
                                " · file ${finished + 1}/$enqueued", f.uniqueId))
                    }
                }
            }
        } catch (_: CancellationException) {
            failFile(uri, fallback, f.uniqueId, "cancelled")
            return
        }
        when (result) {
            is GpsocketClient.DownloadResult.Success -> {
                if (Build.VERSION.SDK_INT >= 29 && uri != null) {
                    val magic = head.toByteArray()
                    val isJpeg = magic.size >= 2 &&
                        magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte()
                    contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                        if (isJpeg) {
                            put(MediaStore.Video.Media.DISPLAY_NAME,
                                label.removeSuffix(".avi") + ".jpg")
                            put(MediaStore.Video.Media.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Video.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_PICTURES + "/EasyDashcam")
                        }
                    }, null, null)
                }
                finishFile(f.uniqueId)
            }
            is GpsocketClient.DownloadResult.Refused ->
                // feff most often means the camera is still writing this record
                failFile(uri, fallback, f.uniqueId, "camera refused (still recording?)")
            is GpsocketClient.DownloadResult.Incomplete ->
                failFile(uri, fallback, f.uniqueId, "stalled at ${result.bytes}/${result.expected} B")
            is GpsocketClient.DownloadResult.Failed ->
                failFile(uri, fallback, f.uniqueId, result.reason)
        }
    }

    private fun finishFile(id: Int, error: String? = null) {
        finished++
        val prev = session.downloads.value[id]
        session.updateDownload(
            id, CameraSession.DownloadState(prev?.received ?: 0, prev?.total ?: 0, done = true, error = error)
        )
        // Result goes on a separate id + channel — the foreground notification
        // is removed with the service and would take the result with it.
        nm.notify(NOTIF_RESULT_ID, NotificationCompat.Builder(this, CHANNEL_RESULT_ID)
            .setContentTitle("Easy Dashcam")
            .setContentText(if (error == null) "Download complete" else "Download failed: $error")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build())
        DebugLogger.log("GPSOCKET", "download %04x -> %s".format(id ushr 8, error ?: "done"))
    }

    private fun failFile(uri: Uri?, fallback: File?, id: Int, error: String) {
        runCatching { uri?.let { contentResolver.delete(it, null, null) } }
        fallback?.delete()
        finishFile(id, error)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, packageManager.getLaunchIntentForPackage(packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(text: String, fileId: Int? = null): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Easy Dashcam")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
        if (fileId != null) {
            b.addAction(0, "Cancel", PendingIntent.getService(
                this, fileId, cancelIntent(this, fileId), PendingIntent.FLAG_IMMUTABLE))
        }
        return b.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
