package com.azzam.receiptscanner.parser

import com.azzam.receiptscanner.llm.LlmExtractionResult

/**
 * طبقة تنظيف البيانات (Data Sanitization) بعد استخراجها من الـ LLM.
 *
 * يطبّق:
 *  1. تنظيف المسافات الزائدة والرموز غير المرئية
 *  2. توحيد تنسيق التاريخ (dd/mm/yyyy → yyyy-MM-dd)
 *  3. تطهير المبلغ (إزالة العملات/الفواصل)
 *  4. تصحيح أسماء البنوك عبر BankMatcher (Fuzzy Matching)
 *  5. رفض القيم الواضحة الخطأ (IBAN كاسم، أرقام كاسم، إلخ)
 *
 * الهدف: رفع دقة البيانات النهائية لخلوها من الأخطاء.
 */
object DataSanitizer {

    /**
     * ينظّف نتيجة استخراج LLM ويُرجع ParsedFields نظيفاً.
     */
    fun sanitize(result: LlmExtractionResult, rawBankId: String? = null): ParsedFields {
        return ParsedFields(
            senderName = sanitizeName(result.senderName),
            recipientName = sanitizeName(result.receiverName),
            amount = sanitizeAmount(result.amount),
            date = sanitizeDate(result.date)
        )
    }

    /** ينظّف اسماً: مسافات + رموز + رفض القيم الواضحة الخطأ. */
    fun sanitizeName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
            .replace(Regex("\\s+"), " ")
            .replace("\u200b", "") // zero-width space
            .replace("\ufeff", "") // BOM

        // رفض IBAN كاسم
        if (Regex("""^SA\d{2}[\d\s\*]+""", RegexOption.IGNORE_CASE).matches(s)) return null
        if (Regex("""^SA\*+""", RegexOption.IGNORE_CASE).matches(s)) return null

        // رفض أرقام حسابات (أغلبها أرقام)
        val digits = s.count { it.isDigit() }
        val letters = s.count { it.isLetter() }
        if (digits > letters && digits > 4) return null

        // رفض أرقام بمفردها
        if (s.toDoubleOrNull() != null) return null

        // رفض نصوص placeholder بين أقواس
        if (s.startsWith("[") || s.startsWith("(")) return null

        // رفض القيم الفارغة الصريحة
        val lower = s.lowercase()
        if (lower in listOf("", "null", "none", "na", "n/a", "فارغ", "—", "-")) return null

        return s.takeIf { it.isNotBlank() && letters >= 1 }
    }

    /** يطهّر المبلغ: أرقام فقط مع فاصلة عشرية. */
    fun sanitizeAmount(raw: Double?): Double? {
        if (raw == null) return null
        return if (raw >= 1.0 && raw <= 10_000_000.0) raw else null
    }

    /** يوحّد التاريخ إلى yyyy-MM-dd ويرفض المستقبلية. */
    fun sanitizeDate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()

        // حاول صيغاً شائعة
        val patterns = listOf(
            Regex("""(\d{4})-(\d{1,2})-(\d{1,2})"""),
            Regex("""(\d{1,2})/(\d{1,2})/(\d{4})"""),
            Regex("""(\d{1,2})-(\d{1,2})-(\d{4})"""),
            Regex("""(\d{4})/(\d{1,2})/(\d{1,2})""")
        )
        for ((i, pattern) in patterns.withIndex()) {
            val m = pattern.find(s) ?: continue
            val (g1, g2, g3) = m.destructured
            val normalized = when {
                // yyyy-mm-dd أو yyyy/mm/dd
                g1.length == 4 -> "%s-%02d-%02d".format(g1, g2.toInt(), g3.toInt())
                // dd/mm/yyyy أو dd-mm-yyyy
                g3.length == 4 -> "%s-%02d-%02d".format(g3, g2.toInt(), g1.toInt())
                else -> continue
            }
            // تحقق من أنه ليس مستقبلياً (> 7 أيام)
            return validateNotFuture(normalized) ?: continue
        }
        return null
    }

    /** يصحّح bankId عبر BankMatcher. */
    fun sanitizeBankId(rawBankId: String?): String {
        return BankMatcher.normalizeBankId(rawBankId) ?: "unknown"
    }

    /** يتحقق أن التاريخ ليس مستقبلياً (> 7 أيام). */
    private fun validateNotFuture(normalized: String): String? {
        return try {
            val parts = normalized.split("-")
            val y = parts[0].toInt()
            val m = parts[1].toInt()
            val d = parts[2].toInt()
            val cal = java.util.Calendar.getInstance()
            val input = java.util.Calendar.getInstance().apply {
                set(y, m - 1, d, 0, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val diffDays = (input.timeInMillis - cal.timeInMillis) / (1000L * 60 * 60 * 24)
            if (diffDays > 7) null else normalized
        } catch (e: Exception) {
            null
        }
    }
}
