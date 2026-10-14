package com.juanm.easydashcam.ui.files

import android.Manifest
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.juanm.easydashcam.R
import com.juanm.easydashcam.core.media.AviMjpegPlayer
import com.juanm.easydashcam.core.net.GpsocketClient
import com.juanm.easydashcam.core.session.CameraSession
import com.juanm.easydashcam.ui.camera.CameraViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Files tab — recordings on the camera, with background downloads.
 *  No ConnectionGate: the cached list and On device browsing stay usable
 *  while disconnected; a failed fetch posts a notice and restores the
 *  disk-cached list. */
@Composable
fun FilesScreen(vm: CameraViewModel) {
    FilesList(vm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesList(vm: CameraViewModel) {
    val files by vm.files.collectAsStateWithLifecycle()
    val loading by vm.filesLoading.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val localFiles by vm.localFiles.collectAsStateWithLifecycle()
    val partialLocal by vm.partialLocal.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var playing by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }
    var streaming by remember { mutableStateOf<GpsocketClient.CameraFile?>(null) }
    var actionsFor by remember { mutableStateOf<Pair<Uri, GpsocketClient.CameraFile>?>(null) }
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    var sortMode by remember { mutableStateOf(SortMode.NEWEST) }
    var sortMenu by remember { mutableStateOf(false) }
    var dayFilter by remember { mutableStateOf<LocalDate?>(null) }
    var filterMenu by remember { mutableStateOf(false) }
    var pickDay by remember { mutableStateOf(false) }
    val autoDl by vm.autoDownload.collectAsStateWithLifecycle()

    var pendingNotif by remember { mutableStateOf<GpsocketClient.CameraFile?>(null) }
    var pendingBatch by remember { mutableStateOf(false) }
    var batchTrigger by remember { mutableIntStateOf(0) }
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingNotif?.let(vm::download); pendingNotif = null
        if (pendingBatch) { pendingBatch = false; batchTrigger++ }
    }

    val startDownload = { f: GpsocketClient.CameraFile ->
        if (Build.VERSION.SDK_INT >= 33) {
            pendingNotif = f
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.download(f)
        }
    }
    val startBatch: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33) {
            pendingBatch = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            batchTrigger++
        }
    }

    LaunchedEffect(Unit) { vm.refreshFiles(); vm.refreshLocalFiles() }

    var filter by remember { mutableStateOf(MediaFilter.ALL) }

    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            MediaFilter.entries.forEachIndexed { i, f ->
                SegmentedButton(
                    selected = filter == f,
                    onClick = { filter = f },
                    shape = SegmentedButtonDefaults.itemShape(i, MediaFilter.entries.size)
                ) { Text(stringResource(f.labelRes)) }
            }
        }
        // Locked (L) records can't be served — the camera feff-refuses their
        // downloads and Viidure's captures never show one transferred either.
        val lockedCount = files?.count { it.kindChar == 'L' } ?: 0
        // downloads emits a new map per 32 KiB chunk — without remember(),
        // the ~800-item filter/group/sort below reran per chunk on the main
        // thread for the whole transfer.
        val list = remember(files, filter, dayFilter, localFiles) {
            files?.let { all ->
                val servable = all.filter { it.kindChar != 'L' }
                val byKind = when (filter) {
                    MediaFilter.ALL -> servable
                    MediaFilter.VIDEOS -> servable.filter { it.kindChar != 'J' }
                    MediaFilter.PHOTOS -> servable.filter { it.kindChar == 'J' }
                    MediaFilter.ON_DEVICE -> servable.filter {
                        localFiles.containsKey(it.uniqueId)
                    }
                }
                if (dayFilter == null) byKind
                else byKind.filter { it.timestamp?.toLocalDate() == dayFilter }
            }
        }
        // Everything visible that isn't already on the phone or in flight.
        val pending = remember(list, localFiles, downloads) {
            list.orEmpty().filter {
                localFiles[it.uniqueId] == null && downloads[it.uniqueId] == null
            }
        }
        LaunchedEffect(batchTrigger) {
            if (batchTrigger > 0) pending.forEach(vm::download)
        }
        // Loop clips are back-to-back: a record's duration ≈ the gap to the
        // next-newer record of the same kind. Absent for photos, the newest
        // file, and boundary gaps that are too large to be loop neighbours.
        val durations = remember(list) {
            val m = HashMap<Int, Int>()
            list.orEmpty().zipWithNext().forEach { (newer, older) ->
                if (newer.kindChar == older.kindChar &&
                    newer.timestamp != null && older.timestamp != null) {
                    val gap = java.time.Duration
                        .between(older.timestamp, newer.timestamp).seconds
                    if (gap in 30..900) m[older.uniqueId] = gap.toInt()
                }
            }
            m
        }
        Row(
            Modifier.align(Alignment.End).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (pending.isNotEmpty()) {
                TextButton(onClick = startBatch) {
                    Text(stringResource(R.string.download_all,
                        pending.size, pending.sumOf { it.size } / 1024f))
                }
            }
            if (dayFilter != null) {
                FilterChip(
                    selected = true,
                    onClick = { dayFilter = null },
                    label = {
                        Text(dayFilter!!.format(
                            DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())))
                    },
                    trailingIcon = {
                        Icon(Icons.Default.Close,
                            contentDescription = stringResource(R.string.clear_filter),
                            modifier = Modifier.size(16.dp))
                    }
                )
            }
            Box {
                IconButton(onClick = { filterMenu = true }) {
                    Icon(Icons.Default.FilterAlt,
                        contentDescription = stringResource(R.string.filter_by_date))
                }
                DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                    val today = LocalDate.now()
                    listOf(
                        stringResource(R.string.date_all) to null,
                        stringResource(R.string.date_today) to today,
                        stringResource(R.string.date_yesterday) to today.minusDays(1)
                    ).forEach { (label, day) ->
                        DropdownMenuItem(
                            text = { Text(label + if (day == dayFilter) " ✓" else "") },
                            onClick = { dayFilter = day; filterMenu = false }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.date_pick)) },
                        onClick = { pickDay = true; filterMenu = false }
                    )
                }
            }
            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort,
                        contentDescription = stringResource(R.string.sort))
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(mode.labelRes) +
                                    if (mode == sortMode) " ✓" else "")
                            },
                            onClick = { sortMode = mode; sortMenu = false }
                        )
                    }
                }
            }
            // Auto-download: enqueues clips recorded after it was enabled.
            IconButton(onClick = vm::toggleAutoDownload) {
                Icon(
                    if (autoDl) Icons.Default.Sync else Icons.Default.SyncDisabled,
                    contentDescription = stringResource(
                        if (autoDl) R.string.auto_download_on else R.string.auto_download_off),
                    tint = if (autoDl) MaterialTheme.colorScheme.primary
                           else androidx.compose.material3.LocalContentColor.current
                )
            }
            IconButton(onClick = { viewMode = viewMode.next() }) {
                Icon(
                    when (viewMode) {
                        ViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList
                        ViewMode.GRID -> Icons.Default.GridView
                        ViewMode.TIMELINE -> Icons.Default.Timeline
                    },
                    contentDescription = stringResource(R.string.view_mode_switch,
                        stringResource(viewMode.labelRes))
                )
            }
        }
    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = { vm.refreshFiles(force = true); vm.refreshLocalFiles() },
        modifier = Modifier.fillMaxSize().weight(1f)
    ) {
        when {
            // PullToRefreshBox already shows its indicator while loading —
            // a second centered spinner made tab switches flash twice.
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (!loading) Text(stringResource(R.string.pull_to_refresh))
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(if (files.isNullOrEmpty()) R.string.no_recordings
                     else R.string.nothing_matches))
            }
            else -> {
                // Days always run newest-first; the sort option orders within
                // each day so the structure stays readable.
                val grouped = remember(list, sortMode) {
                    list.groupBy { it.timestamp?.toLocalDate() }
                        .entries.sortedWith(
                            compareBy(nullsLast(reverseOrder<LocalDate>())) { it.key })
                        .map { it.key to it.value.sortedWith(sortMode.comparator) }
                }
                if (viewMode == ViewMode.GRID) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 170.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        grouped.forEach { (day, dayFiles) ->
                            item(key = "h$day", span = { GridItemSpan(maxLineSpan) }) {
                                DayHeader(day)
                            }
                            itemsIndexed(
                                dayFiles,
                                key = { i, f -> "${f.uniqueId}_${f.timestamp}_$i" }
                            ) { _, f ->
                                val uri = localFiles[f.uniqueId]
                                FileTile(
                                    file = f,
                                    download = downloads[f.uniqueId],
                                    durationSec = durations[f.uniqueId],
                                    localUri = uri,
                                    partial = f.uniqueId in partialLocal,
                                    onDownload = { startDownload(f) },
                                    onPlay = uri?.let { { playing = it to (f.kindChar == 'J') } },
                                    onStream = if (uri == null) { { streaming = f } } else null,
                                    onLongPress = uri?.let { { actionsFor = it to f } },
                                    onCancel = { vm.cancelDownload(f.uniqueId) }
                                )
                            }
                        }
                        if (lockedCount > 0) {
                            item(key = "locked_footer", span = { GridItemSpan(maxLineSpan) }) {
                                LockedFooter(lockedCount)
                            }
                        }
                        item(key = "storage_footer", span = { GridItemSpan(maxLineSpan) }) {
                            StorageFooter()
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        grouped.forEach { (day, dayFiles) ->
                            item(key = "h$day") { DayHeader(day) }
                            val sorted = dayFiles
                            sorted.forEachIndexed { i, f ->
                                val uri = localFiles[f.uniqueId]
                                item(key = "${f.uniqueId}_${f.timestamp}_$i") {
                                    if (viewMode == ViewMode.TIMELINE) {
                                        TimelineRow(f, i == 0, i == sorted.lastIndex,
                                            downloads[f.uniqueId], durations[f.uniqueId],
                                            uri, f.uniqueId in partialLocal,
                                            { startDownload(f) },
                                            uri?.let { { playing = it to (f.kindChar == 'J') } },
                                            if (uri == null) { { streaming = f } } else null,
                                            uri?.let { { actionsFor = it to f } },
                                            { vm.cancelDownload(f.uniqueId) })
                                    } else {
                                        FileRow(
                                            file = f,
                                            download = downloads[f.uniqueId],
                                            durationSec = durations[f.uniqueId],
                                            localUri = uri,
                                            partial = f.uniqueId in partialLocal,
                                            onDownload = { startDownload(f) },
                                            onPlay = uri?.let { { playing = it to (f.kindChar == 'J') } },
                                            onStream = if (uri == null) { { streaming = f } } else null,
                                            onLongPress = uri?.let { { actionsFor = it to f } },
                                            onCancel = { vm.cancelDownload(f.uniqueId) }
                                        )
                                    }
                                    HorizontalDivider()
                                }
                                // Timeline mode marks recording gaps — the
                                // spaces where the camera was off matter as
                                // much as the clips.
                                if (viewMode == ViewMode.TIMELINE) {
                                    val next = sorted.getOrNull(i + 1)
                                    if (f.timestamp != null && next?.timestamp != null) {
                                        val gap = java.time.Duration
                                            .between(next.timestamp, f.timestamp).seconds
                                        if (gap > 900) item(key = "gap_$i") { GapRow(gap) }
                                    }
                                }
                            }
                        }
                        if (lockedCount > 0) {
                            item(key = "locked_footer") { LockedFooter(lockedCount) }
                        }
                        item(key = "storage_footer") { StorageFooter() }
                    }
                }
            }
        }
    }
    }

    if (pickDay) {
        val dp = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { pickDay = false },
            confirmButton = {
                TextButton(onClick = {
                    // DatePicker returns millis at UTC midnight — convert in
                    // UTC so the picked day doesn't shift with the timezone.
                    dayFilter = dp.selectedDateMillis?.let {
                        java.time.Instant.ofEpochMilli(it)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    }
                    pickDay = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pickDay = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        ) { DatePicker(state = dp) }
    }
    playing?.let { (uri, isImage) ->
        PlayerDialog(uri = uri, remote = null, isImage = isImage, vm = vm,
            onClose = { playing = null })
    }
    streaming?.let { f ->
        PlayerDialog(uri = null, remote = f, isImage = f.kindChar == 'J', vm = vm,
            onClose = { streaming = null })
    }
    actionsFor?.let { (uri, f) ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = {
                Text(f.timestamp?.format(
                    DateTimeFormatter.ofPattern("MMM d, HH:mm:ss", Locale.getDefault()))
                    ?: stringResource(R.string.downloaded_file))
            },
            text = { Text(stringResource(R.string.local_copy)) },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        playing = uri to (f.kindChar == 'J'); actionsFor = null
                    }) { Text(stringResource(R.string.play)) }
                    TextButton(onClick = {
                        shareFile(context, uri, f.kindChar == 'J'); actionsFor = null
                    }) { Text(stringResource(R.string.share)) }
                    TextButton(onClick = { vm.deleteLocal(uri); actionsFor = null }) {
                        Text(stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { actionsFor = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/** First-frame thumbnail + true duration for a downloaded clip. */
private data class ClipMeta(val thumb: Bitmap?, val durationSec: Int?)

private object ClipMetaCache {
    private val cache = android.util.LruCache<String, ClipMeta>(64)
    // Fling past a day of downloaded clips and every visible row fires a
    // probe + JPEG decode — bound it or the tablet's CPU is the bottleneck.
    private val gate = kotlinx.coroutines.sync.Semaphore(3)

    fun get(uri: Uri): ClipMeta? = cache.get(uri.toString())
    suspend fun load(context: android.content.Context, uri: Uri): ClipMeta =
        get(uri) ?: gate.withPermit {
            get(uri) ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                        AviMjpegPlayer(java.io.FileInputStream(pfd.fileDescriptor).channel).use { p ->
                            p.probe()   // header only — no 'movi' walk
                            ClipMeta(
                                thumb = p.thumbnail(360),
                                durationSec = p.probeDurationSec?.let { (it + 0.5).toInt() }
                            )
                        }
                    }
                }.getOrDefault(ClipMeta(null, null))
                // Don't cache failures — a transient error (e.g. file still
                // being finalized) shouldn't hide the thumbnail forever.
            }.also { if (it.thumb != null || it.durationSec != null) cache.put(uri.toString(), it) }
        }
}

private fun shareFile(context: android.content.Context, uri: Uri, isImage: Boolean) {
    // Pre-29 downloads are file:// URIs — raw file:// crashes the target on
    // API 24+, so route them through the FileProvider.
    val shareUri = if (uri.scheme == "file") {
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", java.io.File(uri.path!!))
    } else uri
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = if (isImage) "image/jpeg" else "video/*"
        putExtra(android.content.Intent.EXTRA_STREAM, shareUri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(
        send, context.getString(R.string.share_clip)))
}

private enum class MediaFilter(@param:StringRes val labelRes: Int) {
    ALL(R.string.filter_all), VIDEOS(R.string.filter_videos),
    PHOTOS(R.string.filter_photos), ON_DEVICE(R.string.filter_on_device)
}

private enum class ViewMode(@param:StringRes val labelRes: Int) {
    LIST(R.string.view_list), GRID(R.string.view_grid), TIMELINE(R.string.view_timeline);
    fun next() = entries[(ordinal + 1) % entries.size]
}

private enum class SortMode(
    @param:StringRes val labelRes: Int,
    val comparator: Comparator<GpsocketClient.CameraFile>
) {
    NEWEST(R.string.sort_newest, compareByDescending { it.timestamp }),
    OLDEST(R.string.sort_oldest, compareBy { it.timestamp }),
    LARGEST(R.string.sort_largest, compareByDescending { it.size }),
    SMALLEST(R.string.sort_smallest, compareBy { it.size })
}

@Composable
private fun DayHeader(day: LocalDate?) {
    Text(
        day?.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
            ?: stringResource(R.string.unknown_date),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

private fun kindIcon(f: GpsocketClient.CameraFile) = when (f.kindChar) {
    'L' -> Icons.Default.Lock
    'J' -> Icons.Default.Photo
    // Unknown kind letters (multi-channel cams may use them for rear/interior
    // feeds) stay visible instead of being silently labelled "Video".
    else -> Icons.Default.Videocam
}

@Composable
private fun kindLabel(f: GpsocketClient.CameraFile) = when (f.kindChar) {
    'L' -> stringResource(R.string.kind_locked)
    'J' -> stringResource(R.string.kind_photo)
    'A' -> stringResource(R.string.kind_video)
    else -> stringResource(R.string.kind_other, f.kindChar)
}

@Composable
private fun fileDetail(
    file: GpsocketClient.CameraFile,
    durationSec: Int?,
    download: CameraSession.DownloadState?,
    local: Boolean,
    partial: Boolean = false
): String {
    // The camera feff-refuses records it is still writing — flag rows that
    // fresh so a refusal doesn't look like a broken download.
    val stillRecording = file.timestamp?.let {
        kotlin.math.abs(java.time.Duration.between(it, java.time.LocalDateTime.now()).toMinutes()) < 3
    } == true
    return buildString {
        append(stringResource(R.string.detail_base, kindLabel(file), file.size / 1024f))
        durationSec?.let {
            append(stringResource(R.string.detail_duration, it / 60, it % 60))
        }
        download?.takeIf { !it.done }?.etaSec?.let {
            append(stringResource(R.string.detail_eta, it))
        }
        if (stillRecording) append(stringResource(R.string.detail_still_recording))
        if (local) append(stringResource(
            if (partial) R.string.detail_partial else R.string.detail_downloaded))
    }
}

/** Timeline row — a dot on a vertical rail; the rail breaks at day edges. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineRow(
    file: GpsocketClient.CameraFile,
    first: Boolean,
    last: Boolean,
    download: CameraSession.DownloadState?,
    durationSec: Int?,
    localUri: Uri?,
    partial: Boolean = false,
    onDownload: () -> Unit,
    onPlay: (() -> Unit)?,
    onStream: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
    onCancel: (() -> Unit)? = null
) {
    val rail = MaterialTheme.colorScheme.outlineVariant
    val meta = rememberClipMeta(localUri)
    ListItem(
        leadingContent = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(2.dp, 18.dp)
                    .background(if (first) Color.Transparent else rail))
                Box(Modifier.size(10.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape))
                Box(Modifier.size(2.dp, 18.dp)
                    .background(if (last) Color.Transparent else rail))
            }
        },
        headlineContent = {
            Text(file.timestamp?.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                ?: stringResource(R.string.id_fallback, file.id))
        },
        supportingContent = {
            Text(fileDetail(file, meta?.durationSec ?: durationSec, download,
                localUri != null, partial))
        },
        trailingContent = {
            DownloadTrailing(download, localUri != null, onDownload, onPlay, onStream, onCancel)
        },
        modifier = if (onLongPress != null)
            Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)
        else Modifier
    )
}

/** A break in the timeline — the camera was off or not recording. */
@Composable
private fun GapRow(gapSec: Long) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            if (gapSec >= 3600)
                stringResource(R.string.gap_hours, gapSec / 3600, gapSec % 3600 / 60)
            else stringResource(R.string.gap_minutes, gapSec / 60),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

/** Loads (and caches) a downloaded clip's thumbnail + real duration. */
@Composable
private fun rememberClipMeta(uri: Uri?): ClipMeta? {
    val context = LocalContext.current
    var meta by remember(uri) { mutableStateOf(uri?.let(ClipMetaCache::get)) }
    LaunchedEffect(uri) {
        meta = if (uri == null) null else ClipMetaCache.load(context, uri)
    }
    return meta
}

@Composable
private fun LockedFooter(count: Int) {
    Text(
        androidx.compose.ui.res.pluralStringResource(
            R.plurals.locked_clips_hidden, count, count),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
private fun StorageFooter() {
    val context = LocalContext.current
    val free = remember {
        runCatching {
            StatFs(context.getExternalFilesDir(null)?.absolutePath
                ?: Environment.getExternalStorageDirectory().path).availableBytes
        }.getOrNull()
    }
    if (free != null) {
        Text(
            stringResource(R.string.storage_free, free / 1_073_741_824.0),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: GpsocketClient.CameraFile,
    download: CameraSession.DownloadState?,
    durationSec: Int?,
    localUri: Uri?,
    partial: Boolean = false,
    onDownload: () -> Unit,
    onPlay: (() -> Unit)?,
    onStream: (() -> Unit)?,
    onLongPress: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    val icon = kindIcon(file)
    val kindDesc = kindLabel(file)
    val meta = rememberClipMeta(localUri)
    ListItem(
        leadingContent = {
            meta?.thumb?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = kindDesc,
                    contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp))
            } ?: Icon(icon, contentDescription = kindDesc)
        },
        headlineContent = {
            Text(file.timestamp?.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                ?: stringResource(R.string.id_fallback, file.id))
        },
        supportingContent = {
            Text(fileDetail(file, meta?.durationSec ?: durationSec, download,
                localUri != null, partial))
        },
        trailingContent = {
            DownloadTrailing(download, localUri != null, onDownload, onPlay, onStream, onCancel)
        },
        modifier = if (onLongPress != null)
            Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)
        else Modifier
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTile(
    file: GpsocketClient.CameraFile,
    download: CameraSession.DownloadState?,
    durationSec: Int?,
    localUri: Uri?,
    partial: Boolean = false,
    onDownload: () -> Unit,
    onPlay: (() -> Unit)?,
    onStream: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
    onCancel: (() -> Unit)? = null,
) {
    val icon = kindIcon(file)
    val kindDesc = kindLabel(file)
    val meta = rememberClipMeta(localUri)
    Card(
        Modifier.padding(4.dp).then(
            if (onPlay != null || onLongPress != null)
                Modifier.combinedClickable(
                    onClick = { onPlay?.invoke() },
                    onLongClick = { onLongPress?.invoke() })
            else Modifier
        )
    ) {
        Column {
            meta?.thumb?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = kindDesc,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(110.dp))
            }
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = kindDesc, modifier = Modifier.size(18.dp))
                    Text(
                        file.timestamp?.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                            ?: stringResource(R.string.id_fallback, file.id),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                Text(
                    fileDetail(file, meta?.durationSec ?: durationSec, download,
                localUri != null, partial),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(Modifier.align(Alignment.End)) {
                    DownloadTrailing(download, localUri != null, onDownload, onPlay, onStream, onCancel)
                }
            }
        }
    }
}

@Composable
private fun DownloadTrailing(
    download: CameraSession.DownloadState?,
    local: Boolean,
    onDownload: () -> Unit,
    onPlay: (() -> Unit)? = null,
    onStream: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Streams straight from the camera — plays while the bytes arrive,
        // nothing kept on the phone unless the user saves it.
        if (onStream != null) IconButton(onClick = onStream) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.action_stream),
                tint = MaterialTheme.colorScheme.secondary
            )
        }
        when {
            download?.error != null -> IconButton(onClick = onDownload) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = stringResource(
                        R.string.download_failed_retry, download.error ?: ""),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
            // In-flight beats Play: while bytes are moving the row shows
            // progress + cancel, even if a local copy already exists.
            download != null && !download.done -> IconButton(
                onClick = { onCancel?.invoke() },
                enabled = onCancel != null
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (download.total > 0) {
                        CircularProgressIndicator(
                            progress = { (download.received.toFloat() / download.total).coerceIn(0f, 1f) },
                            modifier = Modifier.size(28.dp)
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                    Icon(Icons.Default.Close,
                        contentDescription = stringResource(R.string.cancel),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp))
                }
            }
            onPlay != null -> IconButton(onClick = onPlay) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.play_downloaded),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            download?.done == true || local -> Icon(
                Icons.Default.CheckCircle,
                contentDescription = stringResource(R.string.downloaded),
                tint = MaterialTheme.colorScheme.primary
            )
            else -> IconButton(onClick = onDownload) {
                Icon(Icons.Default.Download,
                    contentDescription = stringResource(R.string.download))
            }
        }
    }
}

