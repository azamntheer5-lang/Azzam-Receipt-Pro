package com.azzam.receiptscanner.llm

/**
 * الـ Prompt الموحّد المُحسَّن مع تقنيات متقدمة:
 *  1. Few-Shot Learning (مثالان محدّدان)
 *  2. Hints-aware: يقبل مستخرَجات Regex المؤكّدة كقرائن
 *  3. FALLBACK_PROMPT: تعليمات أصرم للمرحلة الثانية عند فشل الأولى
 *
 * الهدف: منع الهلوسة وتقليل الأخطاء لدرجة الصفر.
 */
object LlmPrompt {

    /**
     * Prompt المرحلة الأولى — مع hints اختيارية من Regex.
     * @param hints مستخرَجات مؤكّدة (المبلغ/التاريخ/IBAN) لتوجيه النموذج
     */
    fun systemPromptWithHints(hints: ExtractionHints? = null): String {
        val hintsBlock = if (hints != null) {
            buildString {
                append("\n\nقرائن مؤكّدة من Regex (استخدمها كحقائق، لا تخمّلها):\n")
                hints.amount?.let { append("- المبلغ المؤكّد: $it\n") }
                hints.date?.let { append("- التاريخ المؤكّد: $it\n") }
                hints.iban?.let { append("- IBAN/حساب: $it\n") }
                append("\nركّز فقط على استخراج: اسم المرسل، اسم المستلم، وتأكيد البنك.\n")
            }
        } else ""
        return SYSTEM_PROMPT + hintsBlock
    }

    /**
     * Prompt للـ Vision API — يستقبل صورة مباشرة بدون OCR محلي.
     * Claude/Gemini يقرآن العربية من الصورة بدقة عالية.
     */
    val VISION_PROMPT: String = """
خبير مالي سعودي. استخرج بيانات الحوالة من صورة الإيصال.

قواعد:
- ليس إيصال حوالة؟ أعد JSON بكل الحقول فارغة.
- لا تخمن. الحقل غير الواضح يبقى "".
- المبلغ: أرقام فقط (1234.56) بلا عملة.
- التاريخ: YYYY-MM-DD فقط.
- sender_name: اسم/حساب المرسل (بعد From/من/Prepared for).
- receiver_name: اسم/حساب المستلم (بعد To/Beneficiary/إلى/المستفيد).

أمثلة:

الإيصال: صورة حوالة D360 بها "Money Sent Successfully! 50.00" و"From: JANA ADEL"
الرد: {"sender_name":"JANA ADEL A ALMAGHRABI","receiver_name":"مريم محمد علي","amount":"50.00","date":"2026-07-05"}

الإيصال: صورة حوالة SNB بها "Prepared for: RAZAN ALI" و"Beneficiary: سليمان العنزي"
الرد: {"sender_name":"RAZAN ALI A ALMALKI","receiver_name":"سليمان العنزي","amount":"134.00","date":"2026-07-05"}

ردّ JSON فقط بلا ```json:
{"sender_name":"","receiver_name":"","amount":"","date":""}
""".trim()

    val SYSTEM_PROMPT: String = """
خبير مالي سعودي. استخرج بيانات حوالة من نص OCR فوضوي.

قواعد:
- ليس إيصالاً (CV/واجب/تقرير)؟ أعد JSON بكل الحقول فارغة.
- لا تخمن. الحقل غير الواضح يبقى "".
- المبلغ: أرقام فقط (1234.56) بلا عملة.
- التاريخ: YYYY-MM-DD فقط.
- sender_name: اسم/حساب المرسل (بعد From/من/Prepared for).
- receiver_name: اسم/حساب المستلم (بعد To/Beneficiary/إلى/المستفيد).

أمثلة:

النص: "Money Sent Successfully! 50.00 Recipient SA5680000264608016056591 Alrajhi Bank Date 05/07/2026 From JANA ADEL A ALMAGHRABI Reference 101000309520297"
الرد: {"sender_name":"JANA ADEL A ALMAGHRABI","receiver_name":"SA5680000264608016056591","amount":"50.00","date":"2026-07-05"}

النص: "Transaction Details Prepared for: RAZAN ALI A ALMALKI Date: 05/07/2026 From: Account 31000001079606 Amount: 134.00 To: Beneficiary: سليمان العنزي Credit Amount: 134.00"
الرد: {"sender_name":"RAZAN ALI A ALMALKI","receiver_name":"سليمان العنزي","amount":"134.00","date":"2026-07-05"}

ردّ JSON فقط بلا ```json:
{"sender_name":"","receiver_name":"","amount":"","date":""}
""".trim()

    /**
     * Prompt بديل للمرحلة الثانية (Fallback) — تعليمات أصرم لمنع الهلوسة.
     * يُستخدم عند فشل المرحلة الأولى أو إرجاع JSON فارغ.
     */
    val FALLBACK_PROMPT: String = """
خبير مالي. مهامك صارمة جداً — لا تخمّل أبداً.

إن لم تجد حوالة بنكية واضحة، أعد JSON فارغاً فوراً.

قواعد استخراج الإيصال:
- sender_name: ابحث عن "From/من/Prepared for" والقيمة بعدها مباشرة.
- receiver_name: ابحث عن "To/Beneficiary/إلى/المستفيد" والقيمة بعدها.
- amount: الرقم بجوار SAR/ر.س/Amount. بلا عملة.
- date: YYYY-MM-DD فقط.

ممنوع:
- إرجاع IBAN كاسم (استخدمه كاسم فقط إن لم يوجد اسم نصّي)
- تخمين تاريخ غير موجود في النص
- تخمين مبلغ غير موجود

ردّ JSON فقط:
{"sender_name":"","receiver_name":"","amount":"","date":""}
""".trim()

    fun userPrompt(ocrText: String): String = "نص OCR:\n$ocrText"
}

/** مستخرَجات Regex المؤكّدة لتضمينها كـ hints في الـ Prompt. */
data class ExtractionHints(
    val amount: Double? = null,
    val date: String? = null,
    val iban: String? = null
)
