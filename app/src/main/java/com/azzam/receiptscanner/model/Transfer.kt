package com.azzam.receiptscanner.model

import kotlinx.serialization.Serializable

@Serializable
data class Transfer(
    val id: String,
    val senderName: String? = null,
    val recipientName: String? = null,
    val amount: Double? = null,
    val date: String? = null,
    val bankId: String,
    // 0.0 - 1.0: كم نثق باستخراج هذا السجل
    val confidence: Float,
    val sourceFileName: String,
    val processedAt: Long,
    // أول 500 حرف من نص الـOCR الخام، للمراجعة اليدوية عند الحاجة فقط
    val rawText: String = "",
    // اسم المحرك المستخدم للاستخراج السحابي (claude/gemini/groq/huggingface)،
    // أو null إن استُخرج محلياً فقط — مفيد للتشخيص ولفلترة السجلات
    val llmEngineUsed: String? = null
)
