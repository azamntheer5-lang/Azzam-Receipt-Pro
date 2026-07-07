package com.azzam.receiptscanner.data.entity

/**
 * نتيجة تجميع كشف الحساب لشخص واحد.
 * يحوي الاسم، عدد الحوالات، الإجمالي، والقائمة التفصيلية.
 *
 * لا تُخزَّن في Room — تُبنى ديناميكياً من استعلامات DAO.
 */
data class AccountStatement(
    val name: String,
    val role: String,          // "sender" أو "recipient" أو "both"
    val transferCount: Int,
    val totalAmount: Double,
    val transfers: List<ReceiptData>
)
