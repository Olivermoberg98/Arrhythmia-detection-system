package com.ecgmonitor.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ecgmonitor.app.data.models.ArrhythmiaEvent
import com.ecgmonitor.app.data.models.EcgReading

/**
 * Composable wrapper around [EcgChartView] (MPAndroidChart).
 *
 * The ViewModel already maintains a rolling window of [ecgReadings], so this composable
 * simply replaces the chart data on every update. For live streaming the ViewModel
 * trims the buffer to the visible window before emitting, so clearing on each update
 * is both correct and efficient.
 */
@Composable
fun EcgChart(
    ecgReadings: List<EcgReading>,
    arrhythmiaEvents: List<ArrhythmiaEvent> = emptyList(),
    samplingRate: Int = 250,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            EcgChartView(context).apply { setSamplingRate(samplingRate) }
        },
        update = { chartView ->
            chartView.updateEcgData(ecgReadings, clearPrevious = true)
            if (arrhythmiaEvents.isNotEmpty()) {
                chartView.updateVisibleMarkers(arrhythmiaEvents)
            }
        }
    )
}