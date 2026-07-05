package com.azzam.receiptscanner.export

import android.content.Context
import com.azzam.receiptscanner.model.Transfer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * تصدير CSV (مُطوّر في المرحلة 5).
 *
 * تحسينات:
 *  - عمود منفصل للمرسل والمستلم (بدل اختصارهما في حقل واحد)
 *  - عمود المحرك المستخدم (llmEngineUsed) — مفيد للتدقيق
 *  - عمود الثقة (confidence) — لتمييز السجلات التي تحتاج مراجعة
 *  - ترتيب الأعمدة منطقي: التاريخ → المرسل → المستلم → المبلغ → البنك → المحرك → الثقة
 */
object CsvExporter {

    /** يصدّر ملف CSV يفتح مباشرة في Excel/Google Sheets، مع BOM لضمان ظهور العربي صحيحاً. */
    fun export(context: Context, transfers: List<Transfer>): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val file = File(dir, "transfers_$timestamp.csv")

        val sb = StringBuilder()
        sb.append("التاريخ,المرسل,المستلم,المبلغ,البنك,المحرك,الثقة\n")
        transfers.forEach { t ->
            sb.append(t.date.orEmpty()).append(",")
            sb.append(escapeCsv(t.senderName.orEmpty())).append(",")
            sb.append(escapeCsv(t.recipientName.orEmpty())).append(",")
            sb.append(t.amount?.let { "%.2f".format(it) } ?: "").append(",")
            sb.append(escapeCsv(t.bankId)).append(",")
            sb.append(escapeCsv(t.llmEngineUsed.orEmpty())).append(",")
            sb.append("%.0f%%".format(t.confidence * 100)).append("\n")
        }
        val total = transfers.sumOf { it.amount ?: 0.0 }
        sb.append(",,الإجمالي:,").append("%.2f".format(total)).append(",,\n")

        file.outputStream().use { out ->
            out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // UTF-8 BOM لدعم Excel
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
        }
        return file
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
    }
}
