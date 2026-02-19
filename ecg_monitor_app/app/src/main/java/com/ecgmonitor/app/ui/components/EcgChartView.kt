package com.ecgmonitor.app.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.ecgmonitor.app.data.models.ArrhythmiaEvent
import com.ecgmonitor.app.data.models.ArrhythmiaType
import com.ecgmonitor.app.data.models.EcgReading
import timber.log.Timber

/** Custom [LineChart] for ECG waveform display with arrhythmia event markers. */
class EcgChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LineChart(context, attrs, defStyleAttr) {

    companion object {
        private const val ECG_LINE_COLOR    = 0xFF00FF41.toInt()
        private const val GRID_COLOR        = 0xFF1A2332.toInt()
        private const val BACKGROUND_COLOR  = 0xFF0A0E1A.toInt()
        private const val TEXT_COLOR        = 0xFF8A9BA8.toInt()
        private const val LINE_WIDTH        = 2f
        private const val VISIBLE_WINDOW_SECONDS = 10f

        private const val MARKER_NORMAL_COLOR = 0xFF2ED573.toInt()
        private const val MARKER_PVC_COLOR    = 0xFFFF6B6B.toInt()
        private const val MARKER_AFIB_COLOR   = 0xFFFF4757.toInt()
    }

    private var samplingRate: Int = 250
    private val ecgDataSet: LineDataSet
    private val entries = ArrayList<Entry>()

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
        setWillNotDraw(false)
    }

    private fun setupChart() {
        setBackgroundColor(BACKGROUND_COLOR)
        description.isEnabled = false
        legend.isEnabled = false
        setTouchEnabled(true)
        isDragEnabled = true
        setScaleEnabled(true)
        setPinchZoom(true)
        setDrawGridBackground(true)
        setGridBackgroundColor(BACKGROUND_COLOR)

        xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true)
            gridColor = GRID_COLOR
            gridLineWidth = 0.5f
            textColor = TEXT_COLOR
            textSize = 10f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String =
                    "${(value / samplingRate).format(1)}s"
            }
            setDrawAxisLine(true)
            axisLineColor = GRID_COLOR
        }

        // Y axis shows raw ADC counts (int16 offset from midpoint).
        // isAutoScaleMinMaxEnabled adapts to the actual signal amplitude —
        // no hard-coded millivolt range needed. Pinch-zoom lets users inspect detail.
        axisLeft.apply {
            setDrawGridLines(true)
            gridColor = GRID_COLOR
            gridLineWidth = 0.5f
            textColor = TEXT_COLOR
            textSize = 10f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = value.format(0)
            }
            setDrawAxisLine(true)
            axisLineColor = GRID_COLOR
            isAutoScaleMinMaxEnabled = true
        }

        axisRight.isEnabled = false
        animateX(300)
    }

    private fun createEcgDataSet(): LineDataSet =
        LineDataSet(entries, "ECG").apply {
            color = ECG_LINE_COLOR
            lineWidth = LINE_WIDTH
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
            isHighlightEnabled = false
            setDrawFilled(false)
        }

    /**
     * Replaces or appends ECG readings.
     *
     * X = [EcgReading.sampleIndex] (integer counter starting at 0) rather than an
     * absolute millisecond timestamp. Using the raw timestamp (~1.2 billion) caused
     * consecutive 4 ms-apart entries to collapse to the same pixel due to float precision loss.
     */
    fun updateEcgData(readings: List<EcgReading>, clearPrevious: Boolean = false) {
        if (clearPrevious) entries.clear()

        entries.addAll(readings.map { Entry(it.sampleIndex.toFloat(), it.voltage) })

        val maxEntries = (VISIBLE_WINDOW_SECONDS * samplingRate).toInt()
        if (entries.size > maxEntries) {
            repeat(entries.size - maxEntries) { entries.removeAt(0) }
        }

        ecgDataSet.notifyDataSetChanged()
        data.notifyDataChanged()
        notifyDataSetChanged()
        moveViewToX(data.xMax)

        Timber.d("ECG chart updated: ${entries.size} entries")
    }

    /** Replaces the visible arrhythmia marker set and redraws. */
    fun updateVisibleMarkers(visibleEvents: List<ArrhythmiaEvent>) {
        arrhythmiaMarkers.clear()
        visibleEvents.forEach { event ->
            arrhythmiaMarkers.add(
                ArrhythmiaMarker(
                    // Must use sampleIndex, not timestamp — chart X-axis is in sample units.
                    sampleIndex = event.sampleIndex.toFloat(),
                    type        = event.type,
                    label       = getMarkerLabel(event.type),
                    color       = getMarkerColor(event.type)
                )
            )
        }
        invalidate()
    }

    private fun getMarkerLabel(type: ArrhythmiaType): String = when (type) {
        ArrhythmiaType.NORMAL           -> "N"
        ArrhythmiaType.SUPRAVENTRICULAR -> "SVE"
        ArrhythmiaType.VENTRICULAR      -> "PVC"
    }

    private fun getMarkerColor(type: ArrhythmiaType): Int = when (type) {
        ArrhythmiaType.NORMAL           -> MARKER_NORMAL_COLOR
        ArrhythmiaType.SUPRAVENTRICULAR -> MARKER_AFIB_COLOR
        ArrhythmiaType.VENTRICULAR      -> MARKER_PVC_COLOR
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (arrhythmiaMarkers.isEmpty()) return

        val visibleXMin = lowestVisibleX
        val visibleXMax = highestVisibleX

        arrhythmiaMarkers.forEach { marker ->
            if (marker.sampleIndex in visibleXMin..visibleXMax) drawMarker(canvas, marker)
        }
    }

    private fun drawMarker(canvas: Canvas, marker: ArrhythmiaMarker) {
        val transformer = getTransformer(axisLeft.axisDependency)
        val pts = floatArrayOf(marker.sampleIndex, 0f)
        transformer.pointValuesToPixel(pts)
        val xPos = pts[0]

        markerPaint.color = marker.color
        canvas.drawLine(xPos, viewPortHandler.contentTop(), xPos, viewPortHandler.contentBottom(), markerPaint)

        markerTextPaint.color = marker.color
        canvas.drawText(marker.label, xPos, viewPortHandler.contentTop() + 30f, markerTextPaint)
    }

    fun setSamplingRate(rate: Int) {
        samplingRate = rate
    }

    private data class ArrhythmiaMarker(
        val sampleIndex: Float,
        val type: ArrhythmiaType,
        val label: String,
        val color: Int
    )
}

private fun Float.format(decimals: Int): String = "%.${decimals}f".format(this)