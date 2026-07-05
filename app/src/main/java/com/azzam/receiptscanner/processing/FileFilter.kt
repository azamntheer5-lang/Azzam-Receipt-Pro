package com.azzam.receiptscanner.processing

import java.io.File

/**
 * فلترة الملفات قبل المعالجة.
 *
 * التطوير (إصلاح جوهري): أضفنا فلتر المحتوى — لا يكفي أن يكون الملف
 * صورة/PDF بالحجم المناسب، بل يجب أن يحتوي نصه على كلمات مفتاحية للحوالات
 * البنكية. هذا يمنع معالجة الميمز/الصور الشخصية/المستندات العادية التي
 * تُلتقط من مجلد واتساب.
 */
object FileFilter {
    const val MAX_PDF_SIZE_BYTES = 1L * 1024 * 1024   // 1 ميجا
    const val MAX_IMAGE_SIZE_BYTES = 5L * 1024 * 1024  // 5 ميجا

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    /** كلمات مفتاحية تشير لأن الملف إيصال حوالة فعلية (عربي/إنجليزي). */
    private val receiptKeywords = listOf(
        // عربي
        "حوالة", "تحويل", "حواله", "تحويل بنكي", "مصرف", "بنك",
        "المبلغ", "المرسل", "المستفيد", "المستلم", "ريال", "ر.س",
        "إيصال", "ايصال", "عملية", "معاملة", "سداد", "مدى",
        "الراجحي", "الأهلي", "الاهلي", "STC", "stc",
        // إنجليزي
        "transfer", "amount", "sender", "beneficiary", "recipient",
        "receipt", "payment", "sar", "bank", "transaction",
        "rajhi", "alrajhi", "ncb", "alahli", "stc pay"
    )

    /** أنماط رقمية تشير لوجود مبلغ/حساب بنكي (دعم إضافي للكلمات المفتاحية). */
    private val amountPattern = Regex("""(?:SAR|ر\.س|ريال)\s*[\d,]+\.?\d*""", RegexOption.IGNORE_CASE)
    private val ibanPattern = Regex("""SA\d{2}\s?\d{2}\s?\d{18}""")

    /** يُطبّق فلتر النوع والحجم بالضبط كما طُلب. */
    fun isCandidateReceipt(file: File): Boolean {
        if (!file.isFile) return false
        val ext = file.extension.lowercase()
        return when {
            ext == "pdf" -> file.length() in 1..MAX_PDF_SIZE_BYTES
            ext in imageExtensions -> file.length() in 1..MAX_IMAGE_SIZE_BYTES
            else -> false
        }
    }

    fun isPdf(file: File) = file.extension.lowercase() == "pdf"

    /**
     * فلتر محتوى إضافي: يفحص نص OCR للتأكد أنه إيصال حوالة فعلية.
     *
     * يعيد true فقط إن وجد:
     *  - كلمة مفتاحية واحدة على الأقل من [receiptKeywords]، OR
     *  - نمط مبلغ بصيغة SAR/ر.س، OR
     *  - رقم IBAN سعودي
     *
     * هذا يمنع معالجة الميمز/الصور العشوائية.
     */
    fun looksLikeReceipt(ocrText: String): Boolean {
        if (ocrText.isBlank()) return false
        val lower = ocrText.lowercase()

        // فحص الكلمات المفتاحية
        if (receiptKeywords.any { kw -> lower.contains(kw.lowercase()) }) return true

        // فحص نمط المبلغ بعملة صريحة
        if (amountPattern.containsMatchIn(ocrText)) return true

        // فحص IBAN
        if (ibanPattern.containsMatchIn(ocrText)) return true

        return false
    }
}
