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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMenu, enabled = probing) { Text("Menu XML") }
            OutlinedButton(onClick = onFiles, enabled = probing) { Text("Files") }
            OutlinedButton(onClick = onSettings, enabled = probing) { Text("Settings") }
            OutlinedButton(onClick = onDownload, enabled = probing) { Text("Download") }
        }
        Spacer(Modifier.height(16.dp))
        Text("Camera frames", style = MaterialTheme.typography.labelLarge)
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
