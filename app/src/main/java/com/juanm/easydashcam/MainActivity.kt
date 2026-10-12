package com.juanm.easydashcam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.juanm.easydashcam.core.net.GpsocketClient
import com.juanm.easydashcam.ui.camera.CameraViewModel
import com.juanm.easydashcam.ui.theme.EasyDashcamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EasyDashcamTheme {
                val vm: CameraViewModel = viewModel()
                val state by vm.state.collectAsState()
                val log by vm.frameLog.collectAsState()
                HomeScreen(
                    state = state,
                    frameLog = log,
                    onConnect = vm::connect,
                    onDisconnect = vm::disconnect,
                    onMenu = vm::fetchMenu,
                    onFiles = vm::fetchFiles,
                    onSettings = vm::fetchSettings,
                    onDownload = vm::downloadFirst
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: GpsocketClient.State,
    frameLog: List<String>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onMenu: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
    onDownload: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Easy Dashcam") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                when (state) {
                    is GpsocketClient.State.Connected -> "Connected to camera"
                    is GpsocketClient.State.Connecting -> "Connecting…"
                    is GpsocketClient.State.Disconnected -> "Not connected — join the camera Wi-Fi hotspot first"
                    is GpsocketClient.State.Error -> "Error: ${state.cause.message ?: state.cause.javaClass.simpleName}"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onConnect, enabled = state is GpsocketClient.State.Disconnected || state is GpsocketClient.State.Error) {
                    Text("Connect")
                }
                OutlinedButton(onClick = onDisconnect, enabled = state is GpsocketClient.State.Connecting || state is GpsocketClient.State.Connected) {
                    Text("Disconnect")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val probing = state is GpsocketClient.State.Connected
                OutlinedButton(onClick = onMenu, enabled = probing) {
                    Text("Menu XML")
                }
                OutlinedButton(onClick = onFiles, enabled = probing) {
                    Text("Files")
                }
                OutlinedButton(onClick = onSettings, enabled = probing) {
                    Text("Settings")
                }
                OutlinedButton(onClick = onDownload, enabled = probing) {
                    Text("Download")
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Camera frames (debug)", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(frameLog) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
