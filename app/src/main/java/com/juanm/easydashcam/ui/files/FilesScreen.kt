package com.juanm.easydashcam.ui.files

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
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
    var selected by remember { mutableStateOf<GpsocketClient.CameraFile?>(null) }
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
                            FileRow(
                                file = f,
                                download = downloads[f.uniqueId],
                                local = localFiles.containsKey(f.uniqueId),
                                onDownload = { startDownload(f) },
                                onClick = { selected = f }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    selected?.let { f ->
        val uri = localFiles[f.uniqueId]
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            FileDetail(
                file = f,
                local = uri != null,
                onDownload = { selected = null; startDownload(f) },
                onPlay = uri?.let { { playing = it } }
            )
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
    onClick: () -> Unit,
) {
    val (icon, kindLabel) = when (file.kindChar) {
        'L' -> Icons.Default.Lock to "Locked"
        'J' -> Icons.Default.Photo to "Photo"
        else -> Icons.Default.Videocam to "Video"
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = kindLabel) },
        headlineContent = {
            Text(file.timestamp?.format(DateTimeFormatter.ofPattern("HH:mm:ss")) ?: "id 0x%04x".format(file.id))
        },
        supportingContent = {
            Text("$kindLabel · ${"%.1f".format(file.size / 1024f)} MB${if (local) " · downloaded" else ""}")
        },
        trailingContent = { DownloadTrailing(download, local, onDownload) }
    )
}

@Composable
private fun DownloadTrailing(
    download: CameraSession.DownloadState?,
    local: Boolean,
    onDownload: () -> Unit
) {
    when {
        download?.error != null -> Icon(
            Icons.Default.ErrorOutline,
            contentDescription = download.error,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
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

@Composable
private fun FileDetail(
    file: GpsocketClient.CameraFile,
    local: Boolean,
    onDownload: () -> Unit,
    onPlay: (() -> Unit)?
) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth()) {
        Text(
            file.timestamp?.format(DateTimeFormatter.ofPattern("MMM d, HH:mm:ss", Locale.getDefault()))
                ?: "Record 0x%04x".format(file.id),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "kind '${file.kindChar}' · ${file.size} KiB · id 0x%04x".format(file.id),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (onPlay != null) {
                Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(" Play")
                }
            }
            if (!local) {
                Button(onClick = onDownload, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Text(" Download")
                }
            }
        }
    }
}

/** Full-screen MJPEG playback of a downloaded clip. */
@Composable
private fun PlayerDialog(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val player = remember(uri) {
        AviMjpegPlayer { context.contentResolver.openInputStream(uri)!! }
    }

    LaunchedEffect(uri) {
        try {
            player.play { frame = it }
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
        }
    }
}
