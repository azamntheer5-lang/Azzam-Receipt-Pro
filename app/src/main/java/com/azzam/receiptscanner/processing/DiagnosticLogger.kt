package com.azzam.receiptscanner.processing

import android.content.Context
import android.widget.Toast

/**
 * مسجّل تشخيصي — يجمّع إحصائيات معالجة الملفات ويعرضها للمستخدم.
 *
 * هذا يحل مشكلة "الواجهة فارغة دون سبب واضح" — يُظهر للمستخدم بالضبط:
 *  - كم ملف فُحص
 *  - كم رُفض ولماذا (ليس إيصال/صغير/كبير/فشل OCR/لا كلمات مفتاحية)
 *  - كم حُفظ بنجاح
 */
object DiagnosticLogger {

    private val stats = mutableMapOf<String, Int>()
    private val rejectionReasons = mutableListOf<String>()

    fun reset() {
        stats.clear()
        rejectionReasons.clear()
    }

    fun logFileProcessed() {
        stats["processed"] = (stats["processed"] ?: 0) + 1
    }

    fun logFileSaved() {
        stats["saved"] = (stats["saved"] ?: 0) + 1
    }

    fun logRejection(reason: String) {
        stats[reason] = (stats[reason] ?: 0) + 1
        if (rejectionReasons.size < 10) {
            rejectionReasons.add(reason)
        }
    }

    /** يبني تقرير تشخيصي للمستخدم. */
    fun buildReport(context: Context): String {
        val processed = stats["processed"] ?: 0
        val saved = stats["saved"] ?: 0
        if (processed == 0) return "❌ لم نجد أي ملفات لفحصها. اختر مجلداً يدوياً من القائمة."

        val sb = StringBuilder()
        sb.append("📊 فحصنا $processed ملف، حفظنا $saved إيصال.\n")
        if (saved == 0) {
            sb.append("\nأسباب الرفض:\n")
            val reasons = stats.filterKeys { it != "processed" && it != "saved" }
            if (reasons.isEmpty()) {
                sb.append("• لا أسباب مسجّلة\n")
            } else {
                reasons.forEach { (reason, count) ->
                    sb.append("• $reason: $count ملف\n")
                }
            }
        }
        return sb.toString().trim()
    }

    /** يعرض التقرير كـ Toast طويل. */
    fun showReport(context: Context) {
        val report = buildReport(context)
        val mainHandler = android.os.Handler(context.mainLooper)
        // Toast متعدد الأسطر — استخدم Toast_LONG عدة مرات إن لزم
        mainHandler.post {
            val toast = Toast.makeText(context, report, Toast.LENGTH_LONG)
            toast.show()
        }
    }
}
