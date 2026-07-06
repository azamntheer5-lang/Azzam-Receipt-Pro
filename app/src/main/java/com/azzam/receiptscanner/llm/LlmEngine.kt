package com.azzam.receiptscanner.llm

/**
 * واجهة موحّدة لجميع محركات الذكاء الاصطناعي.
 *
 * تطبيق نمط Strategy مع دعم Hints (المرحلة الثانية الهجينة).
 */
interface LlmEngine {
    val engineId: String
    val displayName: String

    /**
     * يستخرج بيانات الحوالة من نص OCR (مع retry تلقائي داخلي).
     *
     * @param ocrText نص الـ OCR الخام
     * @param apiKey مفتاح الـ API
     * @return [LlmExtractionResult] أو null عند الفشل التام
     */
    suspend fun extract(ocrText: String, apiKey: String): LlmExtractionResult?

    /**
     * يستخرج بيانات الحوالة مع تضمين hints من Regex.
     * التنفيذ الافتراضي: يتجاهل hints ويستدعي extract العادية.
     * المحركات يمكنها override لتستفيد من hints.
     */
    suspend fun extractWithHints(
        ocrText: String,
        apiKey: String,
        hints: ExtractionHints?
    ): LlmExtractionResult? = extract(ocrText, apiKey)
}
