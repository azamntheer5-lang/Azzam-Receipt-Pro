package com.azzam.receiptscanner.export

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.azzam.receiptscanner.model.Transfer
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * تقرير PDF (مُطوّر في المرحلة 5).
 *
 * تحسينات:
 *  - ترويسة تاريخ توليد التقرير + اسم التطبيق
 *  - عمودا المرسل والمستلم منفصلين (بدل اختصارهما)
 *  - تنبيه بصري (علامة !) للسجلات منخفضة الثقة التي تحتاج مراجعة
 *  - ترقيم الصفحات (صفحة X من Y) في التذييل
 *  - محاذاة المبلغ لليمين للأرقام
 *  - إجمالي مرئي في نهاية الجدول بإطار مميّز
 */
object PdfReportExporter {

    private const val PAGE_WIDTH = 595  // A4 بدقة 72
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f

    fun export(context: Context, transfers: List<Transfer>): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val file = File(dir, "receipt_report_$timestamp.pdf")

        val document = PdfDocument()
        val formatter = NumberFormat.getCurrencyInstance(Locale("ar", "SA"))
        val titlePaint = Paint().apply { textSize = 18f; isFakeBoldText = true; color = Color.BLACK }
        val subtitlePaint = Paint().apply { textSize = 10f; color = Color.GRAY }
        val headerPaint = Paint().apply { textSize = 11f; isFakeBoldText = true; color = Color.DKGRAY }
        val textPaint = Paint().apply { textSize = 10f; color = Color.BLACK }
        val amountPaint = Paint().apply { textSize = 10f; color = Color.BLACK; isFakeBoldText = true }
        val alertPaint = Paint().apply { textSize = 10f; color = Color.RED; isFakeBoldText = true }
        val linePaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
        val totalPaint = Paint().apply { textSize = 13f; isFakeBoldText = true; color = Color.WHITE }

        // التقسيم على صفحات: نحسب الصفوف أولاً لمعرفة عدد الصفحات
        val rowHeight = 18f
        val headerHeight = 90f
        val footerHeight = 25f
        val usableHeight = PAGE_HEIGHT - MARGIN * 2 - headerHeight - footerHeight
        val rowsPerPage = (usableHeight / rowHeight).toInt().coerceAtLeast(1)
        val pageCount = (transfers.size + rowsPerPage - 1) / rowsPerPage + 1

        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN

        // ===== الترويسة =====
        canvas.drawText("تقرير التحويلات البنكية", MARGIN, y, titlePaint)
        y += 18
        canvas.drawText(
            "تاريخ التقرير: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}",
            MARGIN, y, subtitlePaint
        )
        y += 14
        val total = transfers.sumOf { it.amount ?: 0.0 }
        val lowConfidenceCount = transfers.count { it.confidence < 0.5f }
        canvas.drawText(
            "الإجمالي: ${formatter.format(total)}   |   عدد السجلات: ${transfers.size}" +
                if (lowConfidenceCount > 0) "   |   يحتاج مراجعة: $lowConfidenceCount" else "",
            MARGIN, y, headerPaint
        )
        y += 22
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 15

        // ===== رؤوس الأعمدة =====
        canvas.drawText("التاريخ", MARGIN, y, headerPaint)
        canvas.drawText("المرسل", MARGIN + 70, y, headerPaint)
        canvas.drawText("المستلم", MARGIN + 180, y, headerPaint)
        canvas.drawText("المبلغ", MARGIN + 320, y, headerPaint)
        canvas.drawText("البنك", MARGIN + 400, y, headerPaint)
        canvas.drawText("!", MARGIN + 480, y, headerPaint)
        y += 10
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 15

        // ===== صفوف البيانات =====
        for ((index, t) in transfers.withIndex()) {
            // صفحة جديدة عند الامتلاء
            if (y > PAGE_HEIGHT - MARGIN - footerHeight) {
                drawFooter(canvas, pageNumber, pageCount)
                document.finishPage(page)
                pageNumber++
                page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                canvas = page.canvas
                y = MARGIN
            }

            canvas.drawText(t.date ?: "—", MARGIN, y, textPaint)
            canvas.drawText(truncate(t.senderName, 16), MARGIN + 70, y, textPaint)
            canvas.drawText(truncate(t.recipientName, 16), MARGIN + 180, y, textPaint)
            canvas.drawText(t.amount?.let { "%.2f".format(it) } ?: "—", MARGIN + 320, y, amountPaint)
            canvas.drawText(truncate(t.bankId, 12), MARGIN + 400, y, textPaint)
            // علامة تنبيه للسجلات منخفضة الثقة
            if (t.confidence < 0.5f) {
                canvas.drawText("!", MARGIN + 480, y, alertPaint)
            }
            y += rowHeight
        }

        // ===== صف الإجمالي مميّز =====
        y += 5
        val totalBoxTop = y
        val totalBoxBottom = y + 22
        val totalPaintFill = Paint().apply { color = Color.rgb(27, 94, 32) } // primary
        canvas.drawRect(MARGIN, totalBoxTop, PAGE_WIDTH - MARGIN, totalBoxBottom, totalPaintFill)
        canvas.drawText(
            "الإجمالي: ${formatter.format(total)}",
            MARGIN + 10, totalBoxBottom - 7, totalPaint
        )

        drawFooter(canvas, pageNumber, pageCount)
        document.finishPage(page)

        file.outputStream().use { document.writeTo(it) }
        document.close()
        return file
    }

    private fun drawFooter(canvas: android.graphics.Canvas, current: Int, total: Int) {
        val footerPaint = Paint().apply { textSize = 9f; color = Color.GRAY }
        canvas.drawText(
            "صفحة $current من $total",
            PAGE_WIDTH - MARGIN - 70f,
            PAGE_HEIGHT - 15f,
            footerPaint
        )
        canvas.drawText("ماسح إيصالات واتساب", MARGIN, PAGE_HEIGHT - 15f, footerPaint)
    }

    private fun truncate(value: String?, maxLen: Int): String {
        val s = value.orEmpty()
        return if (s.length > maxLen) s.take(maxLen - 1) + "…" else s.ifBlank { "—" }
    }
}
