package com.azzam.receiptscanner.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * كيان سجل الإيصال في قاعدة بيانات Room.
 *
 * مستقل عن نموذج [com.azzam.receiptscanner.model.Transfer] (الذي يبقى
 * مسؤولاً عن التخزين المشفّر القديم)، لكنه يحمل نفس الحقول الأساسية +
 * حقل المحرك المستخدم.
 *
 * فهارس [indices] على senderName/recipientName تُسرّع استعلامات "كشف
 * الحسابات" (Group By) التي تُجمّع الحوالات بالاسم.
 */
@Entity(
    tableName = "receipts",
    indices = [
        Index(value = ["senderName"]),
        Index(value = ["recipientName"]),
        Index(value = ["date"])
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
     * مفيد لتجميع كشف الحسابات: نُجمّع كل حوالات الشخص سواء كان مرسلاً أو مستلماً.
     */
    fun effectiveName(): String? =
        recipientName?.takeIf { it.isNotBlank() } ?: senderName?.takeIf { it.isNotBlank() }
}
