package com.ecgmonitor.app.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ecgmonitor.app.data.database.entities.HealthStatus
import com.ecgmonitor.app.data.models.ArrhythmiaEvent
import com.ecgmonitor.app.ui.components.EcgChart
import com.ecgmonitor.app.ui.theme.*
import com.ecgmonitor.app.ui.viewmodels.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: String,
    viewModel: SessionDetailViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val sessionData by viewModel.sessionData.collectAsStateWithLifecycle()
    val currentReadings by viewModel.currentReadings.collectAsStateWithLifecycle()
    val visibleEvents by viewModel.visibleEvents.collectAsStateWithLifecycle()
    val allEvents by viewModel.allEvents.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val playbackProgress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val eventFilter by viewModel.eventFilter.collectAsStateWithLifecycle()

    var showSpeedMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        viewModel.loadSession(sessionId)
    }

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
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MedicalDarkBlue
                ),
                actions = {
                    IconButton(onClick = { showExportMenu = true }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Export",
                            tint = Color.White
                        )
                    }
                }
            )
        },
        containerColor = MedicalDarkBackground
    ) { paddingValues ->

        sessionData?.let { data ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Top Section: Statistics
                StatisticsSection(
                    session = data.session,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Middle Section: ECG Chart
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp), // Fixed height
                    colors = CardDefaults.cardColors(
                        containerColor = MedicalCardBackground
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        // Chart header with controls
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
                                // Speed control
                                TextButton(onClick = { showSpeedMenu = true }) {
                                    Text(
                                        "${playbackSpeed}x",
                                        color = MedicalCyan,
                                        fontSize = 12.sp
                                    )
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

                        // ECG Chart
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

                        // Playback controls
                        PlaybackControls(
                            playbackState = playbackState,
                            playbackProgress = playbackProgress,
                            onPlayPause = {
                                when (playbackState) {
                                    is PlaybackState.Playing -> viewModel.pausePlayback()
                                    is PlaybackState.Paused,
                                    is PlaybackState.Stopped,
                                    is PlaybackState.Completed -> viewModel.startPlayback()
                                }
                            },
                            onStop = { viewModel.stopPlayback() },
                            onSeek = { viewModel.seekToProgress(it) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom Section: Event Timeline
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

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Export menu
            DropdownMenu(
                expanded = showExportMenu,
                onDismissRequest = { showExportMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Export as PDF") },
                    onClick = {
                        exportAsPdf(context, data, viewModel)
                        showExportMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Export as CSV") },
                    onClick = {
                        exportAsCsv(context, data, viewModel)
                        showExportMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Default.TableChart, contentDescription = null)
                    }
                )
            }
        } ?: run {
            // Loading state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MedicalGreen)
            }
        }
    }
}

@Composable
private fun StatisticsSection(
    session: com.ecgmonitor.app.data.database.entities.EcgSessionEntity,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MedicalCardBackground
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Session info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatDateTime(session.startTime),
                    fontSize = 14.sp,
                    color = MedicalTextSecondary
                )
                HealthStatusBadge(session.healthStatus)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Heart rate stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                StatColumn("Avg HR", "${session.averageHeartRate}", "BPM", MedicalGreen)
                StatColumn("Min HR", "${session.minHeartRate}", "BPM", MedicalCyan)
                StatColumn("Max HR", "${session.maxHeartRate}", "BPM", MedicalOrange)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider(color = MedicalTextSecondary.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(16.dp))

            // Beat breakdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                BeatStat("Normal", session.normalBeats, MedicalGreen)
                BeatStat("PVC", session.pvcCount, MedicalRed)
                if (session.afibCount > 0) {
                    BeatStat("AFib", session.afibCount, MedicalRed)
                }
            }

            // Health assessment
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
            Text(
                value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = color
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                unit,
                fontSize = 12.sp,
                color = MedicalTextSecondary,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

@Composable
private fun BeatStat(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$count",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            label,
            fontSize = 12.sp,
            color = MedicalTextSecondary
        )
    }
}

@Composable
private fun HealthAssessment(session: com.ecgmonitor.app.data.database.entities.EcgSessionEntity) {
    val (color, message) = when (session.healthStatus) {
        HealthStatus.ATTENTION -> MedicalOrange to "Elevated PVC count detected. Monitor symptoms."
        HealthStatus.CONCERNING -> MedicalRed to "Significant arrhythmias detected. Consider medical consultation."
        else -> return
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                message,
                fontSize = 13.sp,
                color = color
            )
        }
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
        // Progress slider
        Slider(
            value = playbackProgress,
            onValueChange = onSeek,
            colors = SliderDefaults.colors(
                thumbColor = MedicalGreen,
                activeTrackColor = MedicalGreen,
                inactiveTrackColor = MedicalTextSecondary.copy(alpha = 0.3f)
            )
        )

        // Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onStop,
                enabled = playbackState !is PlaybackState.Stopped
            ) {
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
        colors = CardDefaults.cardColors(
            containerColor = MedicalCardBackground
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
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
                        Text(
                            getFilterName(currentFilter),
                            fontSize = 12.sp,
                            color = MedicalCyan
                        )
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MedicalCyan
                        )
                    }

                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = onFilterMenuDismiss
                    ) {
                        EventFilter.values().forEach { filter ->
                            DropdownMenuItem(
                                text = { Text(getFilterName(filter)) },
                                onClick = { onFilterSelect(filter) },
                                leadingIcon = {
                                    if (currentFilter == filter) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Event list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(events) { event ->
                    EventTimelineItem(
                        event = event,
                        onClick = { onEventClick(event) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EventTimelineItem(
    event: ArrhythmiaEvent,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = Color(event.type.color).copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    event.type.displayName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(event.type.color)
                )
                Text(
                    formatTimestamp(event.timestamp),
                    fontSize = 12.sp,
                    color = MedicalTextSecondary
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MedicalTextSecondary
            )
        }
    }
}

@Composable
private fun HealthStatusBadge(status: HealthStatus) {
    val (color, text) = when (status) {
        HealthStatus.NORMAL -> MedicalGreen to "Normal"
        HealthStatus.ATTENTION -> MedicalOrange to "Attention"
        HealthStatus.CONCERNING -> MedicalRed to "Review"
    }

    Surface(
        color = color.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

// Helper functions
private fun formatDateTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatTimestamp(timestamp: Long): String {
    val seconds = timestamp / 1000
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%d:%02d", minutes, secs)
}

private fun getFilterName(filter: EventFilter): String {
    return when (filter) {
        EventFilter.ALL -> "All Events"
        EventFilter.PVC_ONLY -> "PVC Only"
        EventFilter.AFIB_ONLY -> "AFib Only"
        EventFilter.ABNORMAL_ONLY -> "Abnormal Only"
    }
}

private fun exportAsPdf(
    context: Context,
    data: com.ecgmonitor.app.data.database.entities.SessionWithData,
    viewModel: SessionDetailViewModel
) {
    // PDF export will be implemented in ExportManager
    // For now, show a toast
    android.widget.Toast.makeText(context, "PDF export coming soon", android.widget.Toast.LENGTH_SHORT).show()
}

private fun exportAsCsv(
    context: Context,
    data: com.ecgmonitor.app.data.database.entities.SessionWithData,
    viewModel: SessionDetailViewModel
) {
    // CSV export will be implemented in ExportManager
    android.widget.Toast.makeText(context, "CSV export coming soon", android.widget.Toast.LENGTH_SHORT).show()
}