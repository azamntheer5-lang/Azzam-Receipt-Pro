package com.azzam.receiptscanner.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * كيان سجل الإيصال في قاعدة بيانات Room.
 *
 * تحسين: إضافة originalFilePath لحفظ المسار الأصلي للملف/الـ URI
 * لعرضه في شاشة المراجعة (Side-by-Side Verification).
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
    val llmEngineUsed: String?,
    /** المسار الأصلي للملف أو URI — لعرضه في شاشة المراجعة. */
    val originalFilePath: String? = null
) {
    fun effectiveName(): String? =
        recipientName?.takeIf { it.isNotBlank() } ?: senderName?.takeIf { it.isNotBlank() }
}
