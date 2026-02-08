package com.ecgmonitor.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ecgmonitor.app.data.database.entities.EcgSessionEntity
import com.ecgmonitor.app.data.database.entities.HealthStatus
import com.ecgmonitor.app.ui.theme.*
import com.ecgmonitor.app.ui.viewmodels.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onSessionClick: (String) -> Unit
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()

    var showSortMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Recording History",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MedicalDarkBlue
                ),
                actions = {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(
                            Icons.Default.Sort,
                            contentDescription = "Sort",
                            tint = Color.White
                        )
                    }
                }
            )
        },
        containerColor = MedicalDarkBackground
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (sessions.isEmpty()) {
                EmptyHistoryState(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            onClick = { onSessionClick(session.id) }
                        )
                    }
                }
            }

            // Sort menu
            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Most Recent") },
                    onClick = {
                        viewModel.setSortOrder(SortOrder.MOST_RECENT)
                        showSortMenu = false
                    },
                    leadingIcon = {
                        if (sortOrder == SortOrder.MOST_RECENT) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Oldest First") },
                    onClick = {
                        viewModel.setSortOrder(SortOrder.OLDEST)
                        showSortMenu = false
                    },
                    leadingIcon = {
                        if (sortOrder == SortOrder.OLDEST) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Most Arrhythmias") },
                    onClick = {
                        viewModel.setSortOrder(SortOrder.MOST_ARRHYTHMIAS)
                        showSortMenu = false
                    },
                    leadingIcon = {
                        if (sortOrder == SortOrder.MOST_ARRHYTHMIAS) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Longest Duration") },
                    onClick = {
                        viewModel.setSortOrder(SortOrder.LONGEST)
                        showSortMenu = false
                    },
                    leadingIcon = {
                        if (sortOrder == SortOrder.LONGEST) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: EcgSessionEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MedicalCardBackground
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Name and health status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = session.name ?: "Unnamed Session",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )

                HealthStatusBadge(status = session.healthStatus)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Date and duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = MedicalTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatDateTime(session.startTime),
                        fontSize = 13.sp,
                        color = MedicalTextSecondary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Timer,
                        contentDescription = null,
                        tint = MedicalTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatDuration(session.durationSeconds),
                        fontSize = 13.sp,
                        color = MedicalTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Statistics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                StatItem(
                    label = "HR",
                    value = "${session.averageHeartRate}",
                    unit = "BPM",
                    color = MedicalGreen
                )

                StatItem(
                    label = "Beats",
                    value = "${session.totalBeats}",
                    unit = "",
                    color = MedicalCyan
                )

                val arrhythmiaCount = session.pvcCount + session.afibCount + session.otherArrhythmiaCount
                StatItem(
                    label = "Events",
                    value = "$arrhythmiaCount",
                    unit = "",
                    color = if (arrhythmiaCount > 0) MedicalRed else MedicalGreen
                )
            }

            // Arrhythmia breakdown if present
            if (session.pvcCount > 0 || session.afibCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = MedicalTextSecondary.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (session.pvcCount > 0) {
                        ArrhythmiaChip(
                            label = "PVC",
                            count = session.pvcCount,
                            color = MedicalRed
                        )
                    }
                    if (session.afibCount > 0) {
                        ArrhythmiaChip(
                            label = "AFib",
                            count = session.afibCount,
                            color = MedicalRed
                        )
                    }
                }
            }
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

@Composable
private fun StatItem(
    label: String,
    value: String,
    unit: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MedicalTextSecondary
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = color
            )
            if (unit.isNotEmpty()) {
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = unit,
                    fontSize = 11.sp,
                    color = MedicalTextSecondary,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ArrhythmiaChip(
    label: String,
    count: Int,
    color: Color
) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "$count",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                fontSize = 12.sp,
                color = color
            )
        }
    }
}

@Composable
private fun EmptyHistoryState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            tint = MedicalTextSecondary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "No Recordings Yet",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MedicalTextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Start a recording on the Live tab\nto see it appear here",
            fontSize = 14.sp,
            color = MedicalTextSecondary.copy(alpha = 0.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

// Helper functions
private fun formatDateTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatDuration(seconds: Float): String {
    val totalSeconds = seconds.toInt()
    val minutes = totalSeconds / 60
    val secs = totalSeconds % 60
    return if (minutes > 0) {
        "${minutes}m ${secs}s"
    } else {
        "${secs}s"
    }
}

// Sort order enum
enum class SortOrder {
    MOST_RECENT,
    OLDEST,
    MOST_ARRHYTHMIAS,
    LONGEST
}