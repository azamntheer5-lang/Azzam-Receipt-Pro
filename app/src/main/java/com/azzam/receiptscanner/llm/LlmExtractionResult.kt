package com.azzam.receiptscanner.llm

import kotlinx.serialization.Serializable

/**
 * نتيجة استخراج بيانات الحوالة من محرك ذكاء اصطناعي.
 *
 * الحقول قابلة لـ null لأن النموذج قد لا يستطيع استخراج بعضها — المستهلك
 * (ReceiptProcessor) يدمج هذه مع نتائج Regex المحلية عبر mergeFields.
 */
@Serializable
data class LlmExtractionResult(
    val senderName: String? = null,
    val receiverName: String? = null,
    val amount: Double? = null,
    val date: String? = null,
    /** اسم المحرك الذي أنتج هذه النتيجة — مفيد للتشخيص والثقة. */
    val engineId: String = "unknown"
)
