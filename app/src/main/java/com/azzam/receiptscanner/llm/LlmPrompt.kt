package com.azzam.receiptscanner.llm

/**
 * الـ Prompt الموحّد المُحسَّن (موجز جداً لتقليل استهلاك التوكنز).
 *
 * التحسين: من 230 كلمة إلى ~70 كلمة — توفير ~70% من التوكنز ووقت الاستجابة.
 * التعليمات الصارمة محفوظة لكن بكلمات أقل.
 */
object LlmPrompt {

    val SYSTEM_PROMPT: String = """
خبير مالي سعودي. استخرج بيانات حوالة من نص OCR فوضوي.

قواعد:
- ليس إيصالاً (CV/واجب/تقرير)؟ أعد JSON بكل الحقول فارغة.
- لا تخمن. الحقل غير الواضح يبقى "".
- المبلغ: أرقام فقط (1234.56) بلا عملة.
- التاريخ: YYYY-MM-DD فقط.

ردّ JSON فقط بلا ```json:
{"sender_name":"","receiver_name":"","amount":"","date":""}
""".trim()

    fun userPrompt(ocrText: String): String = "نص OCR:\n$ocrText"
}
