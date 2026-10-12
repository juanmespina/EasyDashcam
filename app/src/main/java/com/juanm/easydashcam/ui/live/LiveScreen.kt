package com.juanm.easydashcam.ui.live

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.juanm.easydashcam.core.net.RtspMjpegClient
import com.juanm.easydashcam.ui.camera.CameraViewModel
import com.juanm.easydashcam.ui.components.ConnectionGate

/** Live tab — RTSP MJPEG stream from the camera. */
@Composable
fun LiveScreen(vm: CameraViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    ConnectionGate(state = state, onConnect = vm::connect) {
        LivePlayer(onArmed = vm::enterPreviewMode)
    }
}

@Composable
private fun LivePlayer(onArmed: suspend () -> Unit) {
    var status by remember { mutableStateOf<Status>(Status.Buffering) }
    var frame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val client = remember { RtspMjpegClient() }

    // The camera needs the GPSOCKET session in preview mode (0x0000 arg=01)
    // before it pushes RTP media — verified gap in the Viidure pcaps.
    LaunchedEffect(Unit) {
        runCatching { onArmed() }
        runCatching {
            client.run { bmp ->
                frame = bmp
                if (status != Status.Playing) status = Status.Playing
            }
            status = Status.Ended
        }.onFailure {
            status = Status.Error(it.message ?: it.javaClass.simpleName)
        }
    }
    DisposableEffect(Unit) { onDispose { client.close() } }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        frame?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Live preview",
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
                    "Waiting for stream…",
                    color = Color.White,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            is Status.Error -> Text(
                "Stream error: ${s.msg}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(16.dp)
            )
            Status.Ended -> Text(
                "Stream ended",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(16.dp)
            )
            Status.Playing -> Unit
        }
    }
}

private sealed interface Status {
    data object Buffering : Status
    data object Playing : Status
    data object Ended : Status
    data class Error(val msg: String) : Status
}
