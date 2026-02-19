package com.ecgmonitor.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.ecgmonitor.app.data.database.entities.SessionWithData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Generates PDF and CSV exports for a recorded ECG session. */
class ExportManager(private val context: Context) {

    /** Renders a one-page A4 PDF report and returns the written [File]. */
    suspend fun exportToPdf(
        sessionData: SessionWithData,
        chartBitmap: Bitmap? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val pdfDocument = PdfDocument()
            val pageInfo    = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
            val page        = pdfDocument.startPage(pageInfo)
            val canvas      = page.canvas
            val paint       = Paint()

            val dateFormat  = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            val session     = sessionData.session

            var y = 50f

            // Helpers that keep the repeated canvas.drawText boilerplate concise.
            fun text(s: String, x: Float = 50f): Float {
                canvas.drawText(s, x, y, paint)
                return y + paint.textSize + 8f
            }

            paint.textSize = 20f; paint.isFakeBoldText = true
            y = text("ECG Session Report")
            y += 12f

            paint.textSize = 12f; paint.isFakeBoldText = false
            y = text("Session: ${session.name}")
            y = text("Date: ${dateFormat.format(Date(session.startTime))}")
            y = text("Duration: ${session.durationSeconds}s")
            y = text("Sampling Rate: ${session.samplingRate} Hz")
            y += 16f

            paint.isFakeBoldText = true;  y = text("Vital Statistics");     y += 4f
            paint.isFakeBoldText = false
            y = text("Average Heart Rate: ${session.averageHeartRate} BPM",  x = 70f)
            y = text("Min Heart Rate: ${session.minHeartRate} BPM",          x = 70f)
            y = text("Max Heart Rate: ${session.maxHeartRate} BPM",          x = 70f)
            y = text("Total Beats: ${session.totalBeats}",                   x = 70f)
            y += 16f

            paint.isFakeBoldText = true;  y = text("Arrhythmia Analysis");  y += 4f
            paint.isFakeBoldText = false
            y = text("Normal Beats: ${session.normalBeats}",                 x = 70f)
            y = text("PVCs: ${session.pvcCount}",                            x = 70f)
            y = text("AFib Events: ${session.afibCount}",                    x = 70f)
            y = text("Other Arrhythmias: ${session.otherArrhythmiaCount}",   x = 70f)
            y += 16f

            if (chartBitmap != null && y + 200 < pageInfo.pageHeight) {
                val scaledWidth  = 495f
                val scaledHeight = (chartBitmap.height.toFloat() / chartBitmap.width) * scaledWidth
                canvas.drawBitmap(
                    chartBitmap,
                    null,
                    RectF(50f, y, 50f + scaledWidth, y + scaledHeight),
                    paint
                )
                y += scaledHeight + 20f
            }

            // Health status pinned near the bottom of the page
            y = maxOf(y, pageInfo.pageHeight - 80f)
            paint.isFakeBoldText = true
            canvas.drawText("Health Status: ${session.healthStatus}", 50f, y, paint)

            pdfDocument.finishPage(page)

            val file = outputFile("ECG_Report_${session.id}.pdf")
            FileOutputStream(file).use { pdfDocument.writeTo(it) }
            pdfDocument.close()

            Timber.i("PDF exported: ${file.absolutePath}")
            Result.success(file)
        } catch (e: Exception) {
            Timber.e(e, "Failed to export PDF")
            Result.failure(e)
        }
    }

    /** Writes a CSV file with session metadata header rows followed by per-sample data. */
    suspend fun exportToCsv(sessionData: SessionWithData): Result<File> = withContext(Dispatchers.IO) {
        try {
            val session = sessionData.session
            val file    = outputFile("ECG_Data_${session.id}.csv")

            FileOutputStream(file).bufferedWriter().use { writer ->
                writer.write(
                    """
                    |# ECG Session Export
                    |# Session ID: ${session.id}
                    |# Name: ${session.name}
                    |# Start Time: ${session.startTime}
                    |# Duration: ${session.durationSeconds}s
                    |# Sampling Rate: ${session.samplingRate} Hz
                    |# Avg Heart Rate: ${session.averageHeartRate} BPM
                    |#
                    |SampleIndex,Timestamp,Voltage,EventType,Confidence
                    """.trimMargin() + "\n"
                )

                sessionData.readings.forEach { reading ->
                    val event = sessionData.events.find { it.sampleIndex == reading.sampleIndex }
                    writer.write(
                        "${reading.sampleIndex},${reading.timestamp},${reading.voltage}," +
                                "${event?.type ?: ""},${event?.confidence ?: ""}\n"
                    )
                }
            }

            Timber.i("CSV exported: ${file.absolutePath}")
            Result.success(file)
        } catch (e: Exception) {
            Timber.e(e, "Failed to export CSV")
            Result.failure(e)
        }
    }

    private fun outputFile(name: String): File =
        File(context.getExternalFilesDir(null), name)
}