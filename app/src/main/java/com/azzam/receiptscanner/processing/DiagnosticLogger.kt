package com.azzam.receiptscanner.processing

import android.content.Context

/**
 * مسجّل تشخيصي — يجمّع إحصائيات معالجة الملفات.
 *
 * يُعرض عبر MainActivity مباشرة (ليس عبر WorkManager) لضمان ظهور فوري.
 */
object DiagnosticLogger {

    private val stats = mutableMapOf<String, Int>()

    fun reset() {
        stats.clear()
    }

    fun logFileProcessed() {
        stats["processed"] = (stats["processed"] ?: 0) + 1
    }

    fun logFileSaved() {
        stats["saved"] = (stats["saved"] ?: 0) + 1
    }

    fun logRejection(reason: String) {
        stats[reason] = (stats[reason] ?: 0) + 1
    }

    /** يبني تقرير تشخيصي. */
    fun buildReport(): String {
        val processed = stats["processed"] ?: 0
        val saved = stats["saved"] ?: 0
        if (processed == 0) {
            return "❌ لم نجد أي ملفات لفحصها.\n\n" +
                "الحل: من القائمة (⋮) اختر:\n" +
                "📁 فحص مجلد كامل\n" +
                "واختر مجلد الصور من المعرض مباشرة."
        }

        val sb = StringBuilder()
        sb.append("📊 النتائج:\n")
        sb.append("• فحصنا: $processed ملف\n")
        sb.append("• حفظنا: $saved إيصال\n")
        if (saved == 0 && processed > 0) {
            sb.append("\n🔍 أسباب الرفض:\n")
            val reasons = stats.filterKeys { it != "processed" && it != "saved" }
            if (reasons.isEmpty()) {
                sb.append("• (لا أسباب مسجّلة)\n")
            } else {
                reasons.forEach { (reason, count) ->
                    sb.append("• $reason: $count\n")
                }
            }
        }
        return sb.toString().trim()
    }
}
