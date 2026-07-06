package com.azzam.receiptscanner.parser

/**
 * محلّل إيصالات مصرف الراجحي.
 * يطبّق BankMatcher لتصحيح bankId تلقائياً.
 */
class RajhiParser : BankReceiptParser {
    override val bankId = "al_rajhi"

    override fun matches(text: String): Boolean {
        // تطابق مرن عبر BankMatcher (يدعم أخطاء OCR)
        return BankMatcher.match(text) != null &&
            BankMatcher.match(text)!!.first.startsWith("al_rajhi") ||
            text.contains("الراجحي") || text.contains("Rajhi", ignoreCase = true)
    }

    override fun parse(text: String): ParsedFields? {
        val amount = FieldExtractors.extractAmount(text) ?: return null
        val date = FieldExtractors.extractDate(text)
        val recipient = FieldExtractors.extractNameByLabels(
            text, listOf("Beneficiary", "المستفيد", "Recipient", "المستلم")
        )
        val sender = FieldExtractors.extractNameByLabels(
            text, listOf("From", "Sender", "المرسل")
        )
        return ParsedFields(sender, recipient, amount, date)
    }
}
