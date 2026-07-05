package com.azzam.receiptscanner.processing

import java.io.File

/**
 * فلترة الملفات قبل المعالجة.
 *
 * إصلاح مبني على تحليل 452 ملف حقيقي من مجلدات واتساب:
 *  - 272 صورة (مزيج: إيصالات، أسئلة امتحانات، لقطات LMS، رسائل SMS)
 *  - 157 PDF (مزيج: إيصالات بنكية، سير ذاتية، تقارير معملية، واجبات)
 *  - 23 ملفات أخرى (docx, xlsx — ليست إيصالات أبداً)
 *
 * استراتيجية الفلترة (طبقات):
 *  1. اسم الملف: كلمات مفتاحية إيجابية/سلبية (أسرع — قبل OCR)
 *  2. نوع/حجم الملف: pdf/jpg/png/webp ضمن حدود الحجم
 *  3. محتوى OCR: كلمات مفتاحية للحوالات البنكية
 */
object FileFilter {
    const val MAX_PDF_SIZE_BYTES = 2L * 1024 * 1024   // 2 ميجا (رفعنا الحد قليلاً)
    const val MAX_IMAGE_SIZE_BYTES = 5L * 1024 * 1024  // 5 ميجا

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    /** كلمات في اسم الملف تشير لإيصال حوالة (إيجابية). */
    private val receiptFilenameKeywords = listOf(
        "receipt", "transfer", "transaction", "alinma-report", "alrajhi",
        "stcpay", "stc-pay", "bank-transfer", "payment-receipt"
    )

    /** كلمات في اسم الملف تشير لملف غير إيصال (سلبية). */
    private val nonReceiptFilenameKeywords = listOf(
        "cv", "resume", "lab", "exam", "assignment", "homework", "worksheet",
        "summary", "ملخص", "سيرة", "عملي", "واجب", "محاضرة", "عرض", "presentation",
        "docx", "xlsx", "pptx", "curriculum", "syllabus", "schedule", "plan",
        "timetable", "guide", "manual", "report-lab"
    )

    /** كلمات مفتاحية في نص OCR تشير لإيصال حوالة. */
    private val receiptContentKeywords = listOf(
        // عربي
        "حوالة", "تحويل", "حواله", "تحويل بنكي", "مصرف", "بنك",
        "المبلغ", "المرسل", "المستفيد", "المستلم", "ريال", "ر.س",
        "إيصال", "ايصال", "عملية", "معاملة", "سداد", "مدى",
        "الراجحي", "الأهلي", "الاهلي", "STC", "stc", "الإنماء", "الانماء", "alinma",
        "تم التحويل", "تمت العملية", "مبلغ محول", "استلام مبلغ",
        // إنجليزي
        "transfer", "amount", "sender", "beneficiary", "recipient",
        "receipt", "payment", "sar", "bank", "transaction",
        "rajhi", "alrajhi", "ncb", "alahli", "stc pay", "alinma",
        "money sent", "transfer receipt", "transaction receipt",
        "successfully sent", "successfully transferred"
    )

    /** أنماط رقمية تشير لوجود مبلغ/حساب بنكي. */
    private val amountPattern = Regex("""(?:SAR|ر\.س|ريال)\s*[\d,]+\.?\d*""", RegexOption.IGNORE_CASE)
    private val ibanPattern = Regex("""SA\d{2}\s?\d{2}\s?\d{18}""")
    private val maskedIbanPattern = Regex("""SA\*+\s*\*+\s*\*+\s*\*+\s*\*+\s*\d+""")

    /** يُطبّق فلتر النوع والحجم. */
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
     * فلتر اسم الملف — يُستخدم قبل OCR لتوفير الوقت.
     * يعيد:
     *  - true: اسم الملف يشير لإيصال بقوة (تجاوز فلتر المحتوى)
     *  - false: اسم الملف يشير لغير إيصال (تجاهل فوراً)
     *  - null: غير محدد — اقرأ المحتوى وقرّر
     */
    fun filenameHint(file: File): Boolean? {
        val name = file.name.lowercase()
        // إيجابي: يحوي كلمة إيصال
        if (receiptFilenameKeywords.any { it in name }) return true
        // سلبي: يحوي كلمة غير إيصال
        if (nonReceiptFilenameKeywords.any { it in name }) return false
        return null
    }

    /**
     * فلتر محتوى: يفحص نص OCR للتأكد أنه إيصال حوالة.
     * يعيد true فقط إن وجد كلمات مفتاحية قوية أو أنماط بنكية.
     */
    fun looksLikeReceipt(ocrText: String): Boolean {
        if (ocrText.isBlank()) return false
        val lower = ocrText.lowercase()

        // فحص الكلمات المفتاحية
        if (receiptContentKeywords.any { kw -> lower.contains(kw.lowercase()) }) return true

        // فحص نمط المبلغ بعملة صريحة
        if (amountPattern.containsMatchIn(ocrText)) return true

        // فحص IBAN (كامل أو مقنّع)
        if (ibanPattern.containsMatchIn(ocrText)) return true
        if (maskedIbanPattern.containsMatchIn(ocrText)) return true

        return false
    }
}
