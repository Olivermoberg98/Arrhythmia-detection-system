package com.ecgmonitor.app.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.ecgmonitor.app.data.models.ArrhythmiaEvent
import com.ecgmonitor.app.data.models.ArrhythmiaType
import com.ecgmonitor.app.data.models.EcgReading
import timber.log.Timber

/**
 * Custom LineChart view for ECG waveform display
 * Medical-grade aesthetics with real-time update capabilities and arrhythmia markers
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

        // Arrhythmia marker colors
        private const val MARKER_NORMAL_COLOR = 0xFF2ED573.toInt() // Green
        private const val MARKER_PVC_COLOR = 0xFFFF6B6B.toInt() // Red
        private const val MARKER_AFIB_COLOR = 0xFFFF4757.toInt() // Dark red
        private const val MARKER_WARNING_COLOR = 0xFFFFA502.toInt() // Orange
    }

    private var samplingRate: Int = 360 // Default MIT-BIH rate
    private val ecgDataSet: LineDataSet
    private val entries = ArrayList<Entry>()

    // Arrhythmia markers
    private val arrhythmiaMarkers = mutableListOf<ArrhythmiaMarker>()
    private val markerPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }
    private val markerTextPaint = Paint().apply {
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    init {
        setupChart()
        ecgDataSet = createEcgDataSet()
        data = LineData(ecgDataSet)
        setWillNotDraw(false) // Enable onDraw for custom markers
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
     * Shows vertical lines at arrhythmia detection points
     */
    fun addArrhythmiaMarkers(events: List<ArrhythmiaEvent>) {
        // Clear existing markers
        arrhythmiaMarkers.clear()

        events.forEach { event ->
            val marker = ArrhythmiaMarker(
                timestamp = event.timestamp.toFloat(),
                type = event.type,
                label = getMarkerLabel(event.type),
                color = getMarkerColor(event.type)
            )
            arrhythmiaMarkers.add(marker)

            Timber.d("Added arrhythmia marker at ${event.timestamp}ms: ${event.type.displayName}")
        }

        // Trigger redraw to show markers
        invalidate()
    }

    /**
     * Update markers for visible window (performance optimization)
     */
    fun updateVisibleMarkers(visibleEvents: List<ArrhythmiaEvent>) {
        arrhythmiaMarkers.clear()

        visibleEvents.forEach { event ->
            val marker = ArrhythmiaMarker(
                timestamp = event.timestamp.toFloat(),
                type = event.type,
                label = getMarkerLabel(event.type),
                color = getMarkerColor(event.type)
            )
            arrhythmiaMarkers.add(marker)
        }

        invalidate()
    }

    private fun getMarkerLabel(type: ArrhythmiaType): String {
        return when (type) {
            ArrhythmiaType.NORMAL -> "N"
            ArrhythmiaType.PREMATURE_VENTRICULAR -> "PVC"
            ArrhythmiaType.ATRIAL_FIBRILLATION -> "AFib"
            ArrhythmiaType.VENTRICULAR_TACHYCARDIA -> "V-Tach"
            ArrhythmiaType.BRADYCARDIA -> "Brady"
            ArrhythmiaType.TACHYCARDIA -> "Tachy"
            ArrhythmiaType.UNKNOWN -> "?"
        }
    }

    private fun getMarkerColor(type: ArrhythmiaType): Int {
        return when (type) {
            ArrhythmiaType.NORMAL -> MARKER_NORMAL_COLOR
            ArrhythmiaType.PREMATURE_VENTRICULAR -> MARKER_PVC_COLOR
            ArrhythmiaType.ATRIAL_FIBRILLATION -> MARKER_AFIB_COLOR
            ArrhythmiaType.VENTRICULAR_TACHYCARDIA -> MARKER_AFIB_COLOR
            ArrhythmiaType.BRADYCARDIA -> MARKER_WARNING_COLOR
            ArrhythmiaType.TACHYCARDIA -> MARKER_WARNING_COLOR
            ArrhythmiaType.UNKNOWN -> TEXT_COLOR
        }
    }

    /**
     * Custom drawing to add vertical lines for arrhythmia markers
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (arrhythmiaMarkers.isEmpty()) return

        // Get visible range
        val visibleXMin = lowestVisibleX
        val visibleXMax = highestVisibleX

        // Only draw markers in visible range
        arrhythmiaMarkers.forEach { marker ->
            if (marker.timestamp in visibleXMin..visibleXMax) {
                drawMarker(canvas, marker)
            }
        }
    }

    private fun drawMarker(canvas: Canvas, marker: ArrhythmiaMarker) {
        // Get pixel position for timestamp
        val transformer = getTransformer(axisLeft.axisDependency)
        val pts = floatArrayOf(marker.timestamp, 0f)
        transformer.pointValuesToPixel(pts)

        val xPos = pts[0]

        // Draw vertical line
        markerPaint.color = marker.color
        canvas.drawLine(
            xPos,
            viewPortHandler.contentTop(),
            xPos,
            viewPortHandler.contentBottom(),
            markerPaint
        )

        // Draw label at top
        markerTextPaint.color = marker.color
        canvas.drawText(
            marker.label,
            xPos,
            viewPortHandler.contentTop() + 30f,
            markerTextPaint
        )
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
        arrhythmiaMarkers.clear()
        ecgDataSet.notifyDataSetChanged()
        data.notifyDataChanged()
        notifyDataSetChanged()
        invalidate()
    }

    /**
     * Data class for arrhythmia markers
     */
    private data class ArrhythmiaMarker(
        val timestamp: Float,
        val type: ArrhythmiaType,
        val label: String,
        val color: Int
    )
}

// Extension function for number formatting
private fun Float.format(decimals: Int): String {
    return "%.${decimals}f".format(this)
}