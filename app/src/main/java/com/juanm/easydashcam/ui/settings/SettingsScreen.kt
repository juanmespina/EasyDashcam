package com.juanm.easydashcam.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.juanm.easydashcam.R
import com.juanm.easydashcam.core.settings.CameraSetting
import com.juanm.easydashcam.core.settings.SETTING_DESCRIPTIONS
import com.juanm.easydashcam.ui.camera.CameraViewModel
import com.juanm.easydashcam.ui.components.ConnectionGate

/** Settings tab — rows generated from the camera's own menu XML. */
@Composable
fun SettingsScreen(vm: CameraViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    ConnectionGate(state = state, onConnect = vm::connect) {
        SettingsList(vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsList(vm: CameraViewModel) {
    val categories by vm.settings.collectAsStateWithLifecycle()
    val values by vm.settingValues.collectAsStateWithLifecycle()
    val loading by vm.settingsLoading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadSettings() }

    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = { vm.loadSettings(force = true) },
        modifier = Modifier.fillMaxSize()
    ) {
        val cats = categories
        when {
            cats == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            cats.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_settings))
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                cats.forEach { cat ->
                    item(key = "cat_${cat.name}") {
                        Text(
                            cat.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                        )
                    }
                    itemsIndexed(
                        cat.settings.filter { it.type != 0x01 }, // skip action rows (Format, reset)
                        key = { i, s -> "${s.id}_$i" } // menu ids are not guaranteed unique
                    ) { _, s ->
                        SettingRow(
                            setting = s,
                            raw = values[s.id],
                            onChange = { v -> vm.setSetting(s.id, v) }
                        )
                        HorizontalDivider()
                    }
                }
                item(key = "about") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.app_name)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.about_version,
                                    com.juanm.easydashcam.BuildConfig.VERSION_NAME
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(setting: CameraSetting, raw: ByteArray?, onChange: (Int) -> Unit) {
    val value = raw?.firstOrNull()?.toInt()?.and(0xFF)
    val desc = SETTING_DESCRIPTIONS[setting.id]
    val supporting: (@Composable () -> Unit)? = desc?.let { d ->
        { Text(stringResource(d), style = MaterialTheme.typography.bodySmall) }
    }
    when {
        setting.isToggle -> {
            val on = value == setting.options[1].id
            ListItem(
                headlineContent = { Text(setting.name) },
                supportingContent = supporting,
                trailingContent = {
                    Switch(
                        checked = on,
                        onCheckedChange = { onChange(if (it) setting.options[1].id else setting.options[0].id) },
                        enabled = value != null
                    )
                }
            )
        }
        setting.isEnum -> {
            var open by remember { mutableStateOf(false) }
            val current = setting.options.firstOrNull { it.id == value }?.name ?: "…"
            ListItem(
                headlineContent = { Text(setting.name) },
                supportingContent = supporting,
                trailingContent = {
                    Box {
                        TextButton(onClick = { open = true }, enabled = value != null) {
                            Text(current)
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            setting.options.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt.name) },
                                    onClick = {
                                        open = false
                                        onChange(opt.id)
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
        else -> ListItem( // read-only (e.g. Version) — may be a string param
            headlineContent = { Text(setting.name) },
            supportingContent = supporting,
            trailingContent = {
                Text(
                    setting.options.firstOrNull()?.name ?: raw?.let {
                        if (it.size > 1) {
                            String(it, Charsets.UTF_8).trimEnd(' ', '\u0000')
                        } else {
                            "0x%02x".format(value ?: 0)
                        }
                    } ?: "…"
                )
            }
        )
    }
}
