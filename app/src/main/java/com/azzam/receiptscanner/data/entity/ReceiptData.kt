package com.azzam.receiptscanner.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * كيان سجل الإيصال في قاعدة بيانات Room.
 *
 * تحسين: إضافة فهارس مركّبة على (senderName, amount) و(recipientName, amount)
 * لتسريع استعلامات كشف الحسابات والإحصائيات حتى مع آلاف السجلات.
 */
@Entity(
    tableName = "receipts",
    indices = [
        Index(value = ["senderName"]),
        Index(value = ["recipientName"]),
        Index(value = ["date"]),
        Index(value = ["bankId"]),
        Index(value = ["processedAt"]),
        Index(value = ["amount"]),
        Index(value = ["confidence"]),
        // فهارس مركّبة لتسريع التجميع الشائع
        Index(value = ["senderName", "amount"]),
        Index(value = ["recipientName", "amount"]),
        Index(value = ["bankId", "amount"])
    ]
)
data class ReceiptData(
    @PrimaryKey
    val id: String,
    val senderName: String?,
    val recipientName: String?,
    val amount: Double?,
    val date: String?,
    val bankId: String,
    val confidence: Float,
    val sourceFileName: String,
    val processedAt: Long,
    val rawText: String,
    /** المحرك المستخدم (claude/gemini/groq/huggingface) أو null. */
    val llmEngineUsed: String?
) {
    /**
     * يُرجع الاسم "الفعّال" للسجل — المستلم إن وُجد وإلا المرسل.
     */
    fun effectiveName(): String? =
        recipientName?.takeIf { it.isNotBlank() } ?: senderName?.takeIf { it.isNotBlank() }
}
