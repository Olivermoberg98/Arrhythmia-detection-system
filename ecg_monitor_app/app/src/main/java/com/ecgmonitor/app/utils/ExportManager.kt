package com.ecgmonitor.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import com.ecgmonitor.app.data.database.entities.SessionWithData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Export Manager for PDF and CSV generation
 */
class ExportManager(private val context: Context) {

    /**
     * Export session as PDF
     * Returns the file path
     */
    suspend fun exportToPdf(
        sessionData: SessionWithData,
        chartBitmap: Bitmap? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 size
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas
            val paint = android.graphics.Paint()

            var yPosition = 50f

            // Title
            paint.textSize = 20f
            paint.isFakeBoldText = true
            canvas.drawText("ECG Session Report", 50f, yPosition, paint)
            yPosition += 40f

            // Session metadata
            paint.textSize = 12f
            paint.isFakeBoldText = false

            val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            canvas.drawText("Session: ${sessionData.session.name}", 50f, yPosition, paint)
            yPosition += 20f
            canvas.drawText("Date: ${dateFormat.format(Date(sessionData.session.startTime))}", 50f, yPosition, paint)
            yPosition += 20f
            canvas.drawText("Duration: ${sessionData.session.durationSeconds}s", 50f, yPosition, paint)
            yPosition += 20f
            canvas.drawText("Sampling Rate: ${sessionData.session.samplingRate} Hz", 50f, yPosition, paint)
            yPosition += 40f

            // Statistics
            paint.isFakeBoldText = true
            canvas.drawText("Vital Statistics", 50f, yPosition, paint)
            yPosition += 25f
            paint.isFakeBoldText = false

            canvas.drawText("Average Heart Rate: ${sessionData.session.averageHeartRate} BPM", 70f, yPosition, paint)
            yPosition += 20f
            canvas.drawText("Min Heart Rate: ${sessionData.session.minHeartRate} BPM", 70f, yPosition, paint)
            yPosition += 20f
            canvas.drawText("Max Heart Rate: ${sessionData.session.maxHeartRate} BPM", 70f, yPosition, paint)
            yPosition += 20f
            canvas.drawText("Total Beats: ${sessionData.session.totalBeats}", 70f, yPosition, paint)
            yPosition += 40f

            // Arrhythmia summary
            paint.isFakeBoldText = true
            canvas.drawText("Arrhythmia Analysis", 50f, yPosition, paint)
            yPosition += 25f
            paint.isFakeBoldText = false

            canvas.drawText("Normal Beats: ${sessionData.session.normalBeats}", 70f, yPosition, paint)
            yPosition += 20f
            canvas.drawText("PVCs: ${sessionData.session.pvcCount}", 70f, yPosition, paint)
            yPosition += 20f
            canvas.drawText("AFib Events: ${sessionData.session.afibCount}", 70f, yPosition, paint)
            yPosition += 20f
            canvas.drawText("Other Arrhythmias: ${sessionData.session.otherArrhythmiaCount}", 70f, yPosition, paint)
            yPosition += 40f

            // Chart image (if provided)
            if (chartBitmap != null && yPosition + 200 < pageInfo.pageHeight) {
                val scaledWidth = 495f // Fit to page width with margins
                val scaledHeight = (chartBitmap.height.toFloat() / chartBitmap.width) * scaledWidth
                canvas.drawBitmap(
                    chartBitmap,
                    null,
                    android.graphics.RectF(50f, yPosition, 50f + scaledWidth, yPosition + scaledHeight),
                    paint
                )
                yPosition += scaledHeight + 20f
            }

            // Health status
            yPosition = maxOf(yPosition, 700f) // Near bottom
            paint.isFakeBoldText = true
            canvas.drawText("Health Status: ${sessionData.session.healthStatus}", 50f, yPosition, paint)

            pdfDocument.finishPage(page)

            // Save to file
            val outputDir = context.getExternalFilesDir(null)
            val fileName = "ECG_Report_${sessionData.session.id}.pdf"
            val file = File(outputDir, fileName)

            FileOutputStream(file).use { outputStream ->
                pdfDocument.writeTo(outputStream)
            }

            pdfDocument.close()

            Timber.i("PDF exported: ${file.absolutePath}")
            Result.success(file)

        } catch (e: Exception) {
            Timber.e(e, "Failed to export PDF")
            Result.failure(e)
        }
    }

    /**
     * Export session as CSV
     * Returns the file path
     */
    suspend fun exportToCsv(sessionData: SessionWithData): Result<File> = withContext(Dispatchers.IO) {
        try {
            val outputDir = context.getExternalFilesDir(null)
            val fileName = "ECG_Data_${sessionData.session.id}.csv"
            val file = File(outputDir, fileName)

            FileOutputStream(file).bufferedWriter().use { writer ->
                // Header - Session metadata
                writer.write("# ECG Session Export\n")
                writer.write("# Session ID: ${sessionData.session.id}\n")
                writer.write("# Name: ${sessionData.session.name}\n")
                writer.write("# Start Time: ${sessionData.session.startTime}\n")
                writer.write("# Duration: ${sessionData.session.durationSeconds}s\n")
                writer.write("# Sampling Rate: ${sessionData.session.samplingRate} Hz\n")
                writer.write("# Avg Heart Rate: ${sessionData.session.averageHeartRate} BPM\n")
                writer.write("#\n")

                // Column headers
                writer.write("SampleIndex,Timestamp,Voltage,EventType,Confidence\n")

                // Data rows
                sessionData.readings.forEach { reading ->
                    // Find matching event if exists
                    val event = sessionData.events.find { it.sampleIndex == reading.sampleIndex }

                    writer.write(
                        "${reading.sampleIndex}," +
                                "${reading.timestamp}," +
                                "${reading.voltage}," +
                                "${event?.type ?: ""}," +
                                "${event?.confidence ?: ""}\n"
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
}