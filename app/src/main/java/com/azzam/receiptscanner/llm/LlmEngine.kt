package com.azzam.receiptscanner.llm

import java.io.File

/**
 * واجهة موحّدة لجميع محركات الذكاء الاصطناعي.
 *
 * التحديث: إزالة الاعتماد على OCR محلي تماماً. المحرك يستقبل الملف
 * مباشرة (صورة/PDF) ويستخرج البيانات كاملة عبر Vision API.
 */
interface LlmEngine {
    val engineId: String
    val displayName: String

    /**
     * يستخرج بيانات الحوالة من ملف (صورة/PDF) مباشرة عبر Vision API.
     * لا حاجة لـ OCR محلي — المحرك يقرأ الصورة بنفسه.
     *
     * @param file ملف الصورة أو PDF
     * @param apiKey مفتاح الـ API
     * @return [LlmExtractionResult] أو null عند الفشل
     */
    suspend fun extractFromFile(file: File, apiKey: String): LlmExtractionResult?

    /** طريقة قديمة للتوافق الخلفي — تستخدم extractFromFile. */
    suspend fun extract(ocrText: String, apiKey: String): LlmExtractionResult? = null

    suspend fun extractWithHints(
        ocrText: String,
        apiKey: String,
        hints: ExtractionHints?
    ): LlmExtractionResult? = null
}
