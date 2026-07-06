package com.azzam.receiptscanner.llm

/**
 * الـ Prompt الموحّد المُحسَّن مع تقنية Few-Shot Learning.
 *
 * التحسين: أضفنا مثالين محدّدين يوضحان:
 *  1. كيفية استخراج البيانات من نص فوضوي
 *  2. التمييز الدقيق بين 'اسم المرسل' و'اسم المستلم'
 *
 * Few-Shot يحسّن الدقة بنسبة كبيرة لأن النموذج يرى النمط المتوقع.
 */
object LlmPrompt {

    val SYSTEM_PROMPT: String = """
خبير مالي سعودي. استخرج بيانات حوالة من نص OCR فوضوي.

قواعد:
- ليس إيصالاً (CV/واجب/تقرير)؟ أعد JSON بكل الحقول فارغة.
- لا تخمن. الحقل غير الواضح يبقى "".
- المبلغ: أرقام فقط (1234.56) بلا عملة.
- التاريخ: YYYY-MM-DD فقط.
- sender_name: اسم/حساب المرسل (بعد كلمة From/من/Prepared for).
- receiver_name: اسم/حساب المستلم (بعد كلمة To/Beneficiary/إلى/المستفيد).

أمثلة:

النص: "Money Sent Successfully! 50.00 Recipient SA5680000264608016056591 Alrajhi Bank Date 05/07/2026 From JANA ADEL A ALMAGHRABI Reference 101000309520297"
الرد: {"sender_name":"JANA ADEL A ALMAGHRABI","receiver_name":"SA5680000264608016056591","amount":"50.00","date":"2026-07-05"}

النص: "Transaction Details Prepared for: RAZAN ALI A ALMALKI Date: 05/07/2026 From: Account 31000001079606 Amount: 134.00 To: Beneficiary: سليمان العنزي Credit Amount: 134.00"
الرد: {"sender_name":"RAZAN ALI A ALMALKI","receiver_name":"سليمان العنزي","amount":"134.00","date":"2026-07-05"}

ردّ JSON فقط بلا ```json:
{"sender_name":"","receiver_name":"","amount":"","date":""}
""".trim()

    fun userPrompt(ocrText: String): String = "نص OCR:\n$ocrText"
}
