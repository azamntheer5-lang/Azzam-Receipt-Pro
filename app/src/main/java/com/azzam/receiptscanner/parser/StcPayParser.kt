package com.example.receiptscanner.parser

/**
 * محلّل إيصالات STC Pay.
 *
 * إصلاح: تشديد عبر FieldExtractors المشترك. STC Pay يستخدم تسميات
 * إنجليزية بشكل أساسي مع بعض العربية أحياناً.
 */
class StcPayParser : BankReceiptParser {
    override val bankId = "stc_pay"

    override fun matches(text: String) =
        text.contains("STC Pay", ignoreCase = true) || text.contains("stc pay", ignoreCase = true)

    override fun parse(text: String): ParsedFields? {
        val amount = FieldExtractors.extractAmount(text) ?: return null

        val date = FieldExtractors.extractDate(text)
        val sender = FieldExtractors.extractNameByLabels(text, FieldExtractors.SENDER_LABELS)
        val recipient = FieldExtractors.extractNameByLabels(text, FieldExtractors.RECIPIENT_LABELS)

        return ParsedFields(
            senderName = sender,
            recipientName = recipient,
            amount = amount,
            date = date
        )
    }
}
