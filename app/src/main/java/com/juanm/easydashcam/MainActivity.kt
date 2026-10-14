package com.juanm.easydashcam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.juanm.easydashcam.ui.camera.CameraViewModel
import com.juanm.easydashcam.ui.debug.DebugScreen
import com.juanm.easydashcam.ui.files.FilesScreen
import com.juanm.easydashcam.ui.live.LiveScreen
import com.juanm.easydashcam.ui.navigation.Destinations
import com.juanm.easydashcam.ui.settings.SettingsScreen
import com.juanm.easydashcam.ui.theme.EasyDashcamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EasyDashcamTheme {
                AppRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: CameraViewModel = viewModel()) {
    val nav = rememberNavController()
    val state by vm.state.collectAsStateWithLifecycle()
    val frameLog by vm.frameLog.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    var menuOpen by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    val notices by vm.notices.collectAsStateWithLifecycle()
    LaunchedEffect(notices) {
        notices.lastOrNull()?.let {
            snackbar.showSnackbar(it)
            vm.consumeNotice(it)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.menu_overflow))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (BuildConfig.DEBUG) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Destinations.Debug.labelRes)) },
                                    onClick = {
                                        menuOpen = false
                                        nav.navigate(Destinations.Debug.route)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_disconnect)) },
                                onClick = {
                                    menuOpen = false
                                    vm.disconnect()
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                Destinations.bottomTabs.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            nav.navigate(dest.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(dest.icon,
                                contentDescription = stringResource(dest.labelRes))
                        },
                        label = { Text(stringResource(dest.labelRes)) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Destinations.Live.route,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            composable(Destinations.Live.route) {
                LiveScreen(vm = vm)
            }
            composable(Destinations.Files.route) {
                FilesScreen(vm = vm)
            }
            composable(Destinations.Settings.route) {
                SettingsScreen(vm = vm)
            }
            composable(Destinations.Debug.route) {
                DebugScreen(
                    state = state,
                    frameLog = frameLog,
                    onMenu = vm::fetchMenu,
                    onFiles = vm::fetchFiles,
                    onSettings = vm::fetchSettings,
                    onDownload = vm::downloadFirst
                )
            }
        }
    }
}
