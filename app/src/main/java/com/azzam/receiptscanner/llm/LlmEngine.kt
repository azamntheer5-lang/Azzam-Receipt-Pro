package com.azzam.receiptscanner.llm

/**
 * واجهة موحّدة لجميع محركات الذكاء الاصطناعي.
 *
 * تطبيق نمط Strategy: كل محرك يطبّق هذه الواجهة، والمستهلك (ReceiptProcessor)
 * لا يعرف أي محرك نشط — فقط يستدعي [extract]. هذا يفصل منطق الاستخراج عن
 * تفاصيل كل API، ويسهّل إضافة محركات مستقبلاً دون تعديل المستهلك (OCP).
 */
interface LlmEngine {
    /** معرّف فريد للمحرك (يُستخدم في الإعدادات والتخزين). */
    val engineId: String

    /** الاسم المعروض للمستخدم. */
    val displayName: String

    /**
     * يستخرج بيانات الحوالة من نص OCR الخام.
     *
     * @param ocrText نص الـ OCR الخام (قد يكون فوضوياً/مشوّهاً).
     * @param apiKey مفتاح الـ API الخاص بالمحرك (يجب ألا يكون فارغاً).
     * @return [LlmExtractionResult] يحوي JSON المُفسَّر، أو null عند الفشل.
     */
    suspend fun extract(ocrText: String, apiKey: String): LlmExtractionResult?
}
