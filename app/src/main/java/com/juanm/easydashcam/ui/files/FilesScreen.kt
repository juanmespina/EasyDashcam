package com.juanm.easydashcam.ui.files

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.juanm.easydashcam.core.media.AviMjpegPlayer
import com.juanm.easydashcam.core.net.GpsocketClient
import com.juanm.easydashcam.core.session.CameraSession
import com.juanm.easydashcam.ui.camera.CameraViewModel
import com.juanm.easydashcam.ui.components.ConnectionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Files tab — recordings on the camera, with background downloads. */
@Composable
fun FilesScreen(vm: CameraViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    ConnectionGate(state = state, onConnect = vm::connect) {
        FilesList(vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesList(vm: CameraViewModel) {
    val files by vm.files.collectAsStateWithLifecycle()
    val loading by vm.filesLoading.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val localFiles by vm.localFiles.collectAsStateWithLifecycle()
    var playing by remember { mutableStateOf<Uri?>(null) }

    var pendingNotif by remember { mutableStateOf<GpsocketClient.CameraFile?>(null) }
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { pendingNotif?.let(vm::download); pendingNotif = null }

    val startDownload = { f: GpsocketClient.CameraFile ->
        if (Build.VERSION.SDK_INT >= 33) {
            pendingNotif = f
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.download(f)
        }
    }

    LaunchedEffect(Unit) { vm.refreshFiles(); vm.refreshLocalFiles() }

    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = { vm.refreshFiles(); vm.refreshLocalFiles() },
        modifier = Modifier.fillMaxSize()
    ) {
        val list = files
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No recordings on the camera")
            }
            else -> {
                val grouped = list.groupBy { it.timestamp?.toLocalDate() }
                LazyColumn(Modifier.fillMaxSize()) {
                    grouped.forEach { (day, dayFiles) ->
                        item(key = "h$day") { DayHeader(day) }
                        itemsIndexed(
                            dayFiles,
                            key = { i, f -> "${f.uniqueId}_${f.timestamp}_$i" }
                        ) { _, f ->
                            val uri = localFiles[f.uniqueId]
                            FileRow(
                                file = f,
                                download = downloads[f.uniqueId],
                                local = uri != null,
                                onDownload = { startDownload(f) },
                                onPlay = uri?.let { { playing = it } }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    playing?.let { uri ->
        PlayerDialog(uri = uri, onClose = { playing = null })
    }
}

@Composable
private fun DayHeader(day: LocalDate?) {
    Text(
        day?.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())) ?: "Unknown date",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun FileRow(
    file: GpsocketClient.CameraFile,
    download: CameraSession.DownloadState?,
    local: Boolean,
    onDownload: () -> Unit,
    onPlay: (() -> Unit)?,
) {
    val (icon, kindLabel) = when (file.kindChar) {
        'L' -> Icons.Default.Lock to "Locked"
        'J' -> Icons.Default.Photo to "Photo"
        else -> Icons.Default.Videocam to "Video"
    }
    ListItem(
        leadingContent = { Icon(icon, contentDescription = kindLabel) },
        headlineContent = {
            Text(file.timestamp?.format(DateTimeFormatter.ofPattern("HH:mm:ss")) ?: "id 0x%04x".format(file.id))
        },
        supportingContent = {
            Text("$kindLabel · ${"%.1f".format(file.size / 1024f)} MB${if (local) " · downloaded" else ""}")
        },
        trailingContent = { DownloadTrailing(download, local, onDownload, onPlay) }
    )
}

@Composable
private fun DownloadTrailing(
    download: CameraSession.DownloadState?,
    local: Boolean,
    onDownload: () -> Unit,
    onPlay: (() -> Unit)? = null
) {
    when {
        download?.error != null -> Icon(
            Icons.Default.ErrorOutline,
            contentDescription = download.error,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        onPlay != null -> IconButton(onClick = onPlay) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play downloaded file",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        download?.done == true || local -> Icon(
            Icons.Default.CheckCircle,
            contentDescription = "Downloaded",
            tint = MaterialTheme.colorScheme.primary
        )
        download != null && download.total > 0 -> Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { (download.received.toFloat() / download.total).coerceIn(0f, 1f) },
                modifier = Modifier.size(28.dp)
            )
        }
        download != null -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
        else -> IconButton(onClick = onDownload) {
            Icon(Icons.Default.Download, contentDescription = "Download")
        }
    }
}

/** Full-screen MJPEG playback of a downloaded clip, with seek controls. */
@Composable
private fun PlayerDialog(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pos by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var indexed by remember { mutableStateOf(false) }
    val player = remember(uri) {
        AviMjpegPlayer(java.io.FileInputStream(
            context.contentResolver.openFileDescriptor(uri, "r")!!.fileDescriptor).channel)
    }

    DisposableEffect(uri) { onDispose { player.close() } }

    LaunchedEffect(uri) {
        try {
            player.index()
            indexed = true
            var lastDecoded = -1
            while (true) {
                if (pos >= player.frames.size) { paused = true }
                // Decode when playing, or once after the slider moved while paused.
                if (paused && pos == lastDecoded) { delay(80); continue }
                if (pos >= player.frames.size) { delay(80); continue }
                frame = player.frameAt(pos)
                lastDecoded = pos
                if (paused) { delay(80); continue }
                pos++
                delay(player.frameMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                error != null -> Text(
                    "Cannot play: $error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
                frame != null -> Image(
                    bitmap = frame!!.asImageBitmap(),
                    contentDescription = "Playback",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            if (indexed && player.frames.size > 1) {
                val count = player.frames.size
                val skip = (1000L / player.frameMs.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                    Slider(
                        value = pos.toFloat(),
                        onValueChange = {
                            pos = it.toInt().coerceIn(0, count - 1)
                        },
                        valueRange = 0f..(count - 1).toFloat()
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { pos = (pos - skip).coerceAtLeast(0); paused = false }) {
                            Icon(Icons.Default.Replay10, contentDescription = "Back 1 s", tint = Color.White)
                        }
                        IconButton(onClick = {
                            paused = !paused
                            if (!paused && pos >= count) pos = 0
                        }) {
                            Icon(
                                if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (paused) "Play" else "Pause",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { pos = (pos + skip).coerceAtMost(count - 1); paused = false }) {
                            Icon(Icons.Default.Forward10, contentDescription = "Forward 1 s", tint = Color.White)
                        }
                        Text(
                            "%.1f / %.1f s".format(pos * player.frameMs / 1000.0, player.durationSec),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
        }
    }
}
