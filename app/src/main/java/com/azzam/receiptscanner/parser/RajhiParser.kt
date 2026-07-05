package com.azzam.receiptscanner.parser

/**
 * محلّل إيصالات مصرف الراجحي.
 *
 * التطوير (المرحلة 1): استخدام FieldExtractors المشترك، وإضافة استخراج
 * اسم المرسل (كان يُرجع null دائماً سابقاً). يدعم التسميات ثنائية
 * اللغة لأن إيصالات الراجحي غالباً ثنائية اللغة.
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
            listOf("Beneficiary", "المستفيد", "To", "إلى", "المستلم")
        )
        val sender = FieldExtractors.extractNameByLabels(
            text,
            listOf("From", "Sender", "المرسل", "من")
        )

        return ParsedFields(
            senderName = sender,
            recipientName = recipient,
            amount = amount,
            date = date
        )
    }
}
