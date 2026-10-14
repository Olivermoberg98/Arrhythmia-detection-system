package com.ecgmonitor.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ecgmonitor.app.data.database.entities.EcgSessionEntity
import com.ecgmonitor.app.data.database.entities.HealthStatus
import com.ecgmonitor.app.data.models.ArrhythmiaEvent
import com.ecgmonitor.app.ui.components.EcgChart
import com.ecgmonitor.app.ui.theme.MedicalCardBackground
import com.ecgmonitor.app.ui.theme.MedicalCyan
import com.ecgmonitor.app.ui.theme.MedicalDarkBackground
import com.ecgmonitor.app.ui.theme.MedicalDarkBlue
import com.ecgmonitor.app.ui.theme.MedicalGreen
import com.ecgmonitor.app.ui.theme.MedicalOrange
import com.ecgmonitor.app.ui.theme.MedicalRed
import com.ecgmonitor.app.ui.theme.MedicalTextSecondary
import com.ecgmonitor.app.ui.viewmodels.EventFilter
import com.ecgmonitor.app.ui.viewmodels.PlaybackState
import com.ecgmonitor.app.ui.viewmodels.SessionDetailViewModel
import com.ecgmonitor.app.utils.ExportManager
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: String,
    viewModel: SessionDetailViewModel,
    onBack: () -> Unit,
    onDelete: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val exportManager  = remember { ExportManager(context) }

    val sessionData      by viewModel.sessionData.collectAsStateWithLifecycle()
    val currentReadings  by viewModel.currentReadings.collectAsStateWithLifecycle()
    val visibleEvents    by viewModel.visibleEvents.collectAsStateWithLifecycle()
    val playbackState    by viewModel.playbackState.collectAsStateWithLifecycle()
    val playbackProgress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val playbackSpeed    by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val eventFilter      by viewModel.eventFilter.collectAsStateWithLifecycle()

    var showSpeedMenu    by remember { mutableStateOf(false) }
    var showFilterMenu   by remember { mutableStateOf(false) }
    var showExportMenu   by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var exportStatus     by remember { mutableStateOf<Pair<Boolean, String>?>(null) } // isSuccess to message

    LaunchedEffect(sessionId) { viewModel.loadSession(sessionId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        sessionData?.session?.name ?: "Session Details",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MedicalDarkBlue),
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete recording", tint = MedicalRed)
                    }
                    IconButton(onClick = { showExportMenu = true }) {
                        Icon(Icons.Default.Share, contentDescription = "Export", tint = Color.White)
                    }
                }
            )
        },
        containerColor = MedicalDarkBackground
    ) { paddingValues ->

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                containerColor = MedicalCardBackground,
                icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MedicalRed) },
                title = { Text("Delete recording?", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "This will permanently delete the recording and all associated events. This cannot be undone.",
                        color = MedicalTextSecondary,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { showDeleteDialog = false; onDelete(sessionId) },
                        colors = ButtonDefaults.buttonColors(containerColor = MedicalRed)
                    ) { Text("Delete") }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { showDeleteDialog = false },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MedicalTextSecondary)
                    ) { Text("Cancel") }
                }
            )
        }

        sessionData?.let { data ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                StatisticsSection(session = data.session, modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                    colors = CardDefaults.cardColors(containerColor = MedicalCardBackground),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "ECG Waveform",
                                color = MedicalTextSecondary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )

                            Row {
                                TextButton(onClick = { showSpeedMenu = true }) {
                                    Text("${playbackSpeed}x", color = MedicalCyan, fontSize = 12.sp)
                                }
                                DropdownMenu(
                                    expanded = showSpeedMenu,
                                    onDismissRequest = { showSpeedMenu = false }
                                ) {
                                    listOf(0.5f, 1f, 2f, 4f).forEach { speed ->
                                        DropdownMenuItem(
                                            text = { Text("${speed}x") },
                                            onClick = {
                                                viewModel.setPlaybackSpeed(speed)
                                                showSpeedMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (currentReadings.isNotEmpty()) {
                            EcgChart(
                                ecgReadings = currentReadings,
                                arrhythmiaEvents = visibleEvents,
                                samplingRate = data.session.samplingRate,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        PlaybackControls(
                            playbackState = playbackState,
                            playbackProgress = playbackProgress,
                            onPlayPause = {
                                when (playbackState) {
                                    is PlaybackState.Playing -> viewModel.pausePlayback()
                                    else -> viewModel.startPlayback()
                                }
                            },
                            onStop = { viewModel.stopPlayback() },
                            onSeek = { viewModel.seekToProgress(it) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                EventTimelineSection(
                    events = viewModel.getFilteredEvents(),
                    currentFilter = eventFilter,
                    onFilterClick = { showFilterMenu = true },
                    onEventClick = { viewModel.jumpToEvent(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    showFilterMenu = showFilterMenu,
                    onFilterMenuDismiss = { showFilterMenu = false },
                    onFilterSelect = { filter ->
                        viewModel.setEventFilter(filter)
                        showFilterMenu = false
                    }
                )

                exportStatus?.let { (isSuccess, message) ->
                    Spacer(modifier = Modifier.height(12.dp))
                    ExportStatusCard(
                        message = message,
                        isSuccess = isSuccess,
                        onDismiss = { exportStatus = null }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            DropdownMenu(
                expanded = showExportMenu,
                onDismissRequest = { showExportMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Export as PDF") },
                    leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null) },
                    onClick = {
                        showExportMenu = false
                        coroutineScope.launch {
                            exportManager.exportToPdf(data).fold(
                                onSuccess = { file ->
                                    exportStatus = true to "PDF saved to ${file.name}"
                                    shareFile(context, file, "application/pdf")
                                },
                                onFailure = { exportStatus = false to "PDF export failed: ${it.message}" }
                            )
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Export as CSV") },
                    leadingIcon = { Icon(Icons.Default.TableChart, contentDescription = null) },
                    onClick = {
                        showExportMenu = false
                        coroutineScope.launch {
                            exportManager.exportToCsv(data).fold(
                                onSuccess = { file ->
                                    exportStatus = true to "CSV saved to ${file.name}"
                                    shareFile(context, file, "text/csv")
                                },
                                onFailure = { exportStatus = false to "CSV export failed: ${it.message}" }
                            )
                        }
                    }
                )
            }
        } ?: Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MedicalGreen)
        }
    }
}

// region — Private composable

@Composable
private fun StatisticsSection(session: EcgSessionEntity, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MedicalCardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatDateTime(session.startTime), fontSize = 14.sp, color = MedicalTextSecondary)
                HealthStatusBadge(session.healthStatus)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                StatColumn("Avg HR", "${session.averageHeartRate}", "BPM", MedicalGreen)
                StatColumn("Min HR", "${session.minHeartRate}",     "BPM", MedicalCyan)
                StatColumn("Max HR", "${session.maxHeartRate}",     "BPM", MedicalOrange)
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MedicalTextSecondary.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                BeatStat("Normal", session.normalBeats, MedicalGreen)
                BeatStat("PVC",    session.pvcCount,    MedicalRed)
                if (session.supraventricularCount > 0) {
                    BeatStat("SVEB", session.supraventricularCount, MedicalOrange)
                }
            }

            if (session.healthStatus != HealthStatus.NORMAL) {
                Spacer(modifier = Modifier.height(12.dp))
                HealthAssessment(session)
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, unit: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = MedicalTextSecondary)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = color)
            Spacer(modifier = Modifier.width(4.dp))
            Text(unit, fontSize = 12.sp, color = MedicalTextSecondary, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

@Composable
private fun BeatStat(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$count", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 12.sp, color = MedicalTextSecondary)
    }
}

@Composable
private fun HealthAssessment(session: EcgSessionEntity) {
    val (color, message) = when (session.healthStatus) {
        HealthStatus.ATTENTION  -> MedicalOrange to "Elevated PVC count detected. Monitor symptoms."
        HealthStatus.CONCERNING -> MedicalRed    to "Significant arrhythmias detected. Consider medical consultation."
        else -> return
    }
    Surface(color = color.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(message, fontSize = 13.sp, color = color)
        }
    }
}

@Composable
private fun HealthStatusBadge(status: HealthStatus) {
    val (color, text) = when (status) {
        HealthStatus.NORMAL     -> MedicalGreen  to "Normal"
        HealthStatus.ATTENTION  -> MedicalOrange to "Attention"
        HealthStatus.CONCERNING -> MedicalRed    to "Review"
    }
    Surface(color = color.copy(alpha = 0.2f), shape = MaterialTheme.shapes.small) {
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PlaybackControls(
    playbackState: PlaybackState,
    playbackProgress: Float,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Float) -> Unit
) {
    Column {
        Slider(
            value = playbackProgress,
            onValueChange = onSeek,
            colors = SliderDefaults.colors(
                thumbColor = MedicalGreen,
                activeTrackColor = MedicalGreen,
                inactiveTrackColor = MedicalTextSecondary.copy(alpha = 0.3f)
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onStop, enabled = playbackState !is PlaybackState.Stopped) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "Stop",
                    tint = if (playbackState !is PlaybackState.Stopped) MedicalRed else MedicalTextSecondary
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (playbackState is PlaybackState.Playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playbackState is PlaybackState.Playing) "Pause" else "Play",
                    tint = MedicalGreen,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun EventTimelineSection(
    events: List<ArrhythmiaEvent>,
    currentFilter: EventFilter,
    onFilterClick: () -> Unit,
    onEventClick: (ArrhythmiaEvent) -> Unit,
    modifier: Modifier = Modifier,
    showFilterMenu: Boolean,
    onFilterMenuDismiss: () -> Unit,
    onFilterSelect: (EventFilter) -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MedicalCardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Event Timeline (${events.size})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MedicalTextSecondary
                )

                Box {
                    TextButton(onClick = onFilterClick) {
                        Text(getFilterName(currentFilter), fontSize = 12.sp, color = MedicalCyan)
                        Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(16.dp), tint = MedicalCyan)
                    }

                    DropdownMenu(expanded = showFilterMenu, onDismissRequest = onFilterMenuDismiss) {
                        EventFilter.entries.forEach { filter ->
                            DropdownMenuItem(
                                text = { Text(getFilterName(filter)) },
                                onClick = { onFilterSelect(filter) },
                                leadingIcon = {
                                    if (currentFilter == filter) Icon(Icons.Default.Check, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(events) { event ->
                    EventTimelineItem(event = event, onClick = { onEventClick(event) })
                }
            }
        }
    }
}

@Composable
private fun EventTimelineItem(event: ArrhythmiaEvent, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color(event.type.color).copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(event.type.displayName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(event.type.color))
                Text(formatTimestamp(event.timestamp), fontSize = 12.sp, color = MedicalTextSecondary)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MedicalTextSecondary)
        }
    }
}

@Composable
private fun ExportStatusCard(message: String, isSuccess: Boolean, onDismiss: () -> Unit) {
    val color = if (isSuccess) MedicalGreen else MedicalRed
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(message, color = color, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text("OK", color = color, fontSize = 12.sp)
            }
        }
    }
}

// endregion

// region — Helpers

private fun formatDateTime(timestamp: Long): String =
    SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun formatTimestamp(timestamp: Long): String {
    val mins = (timestamp / 1000) / 60
    val secs = (timestamp / 1000) % 60
    return String.format(Locale.US, "%d:%02d", mins, secs)
}

private fun getFilterName(filter: EventFilter): String = when (filter) {
    EventFilter.ALL           -> "All Events"
    EventFilter.PVC_ONLY      -> "PVC Only"
    EventFilter.AFIB_ONLY     -> "AFib Only"
    EventFilter.ABNORMAL_ONLY -> "Abnormal Only"
}

/**
 * Shares [file] via the system share sheet using a FileProvider URI.
 * Requires `${applicationId}.provider` declared in AndroidManifest.xml.
 */
private fun shareFile(context: Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share ECG Export"))
}

// endregion