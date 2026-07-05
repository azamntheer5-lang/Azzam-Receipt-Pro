package com.azzam.receiptscanner.parser

/**
 * خط الدفاع الأخير: يُستخدم عندما لا يتطابق النص مع أي بنك معروف.
 *
 * إصلاح جوهري: تشديد كبير لمنع false positives:
 *  - لا يقبل أي رقم بكسور (كان يلتقط أسعار المنتجات والتواريخ)
 *  - يشترط وجود عملة صريحة (SAR/ر.س) أو تسمية مبلغ
 *  - لا يحفظ سجلاً بمبلغ < 1.0 أو > 10,000,000
 *  - الأسماء: يحاول استخراجها فقط عبر التسميات، لا يخمن
 */
class GenericParser : BankReceiptParser {
    override val bankId = "generic"

    override fun matches(text: String) = true

    override fun parse(text: String): ParsedFields? {
        // المبلغ الآن صارم: يجب أن يكون مرتبطاً بعملة/تسمية
        val amount = FieldExtractors.extractAmount(text) ?: return null

        val date = FieldExtractors.extractDate(text)

        // الأسماء: نحاول فقط عبر التسميات الواضحة، لا نخمن
        val sender = FieldExtractors.extractNameByLabels(text, FieldExtractors.SENDER_LABELS)
        val recipient = FieldExtractors.extractNameByLabels(text, FieldExtractors.RECIPIENT_LABELS)

        // بدون مبلغ موثوق (مرتبط بعملة)، لا نُرجع بيانات
        return ParsedFields(
            senderName = sender,
            recipientName = recipient,
            amount = amount,
            date = date
        )
    }
}
