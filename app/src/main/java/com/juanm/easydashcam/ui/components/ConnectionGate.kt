package com.juanm.easydashcam.ui.components

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.juanm.easydashcam.R
import com.juanm.easydashcam.core.net.GpsocketClient

/**
 * Gates a screen behind camera connectivity: shows the real content when
 * connected, otherwise a status card with Connect. Wrap each feature screen
 * so connection handling lives in exactly one place.
 */
@Composable
fun ConnectionGate(state: GpsocketClient.State, onConnect: () -> Unit, content: @Composable () -> Unit) {
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
                        is GpsocketClient.State.Connecting ->
                            stringResource(R.string.connecting)
                        is GpsocketClient.State.Error -> stringResource(
                            R.string.connection_error,
                            state.cause.message ?: state.cause.javaClass.simpleName
                        )
                        else -> stringResource(R.string.join_hotspot)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                when (state) {
                    is GpsocketClient.State.Connecting -> CircularProgressIndicator()
                    else -> {
                        val context = LocalContext.current
                        Button(onClick = onConnect) {
                            Text(stringResource(R.string.connect))
                        }
                        OutlinedButton(onClick = {
                            // The system Wi-Fi panel (29+) doesn't leave the app.
                            val action = if (Build.VERSION.SDK_INT >= 29) {
                                Settings.Panel.ACTION_WIFI
                            } else {
                                Settings.ACTION_WIFI_SETTINGS
                            }
                            context.startActivity(Intent(action))
                        }) { Text(stringResource(R.string.open_wifi_settings)) }
                    }
                }
            }
        }
    }
}
