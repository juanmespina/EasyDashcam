package com.juanm.easydashcam.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector

/** App destinations — add a tab/screen here and one NavHost entry in MainActivity. */
data class Destination(val route: String, val label: String, val icon: ImageVector)

object Destinations {
    val Live = Destination("live", "Live", Icons.Default.Videocam)
    val Files = Destination("files", "Files", Icons.Default.FolderOpen)
    val Settings = Destination("settings", "Settings", Icons.Default.Settings)
    val Debug = Destination("debug", "Debug log", Icons.Default.BugReport)

    val bottomTabs = listOf(Live, Files, Settings)
}
