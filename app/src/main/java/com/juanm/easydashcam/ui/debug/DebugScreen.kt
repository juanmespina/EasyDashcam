package com.juanm.easydashcam.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.juanm.easydashcam.R
import com.juanm.easydashcam.core.DebugLogger
import com.juanm.easydashcam.core.net.GpsocketClient

/** Raw frame log + protocol actions, for development and field debugging. */
@Composable
fun DebugScreen(
    state: GpsocketClient.State,
    frameLog: List<String>,
    onMenu: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
    onDownload: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val probing = state is GpsocketClient.State.Connected
        val context = LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMenu, enabled = probing) {
                Text(stringResource(R.string.dbg_menu_xml))
            }
            OutlinedButton(onClick = onFiles, enabled = probing) {
                Text(stringResource(R.string.dbg_files))
            }
            OutlinedButton(onClick = onSettings, enabled = probing) {
                Text(stringResource(R.string.dbg_settings))
            }
            OutlinedButton(onClick = onDownload, enabled = probing) {
                Text(stringResource(R.string.dbg_download))
            }
            // The tablet's logd drops app logs — this file is the only
            // way to get field captures off the device without USB.
            OutlinedButton(onClick = { shareLog(context) }) {
                Text(stringResource(R.string.dbg_share_log))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.dbg_camera_frames),
            style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(frameLog) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

private fun shareLog(context: android.content.Context) {
    val src = DebugLogger.logFile ?: return
    val shareDir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
    val dst = java.io.File(shareDir, "easydashcam-debug.log")
    runCatching { src.copyTo(dst, overwrite = true) }.onFailure { return }
    val uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", dst)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(
        send, context.getString(R.string.dbg_share_title)))
}
