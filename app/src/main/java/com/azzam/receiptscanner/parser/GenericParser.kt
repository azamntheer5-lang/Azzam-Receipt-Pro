package com.azzam.receiptscanner.parser

/**
 * خط الدفاع الأخير: يُستخدم عندما لا يتطابق النص مع أي بنك معروف.
 *
 * التطوير (المرحلة 1): استخدام FieldExtractors المشترك، وإضافة محاولة
 * استخراج الأسماء عبر التسميات الشائعة (كانت تُرجع null دائماً). كما
 * يوحّد صيغة التاريخ الآن إلى yyyy-MM-dd.
 */
class GenericParser : BankReceiptParser {
    override val bankId = "generic"

    override fun matches(text: String) = true

    override fun parse(text: String): ParsedFields? {
        val amount = FieldExtractors.extractAmount(text)
        val date = FieldExtractors.extractDate(text)

        // بدون مبلغ أو تاريخ، لا فائدة من السجل
        if (amount == null && date == null) return null

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
