package com.ecgmonitor.app.ui.components

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.ecgmonitor.app.data.models.ArrhythmiaEvent
import com.ecgmonitor.app.data.models.EcgReading
import timber.log.Timber

/**
 * Custom LineChart view for ECG waveform display
 * Medical-grade aesthetics with real-time update capabilities
 */
class EcgChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LineChart(context, attrs, defStyleAttr) {

    companion object {
        private const val ECG_LINE_COLOR = 0xFF00FF41.toInt() // Medical monitor green
        private const val GRID_COLOR = 0xFF1A2332.toInt()
        private const val BACKGROUND_COLOR = 0xFF0A0E1A.toInt()
        private const val TEXT_COLOR = 0xFF8A9BA8.toInt()
        private const val LINE_WIDTH = 2f
        private const val VISIBLE_WINDOW_SECONDS = 10f // Show 10 seconds of data
    }

    private var samplingRate: Int = 360 // Default MIT-BIH rate
    private val ecgDataSet: LineDataSet
    private val entries = ArrayList<Entry>()

    init {
        setupChart()
        ecgDataSet = createEcgDataSet()
        data = LineData(ecgDataSet)
    }

    private fun setupChart() {
        // Chart appearance
        setBackgroundColor(BACKGROUND_COLOR)
        description.isEnabled = false
        legend.isEnabled = false
        setTouchEnabled(true)
        isDragEnabled = true
        setScaleEnabled(true)
        setPinchZoom(true)
        setDrawGridBackground(true)
        setGridBackgroundColor(BACKGROUND_COLOR)

        // X Axis (Time)
        xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true)
            gridColor = GRID_COLOR
            gridLineWidth = 0.5f
            textColor = TEXT_COLOR
            textSize = 10f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return "${(value / 1000f).format(1)}s" // Convert ms to seconds
                }
            }
            setDrawAxisLine(true)
            axisLineColor = GRID_COLOR
        }

        // Y Axis (Voltage in mV)
        axisLeft.apply {
            setDrawGridLines(true)
            gridColor = GRID_COLOR
            gridLineWidth = 0.5f
            textColor = TEXT_COLOR
            textSize = 10f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return "${value.format(2)} mV"
                }
            }
            setDrawAxisLine(true)
            axisLineColor = GRID_COLOR
            // Set range for ECG (typically -2 to +2 mV)
            axisMinimum = -1.5f
            axisMaximum = 2.5f
        }

        // Disable right axis
        axisRight.isEnabled = false

        // Animation
        animateX(300)
    }

    private fun createEcgDataSet(): LineDataSet {
        return LineDataSet(entries, "ECG").apply {
            color = ECG_LINE_COLOR
            lineWidth = LINE_WIDTH
            setDrawCircles(false) // Don't draw points, just lines
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
            isHighlightEnabled = false
            setDrawFilled(false)
        }
    }

    /**
     * Update chart with new ECG readings
     * @param readings List of ECG data points
     * @param clearPrevious Whether to clear existing data
     */
    fun updateEcgData(readings: List<EcgReading>, clearPrevious: Boolean = false) {
        if (clearPrevious) {
            entries.clear()
        }

        // Convert readings to chart entries
        val newEntries = readings.map { reading ->
            Entry(reading.timestamp.toFloat(), reading.voltage)
        }

        entries.addAll(newEntries)

        // Limit visible window for performance
        val maxEntries = (VISIBLE_WINDOW_SECONDS * samplingRate).toInt()
        if (entries.size > maxEntries) {
            // Remove oldest entries
            val toRemove = entries.size - maxEntries
            repeat(toRemove) { entries.removeAt(0) }
        }

        ecgDataSet.notifyDataSetChanged()
        data.notifyDataChanged()
        notifyDataSetChanged()

        // Auto-scroll to latest data
        moveViewToX(data.xMax)

        Timber.d("Updated ECG chart: ${entries.size} entries")
    }

    /**
     * Add arrhythmia event markers to the chart
     */
    fun addArrhythmiaMarkers(events: List<ArrhythmiaEvent>) {
        // TODO: Phase 3 - Add vertical line markers and labels for arrhythmia events
        // For now, just log them
        events.forEach { event ->
            Timber.d("Arrhythmia marker at ${event.timestamp}ms: ${event.type.displayName}")
        }
    }

    /**
     * Set the sampling rate for proper time axis scaling
     */
    fun setSamplingRate(rate: Int) {
        samplingRate = rate
    }

    /**
     * Clear all data from the chart
     */
    fun clearData() {
        entries.clear()
        ecgDataSet.notifyDataSetChanged()
        data.notifyDataChanged()
        notifyDataSetChanged()
        invalidate()
    }
}

// Extension function for number formatting
private fun Float.format(decimals: Int): String {
    return "%.${decimals}f".format(this)
}
