package com.juanm.easydashcam.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.juanm.easydashcam.EasyDashcamApp
import com.juanm.easydashcam.R
import com.juanm.easydashcam.core.DebugLogger
import com.juanm.easydashcam.core.net.GpsocketClient
import com.juanm.easydashcam.core.session.CameraSession
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

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
        private const val STALL_RETRIES = 2
        private const val STALL_RETRY_DELAY_MS = 3_000L

        fun downloadIntent(context: Context, f: GpsocketClient.CameraFile) =
            Intent(context, DownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_ID, f.id)
                putExtra(EXTRA_KIND, f.kind)
                putExtra(EXTRA_SIZE, f.size)
                f.timestamp?.let { putExtra(EXTRA_TS, it.toString()) }
            }

        fun cancelIntent(context: Context, uniqueId: Int) = Intent(context, DownloadService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_ID, uniqueId)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<GpsocketClient.CameraFile>(Channel.UNLIMITED)

    /** Items sent to [queue] and not yet received — Channel.isEmpty is experimental. */
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** uniqueIds from enqueue until finishFile — rejects duplicate downloads. */
    private val queuedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private lateinit var session: CameraSession
    private lateinit var nm: NotificationManager

    // Written on main (onStartCommand) and reset by the IO worker.
    @Volatile private var worker = false

    /** Latest delivered startId — stopSelfResult uses it so an intent
     *  queued mid-shutdown keeps the service alive. */
    private var lastStartId = 0

    // Queue counters for "file N of M" in the notification.
    @Volatile private var enqueued = 0

    @Volatile private var finished = 0

    override fun onCreate() {
        super.onCreate()
        session = (application as EasyDashcamApp).session
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_downloads),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT_ID,
                getString(R.string.channel_download_results),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                val f = GpsocketClient.CameraFile(
                    kind = intent.getIntExtra(EXTRA_KIND, 0),
                    id = intent.getIntExtra(EXTRA_ID, 0),
                    timestamp = intent.getStringExtra(EXTRA_TS)
                        ?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() },
                    size = intent.getLongExtra(EXTRA_SIZE, 0)
                )
                // A re-queue after cancel must clear the mark or the worker
                // kills it again instantly — the set lived until process death.
                cancelled -= f.uniqueId
                // queuedIds lives from enqueue to finishFile — a second
                // DOWNLOAD intent for an in-flight clip would write a
                // duplicate MediaStore entry.
                if (!queuedIds.add(f.uniqueId)) return START_NOT_STICKY
                pending.incrementAndGet()
                queue.trySend(f)
                enqueued++
                ensureWorker()
            }
            ACTION_CANCEL -> {
                cancelled += intent.getIntExtra(EXTRA_ID, -1)
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureWorker() {
        if (worker) return
        worker = true
        startForeground(NOTIF_ID, notification(getString(R.string.notif_preparing)))
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
                    pending.decrementAndGet()
                    if (f.uniqueId in cancelled) {
                        finishFile(f.uniqueId, error = getString(R.string.dl_err_cancelled))
                        continue
                    }
                    downloadOne(f)
                }
            } finally {
                wakeLock.release()
            }
            // Shutdown decisions run on Main, where onStartCommand also runs —
            // an enqueue racing the idle timeout can't slip between the queue
            // check and stopSelfResult, which itself no-ops if a newer start
            // request arrived.
            kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                if (pending.get() == 0) {
                    worker = false
                    enqueued = 0
                    finished = 0
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(lastStartId)
                } else {
                    worker = false
                    ensureWorker()
                }
            }
        }
    }

    private suspend fun downloadOne(f: GpsocketClient.CameraFile) {
        // The protocol has no resume offset — 0x0503 always streams from
        // byte 0 — so a stalled transfer can only be retried as a fresh
        // full download. Two retries before surfacing the stall.
        var attemptsLeft = STALL_RETRIES
        while (true) {
            if (downloadAttempt(f, lastAttempt = attemptsLeft <= 0)) return
            if (f.uniqueId in cancelled) {
                finishFile(f.uniqueId, error = getString(R.string.dl_err_cancelled))
                return
            }
            attemptsLeft--
            kotlinx.coroutines.delay(STALL_RETRY_DELAY_MS)
        }
    }

    /** One transfer attempt; true = terminal state reached. */
    private suspend fun downloadAttempt(f: GpsocketClient.CameraFile, lastAttempt: Boolean): Boolean {
        // open() does a MediaStore insert — it can throw (full storage,
        // revoked volume), and on this supervisor scope an uncaught
        // exception crashes the app.
        val dest = runCatching { ClipStore.open(this, f) }.getOrNull()
        if (dest == null) {
            finishFile(f.uniqueId, error = getString(R.string.dl_err_no_dest))
            return true
        }
        val label = dest.label
        val out = dest.out

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
                    if (f.uniqueId in cancelled) {
                        // The client drains the rest of the camera's stream
                        // before it returns (seconds for a large clip); show
                        // the cancel now rather than after the drain.
                        session.updateDownload(
                            f.uniqueId,
                            CameraSession.DownloadState(
                                got,
                                total,
                                done = true,
                                error = getString(R.string.dl_err_cancelled)
                            )
                        )
                        throw CancellationException("user cancelled")
                    }
                    // The callback fires per 32 KiB chunk — every emission
                    // recomposes the whole Files screen, so cap UI progress
                    // at ~2/s (completion always posts).
                    val now = System.currentTimeMillis()
                    if (now - lastNotifMs >= 500 || got >= total) {
                        lastNotifMs = now
                        val elapsed = now - startMs
                        val eta = if (elapsed > 1500 && got in 1 until total) {
                            (total - got) * elapsed / got / 1000
                        } else {
                            null
                        }
                        session.updateDownload(
                            f.uniqueId,
                            CameraSession.DownloadState(got, total, etaSec = eta)
                        )
                        nm.notify(
                            NOTIF_ID,
                            notification(
                                getString(R.string.notif_progress, label, got / 1024, total / 1024) +
                                    (eta?.let { e -> getString(R.string.notif_eta, e) } ?: "") +
                                    getString(R.string.notif_file_n, finished + 1, enqueued),
                                f.uniqueId
                            )
                        )
                    }
                }
            }
        } catch (_: CancellationException) {
            failFile(dest, f.uniqueId, getString(R.string.dl_err_cancelled))
            return true
        } catch (e: java.io.IOException) {
            // Storage full / MediaStore stream broke mid-write — fail the
            // file instead of crashing the app on this supervisor scope.
            failFile(dest, f.uniqueId, e.message ?: e.javaClass.simpleName)
            return true
        }
        when (result) {
            is GpsocketClient.DownloadResult.Success -> {
                val magic = head.toByteArray()
                val isJpeg = magic.size >= 2 &&
                    magic[0] == 0xFF.toByte() &&
                    magic[1] == 0xD8.toByte()
                runCatching { ClipStore.commit(this, dest, isJpeg) }
                    .onFailure {
                        failFile(
                            dest,
                            f.uniqueId,
                            it.message ?: it.javaClass.simpleName
                        )
                        return true
                    }
                finishFile(f.uniqueId)
            }
            is GpsocketClient.DownloadResult.Refused ->
                // feff most often means the camera is still writing this record
                failFile(dest, f.uniqueId, getString(R.string.dl_err_refused))
            is GpsocketClient.DownloadResult.Incomplete -> {
                // Retry needs a clean row — the partial MediaStore entry is
                // dropped before the next attempt re-opens it.
                ClipStore.fail(this, dest)
                if (!lastAttempt && f.uniqueId !in cancelled) return false
                finishFile(
                    f.uniqueId,
                    error = getString(R.string.dl_err_stalled, result.bytes, result.expected)
                )
            }
            is GpsocketClient.DownloadResult.Failed ->
                failFile(dest, f.uniqueId, result.reason)
            is GpsocketClient.DownloadResult.NotConnected ->
                failFile(dest, f.uniqueId, getString(R.string.dl_err_not_connected))
        }
        return true
    }

    private fun finishFile(id: Int, error: String? = null) {
        finished++
        queuedIds -= id
        val prev = session.downloads.value[id]
        session.updateDownload(
            id,
            CameraSession.DownloadState(prev?.received ?: 0, prev?.total ?: 0, done = true, error = error)
        )
        // Result goes on a separate id + channel — the foreground notification
        // is removed with the service and would take the result with it.
        nm.notify(
            NOTIF_RESULT_ID,
            NotificationCompat.Builder(this, CHANNEL_RESULT_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(
                    if (error == null) {
                        getString(R.string.notif_download_complete)
                    } else {
                        getString(R.string.notif_download_failed, error)
                    }
                )
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
        )
        DebugLogger.log("GPSOCKET", "download %04x -> %s".format(id ushr 8, error ?: "done"))
    }

    private fun failFile(dest: ClipStore.Dest, id: Int, error: String) {
        ClipStore.fail(this, dest)
        finishFile(id, error)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        packageManager.getLaunchIntentForPackage(packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notification(text: String, fileId: Int? = null): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
        if (fileId != null) {
            b.addAction(
                0,
                getString(R.string.cancel),
                PendingIntent.getService(
                    this,
                    fileId,
                    cancelIntent(this, fileId),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        return b.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