/**
 * Full-screen MJPEG playback of a downloaded clip ([uri] != null) or a
 * streamed one ([remote] != null): the bytes are pulled into a temp file
 * while the player indexes and plays whatever has already arrived — same
 * wire path as a download, but nothing is kept unless the user saves it.
 */
@Composable
private fun PlayerDialog(
    uri: Uri?,
    remote: GpsocketClient.CameraFile?,
    isImage: Boolean,
    vm: CameraViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var frame by remember(uri, remote) { mutableStateOf<Bitmap?>(null) }
    var error by remember(uri, remote) { mutableStateOf<String?>(null) }
    var pos by remember(uri, remote) { mutableIntStateOf(0) }
    var paused by remember(uri, remote) { mutableStateOf(false) }
    var indexed by remember(uri, remote) { mutableStateOf(false) }
    var buffering by remember(uri, remote) { mutableStateOf(false) }
    var streamGot by remember(uri, remote) { mutableLongStateOf(0L) }
    var streamTotal by remember(uri, remote) { mutableLongStateOf(0L) }
    var streamDone by remember(uri, remote) { mutableStateOf(remote == null) }
    var indexedFrames by remember(uri, remote) { mutableIntStateOf(0) }
    var saved by remember(uri, remote) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Streamed clips land in cacheDir — a growing file the player re-indexes.
    val tmpFile = remember(remote) {
        remote?.let {
            java.io.File(context.cacheDir, "stream_${it.uniqueId}.part")
                .apply { createNewFile() }
        }
    }
    // The uri can be deleted between the query and the tap — fail into the
    // error state instead of crashing out of composition.
    // The ParcelFileDescriptor must outlive the player: unreferenced, GC can
    // finalize it mid-playback and close the fd the FileChannel reads on.
    // AutoCloseInputStream owns it — closing the channel closes everything.
    val player = remember(uri, remote) {
        runCatching {
            when {
                uri != null -> AviMjpegPlayer(
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        context.contentResolver.openFileDescriptor(uri, "r")!!).channel)
                tmpFile != null -> AviMjpegPlayer(java.io.FileInputStream(tmpFile).channel)
                else -> null
            }
        }.getOrNull()
    }

    DisposableEffect(player) { onDispose { player?.close(); tmpFile?.delete() } }

    LaunchedEffect(player) {
        if (player == null) {
            error = context.getString(R.string.err_file_unavailable); return@LaunchedEffect
        }
        try {
            var fetchErr: String? = null
            if (remote != null) {
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        java.io.FileOutputStream(tmpFile!!).use { fos ->
                            var lastMs = 0L
                            when (val r = vm.streamFile(remote, fos) { got, total ->
                                val now = System.currentTimeMillis()
                                // Throttle: the callback fires per 32 KiB chunk.
                                if (now - lastMs >= 300 || got >= total) {
                                    lastMs = now
                                    streamGot = got; streamTotal = total
                                }
                            }) {
                                is GpsocketClient.DownloadResult.Success -> {}
                                is GpsocketClient.DownloadResult.Refused ->
                                    fetchErr = context.getString(R.string.err_camera_refused)
                                is GpsocketClient.DownloadResult.Incomplete ->
                                    fetchErr = context.getString(R.string.err_transfer_stopped)
                                is GpsocketClient.DownloadResult.Failed ->
                                    fetchErr = r.reason
                            }
                        }
                    } finally {
                        streamDone = true
                    }
                }
            }
            // Wait until something is playable: local clips index once;
            // streams extend the index as chunks arrive.
            if (remote == null) {
                player.index()
            } else if (isImage) {
                while (!streamDone) delay(150)
                fetchErr?.let { error = it; return@LaunchedEffect }
                player.index()
            } else {
                while (player.frames.isEmpty() && !streamDone) {
                    player.extendIndex()
                    if (player.frames.isEmpty()) delay(150)
                }
                player.extendIndex()
            }
            fetchErr?.let { error = it; return@LaunchedEffect }
            if (player.frames.isEmpty()) {
                error = context.getString(R.string.err_no_video_frames); return@LaunchedEffect
            }
            indexedFrames = player.frames.size
            indexed = true
            // Audio: PCM chunks feed a self-clocking AudioTrack; seeks move the
            // PCM read position to match the video frame time.
            // Hoisted so the outer finally can stop() a write stuck on a
            // full buffer — a blocking write doesn't observe cancellation.
            var audioTrack: AudioTrack? = null
            val audioJob = if (!player.hasAudio) null else launch {
                val buf = ByteArray(8192)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(player.audioRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(if (player.audioChannels == 1)
                            AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioTrack = track
                if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); return@launch }
                var bytePos = 0L
                var lastVideoPos = -1
                var streaming = false
                try {
                    while (isActive) {
                        if (paused || pos >= player.frames.size) {
                            if (streaming) { track.pause(); streaming = false }
                            delay(60); continue
                        }
                        if (!streaming) { track.play(); streaming = true }
                        if (pos != lastVideoPos + 1) {   // seek or restart
                            bytePos = player.audioPosForMs(pos * player.frameMs)
                            track.pause(); track.flush(); track.play()
                        }
                        lastVideoPos = pos
                        val n = player.audioRead(bytePos, buf)
                        if (n <= 0) { delay(60); continue }
                        bytePos += n
                        track.write(buf, 0, n)   // blocks — paces the playback
                    }
                } finally {
                    runCatching { track.stop() }
                    track.release()
                }
            }
            var lastDecoded = -1
            try {
                while (true) {
                    // Streaming: index whatever arrived since the last pass —
                    // the scan resumes at the last complete chunk, so this is
                    // ~one header read per iteration when idle.
                    if (remote != null) {
                        player.extendIndex()
                        if (indexedFrames != player.frames.size)
                            indexedFrames = player.frames.size
                    }
                    if (pos >= player.frames.size) {
                        if (streamDone) { paused = true }   // real EOF
                        else { buffering = true; delay(120); continue }
                    }
                    // Decode when playing, or once after the slider moved while paused.
                    if (paused && pos == lastDecoded) { delay(80); continue }
                    if (pos >= player.frames.size) { delay(80); continue }
                    buffering = false
                    frame = player.frameAt(pos)
                    lastDecoded = pos
                    if (paused) { delay(80); continue }
                    pos++
                    delay(player.frameMs)
                }
            } finally {
                audioJob?.cancel()
                // Unblock a write() stuck on a full buffer — it doesn't
                // observe cancellation, so it would delay the release.
                audioTrack?.let { runCatching { it.stop() } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Immersive while the player is up — a swipe still reveals the bars.
        val view = LocalView.current
        DisposableEffect(Unit) {
            val ctl = (context as? android.app.Activity)?.window
                ?.let { WindowCompat.getInsetsController(it, view) }
            ctl?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                error != null -> Text(
                    stringResource(R.string.cannot_play, error.orEmpty()),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
                frame != null -> Image(
                    bitmap = frame!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.playback),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            if (remote != null && (buffering || streamTotal > 0)) {
                Text(
                    if (buffering && streamTotal > 0)
                        stringResource(R.string.buffering_pct, streamGot * 100f / streamTotal)
                    else if (buffering) stringResource(R.string.buffering)
                    else stringResource(R.string.streaming_pct, streamGot * 100f / streamTotal),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.TopStart)
                        .padding(16.dp)
                        .background(Color(0x66000000))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                // Adopt the streamed bytes into the gallery instead of
                // re-pulling them over the socket. Allowed once frames are
                // indexed — a truncated AVI still plays to its last whole
                // chunk, so the user can keep just the part they watched.
                if (remote != null && error == null && (streamDone || indexed)) {
                    IconButton(onClick = {
                        scope.launch {
                            saved = vm.saveStreamed(remote, tmpFile!!, partial = !streamDone)
                        }
                    }, enabled = !saved) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = stringResource(R.string.save_to_device),
                            tint = if (saved) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }
                }
                if (uri != null) {
                    IconButton(onClick = { shareFile(context, uri, isImage) }) {
                        Icon(Icons.Default.Share,
                            contentDescription = stringResource(R.string.share),
                            tint = Color.White)
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White)
                }
            }
            val pl = player
            if (indexed && pl != null && indexedFrames > 1) {
                val count = indexedFrames
                val skip = (1000L / pl.frameMs.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                    Slider(
                        value = pos.toFloat(),
                        onValueChange = {
                            pos = it.toInt().coerceIn(0, count - 1)
                        },
                        valueRange = 0f..(count - 1).toFloat()
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { pos = (pos - skip).coerceAtLeast(0); paused = false }) {
                            Icon(Icons.Default.Replay10,
                                contentDescription = stringResource(R.string.back_1s),
                                tint = Color.White)
                        }
                        IconButton(onClick = {
                            paused = !paused
                            if (!paused && pos >= count) pos = 0
                        }) {
                            Icon(
                                if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = stringResource(
                                    if (paused) R.string.play else R.string.pause),
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { pos = (pos + skip).coerceAtMost(count - 1); paused = false }) {
                            Icon(Icons.Default.Forward10,
                                contentDescription = stringResource(R.string.forward_1s),
                                tint = Color.White)
                        }
                        Text(
                            stringResource(R.string.player_position,
                                pos * pl.frameMs / 1000.0,
                                pl.probeDurationSec ?: pl.durationSec),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
        }
    }
}
