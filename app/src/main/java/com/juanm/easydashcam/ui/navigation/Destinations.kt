package com.juanm.easydashcam.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import com.juanm.easydashcam.R

/** App destinations — add a tab/screen here and one NavHost entry in MainActivity. */
data class Destination(val route: String, @param:StringRes val labelRes: Int, val icon: ImageVector)

object Destinations {
    val Live = Destination("live", R.string.nav_live, Icons.Default.Videocam)
    val Files = Destination("files", R.string.nav_files, Icons.Default.FolderOpen)
    val Settings = Destination("settings", R.string.nav_settings, Icons.Default.Settings)
    val Debug = Destination("debug", R.string.nav_debug, Icons.Default.BugReport)

    val bottomTabs = listOf(Live, Files, Settings)
}
