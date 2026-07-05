package com.azzam.receiptscanner.parser

data class ParsedFields(
    val senderName: String?,
    val recipientName: String?,
    val amount: Double?,
    val date: String?
)

/**
 * لإضافة بنك جديد: أنشئ صنفاً جديداً يطبّق هذا الواجهة (انظر RajhiParser
 * وStcPayParser كمثال)، ثم أضفه لقائمة parsers في ParserRegistry.
 * لا حاجة لتعديل أي كود آخر.
 */
interface BankReceiptParser {
    val bankId: String
    fun matches(ocrText: String): Boolean
    fun parse(ocrText: String): ParsedFields?
}
