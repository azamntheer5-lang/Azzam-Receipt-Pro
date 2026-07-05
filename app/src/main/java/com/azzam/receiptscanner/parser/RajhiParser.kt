package com.azzam.receiptscanner.parser

/**
 * محلّل إيصالات مصرف الراجحي.
 *
 * إصلاح: تشديد عبر FieldExtractors المشترك (مع القائمة السوداء والأنماط
 * الأصرم). يدعم التسميات ثنائية اللغة لأن إيصالات الراجحي غالباً ثنائية.
 */
class RajhiParser : BankReceiptParser {
    override val bankId = "al_rajhi"

    override fun matches(text: String) =
        text.contains("الراجحي") || text.contains("Rajhi", ignoreCase = true)

    override fun parse(text: String): ParsedFields? {
        // بدون مبلغ موثوق، لا تُرجع بيانات مضلِّلة
        val amount = FieldExtractors.extractAmount(text) ?: return null

        val date = FieldExtractors.extractDate(text)
        val recipient = FieldExtractors.extractNameByLabels(
            text,
            listOf("Beneficiary", "المستفيد", "Recipient", "المستلم")
        )
        val sender = FieldExtractors.extractNameByLabels(
            text,
            listOf("From", "Sender", "المرسل")
        )

        return ParsedFields(
            senderName = sender,
            recipientName = recipient,
            amount = amount,
            date = date
        )
    }
}
