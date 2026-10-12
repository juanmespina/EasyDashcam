package com.juanm.easydashcam.ui.files

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.juanm.easydashcam.core.net.GpsocketClient
import com.juanm.easydashcam.core.session.CameraSession
import com.juanm.easydashcam.ui.camera.CameraViewModel
import com.juanm.easydashcam.ui.components.ConnectionGate
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

    var pendingNotif by remember { mutableStateOf<GpsocketClient.CameraFile?>(null) }
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { pendingNotif?.let(vm::download); pendingNotif = null }

    LaunchedEffect(Unit) { vm.refreshFiles() }

    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = vm::refreshFiles,
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
                                onDownload = {
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        pendingNotif = f
                                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        vm.download(f)
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
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
    onDownload: () -> Unit
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
        supportingContent = { Text("$kindLabel · ${"%.1f".format(file.size / 1024f)} MB") },
        trailingContent = { DownloadTrailing(download, onDownload) }
    )
}

@Composable
private fun DownloadTrailing(
    download: CameraSession.DownloadState?,
    onDownload: () -> Unit
) {
    when {
        download == null -> IconButton(onClick = onDownload) {
            Icon(Icons.Default.Download, contentDescription = "Download")
        }
        download.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = download.error,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
        download.done -> Icon(
            Icons.Default.CheckCircle,
            contentDescription = "Downloaded",
            tint = MaterialTheme.colorScheme.primary
        )
        download.total > 0 -> Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { (download.received.toFloat() / download.total).coerceIn(0f, 1f) },
                modifier = Modifier.size(28.dp)
            )
        }
        else -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
    }
}
