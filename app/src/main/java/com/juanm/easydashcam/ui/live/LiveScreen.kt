package com.juanm.easydashcam.ui.live

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.juanm.easydashcam.R
import com.juanm.easydashcam.core.net.RtspMjpegClient
import com.juanm.easydashcam.ui.camera.CameraViewModel
import com.juanm.easydashcam.ui.components.ConnectionGate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live tab — RTSP MJPEG stream from the camera. */
@Composable
fun LiveScreen(vm: CameraViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val cameraHost by vm.cameraHost.collectAsStateWithLifecycle()
    // Modes are global on the camera: a download steals the preview channel.
    val transferActive by vm.transferActive.collectAsStateWithLifecycle()
    ConnectionGate(state = state, onConnect = vm::connect) {
        LivePlayer(
            cameraHost,
            transferActive,
            vm::enterPreviewMode,
            vm::exitPreviewMode,
            vm::postNotice
        )
    }
}

@Composable
private fun LivePlayer(
    cameraHost: String,
    transferActive: Boolean,
    onArmed: suspend () -> Unit,
    onDisarmed: () -> Unit,
    onNotice: (String) -> Unit
) {
    var status by remember { mutableStateOf<Status>(Status.Buffering) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var muted by remember { mutableStateOf(false) }
    val client = remember(cameraHost) { RtspMjpegClient(cameraHost) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshotSavedMsg = stringResource(R.string.snapshot_saved)
    val snapshotFailedMsg = stringResource(R.string.snapshot_failed)

    // A mounted dashcam-view phone must not sleep while streaming.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Stream only while the app is in the foreground. repeatOnLifecycle
    // restarts the session on return; the ON_STOP observer closes the sockets
    // because DatagramSocket.receive() is not interruptible by cancellation.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                client.stop() // UDP only — lets run() send TEARDOWN
                onDisarmed() // drop GPSOCKET preview mode too
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            client.stop()
            onDisarmed()
        }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            status = Status.Buffering
            try {
                runCatching { onArmed() } // GPSOCKET preview mode (0x01)
                client.run { bmp ->
                    frame = bmp
                    if (status != Status.Playing) status = Status.Playing
                }
                if (status == Status.Playing) status = Status.Ended
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Socket errors caused by our own stop() must not flash an error.
                if (currentCoroutineContext().isActive) {
                    status = Status.Error(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        frame?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.live_preview),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        when (val s = status) {
            Status.Buffering -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Text(
                    stringResource(R.string.waiting_stream),
                    color = Color.White,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            is Status.Error -> Text(
                stringResource(R.string.stream_error, s.msg),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(16.dp)
            )
            Status.Ended -> Text(
                stringResource(R.string.stream_ended),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(16.dp)
            )
            Status.Playing -> Row(
                Modifier.align(Alignment.BottomEnd).padding(8.dp)
            ) {
                IconButton(onClick = {
                    muted = !muted
                    client.setAudioMuted(muted)
                }) {
                    Icon(
                        if (muted) {
                            Icons.AutoMirrored.Filled.VolumeOff
                        } else {
                            Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = stringResource(
                            if (muted) R.string.unmute else R.string.mute
                        ),
                        tint = Color.White
                    )
                }
                IconButton(onClick = {
                    frame?.let { bmp ->
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { saveSnapshot(context, bmp) }
                            onNotice(if (ok) snapshotSavedMsg else snapshotFailedMsg)
                        }
                    }
                }) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = stringResource(R.string.save_snapshot),
                        tint = Color.White
                    )
                }
            }
        }
        if (transferActive) {
            Text(
                stringResource(R.string.download_in_progress),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

/** Writes one frame to Pictures/EasyDashcam; false on any failure. */
private fun saveSnapshot(context: Context, bmp: Bitmap): Boolean {
    val name = "snap_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
    if (Build.VERSION.SDK_INT < 29) {
        val f = java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), name)
        return runCatching {
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        }.isSuccess
    }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EasyDashcam")
    }
    val uri = context.contentResolver.insert(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        values
    )
        ?: return false
    val ok = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } == true
    }.getOrDefault(false)
    if (!ok) runCatching { context.contentResolver.delete(uri, null, null) }
    return ok
}

private sealed interface Status {
    data object Buffering : Status
    data object Playing : Status
    data object Ended : Status
    data class Error(val msg: String) : Status
}
