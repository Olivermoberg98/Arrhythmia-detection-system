package com.ecgmonitor.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ecgmonitor.app.data.models.ArrhythmiaEvent
import com.ecgmonitor.app.data.models.EcgReading

/**
 * Composable wrapper for EcgChartView
 * This bridges the traditional Android View (MPAndroidChart) with Jetpack Compose
 * Now with arrhythmia marker support!
 */
@Composable
fun EcgChart(
    ecgReadings: List<EcgReading>,
    arrhythmiaEvents: List<ArrhythmiaEvent> = emptyList(),
    samplingRate: Int = 360,
    modifier: Modifier = Modifier
) {
    // Create and remember the chart view
    AndroidView(
        modifier = modifier,
        factory = { context ->
            EcgChartView(context).apply {
                setSamplingRate(samplingRate)
            }
        },
        update = { chartView ->
            // Update chart when data changes
            chartView.updateEcgData(ecgReadings, clearPrevious = true)

            // Update arrhythmia markers
            if (arrhythmiaEvents.isNotEmpty()) {
                chartView.updateVisibleMarkers(arrhythmiaEvents)
            }
        }
    )
}

/**
 * ECG Chart for real-time streaming data
 * Optimized for continuous updates without clearing previous data
 */
@Composable
fun StreamingEcgChart(
    ecgReadings: List<EcgReading>,
    arrhythmiaEvents: List<ArrhythmiaEvent> = emptyList(),
    samplingRate: Int = 360,
    modifier: Modifier = Modifier
) {
    val chartView = remember { mutableListOf<EcgChartView>() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            EcgChartView(context).apply {
                setSamplingRate(samplingRate)
                chartView.add(this)
            }
        }
    )

    // Update chart when new data arrives
    LaunchedEffect(ecgReadings.size) {
        chartView.firstOrNull()?.let { chart ->
            if (ecgReadings.isNotEmpty()) {
                // Only add new readings, don't clear
                chart.updateEcgData(ecgReadings, clearPrevious = false)
            }
        }
    }

    // Update arrhythmia markers separately
    LaunchedEffect(arrhythmiaEvents.size) {
        chartView.firstOrNull()?.let { chart ->
            if (arrhythmiaEvents.isNotEmpty()) {
                chart.updateVisibleMarkers(arrhythmiaEvents)
            }
        }
    }
}