package com.juanm.easydashcam.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.juanm.easydashcam.core.net.GpsocketClient

/**
 * Gates a screen behind camera connectivity: shows the real content when
 * connected, otherwise a status card with Connect. Wrap each feature screen
 * so connection handling lives in exactly one place.
 */
@Composable
fun ConnectionGate(
    state: GpsocketClient.State,
    onConnect: () -> Unit,
    content: @Composable () -> Unit
) {
    if (state is GpsocketClient.State.Connected) {
        content()
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    when (state) {
                        is GpsocketClient.State.Connecting -> "Connecting to camera…"
                        is GpsocketClient.State.Error ->
                            "Error: ${state.cause.message ?: state.cause.javaClass.simpleName}"
                        else -> "Join the camera Wi-Fi hotspot, then connect"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                when (state) {
                    is GpsocketClient.State.Connecting -> CircularProgressIndicator()
                    else -> Button(onClick = onConnect) { Text("Connect") }
                }
            }
        }
    }
}
