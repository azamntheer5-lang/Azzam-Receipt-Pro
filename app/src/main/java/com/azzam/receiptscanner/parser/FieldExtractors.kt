package com.azzam.receiptscanner.parser

/**
 * أدوات مشتركة لاستخراج الحقول من نص OCR الخام.
 *
 * صُمّمت لتُستخدم من قبل جميع المحللات (parsers) لضمان اتساق منطق
 * الاستخراج وتقليل التكرار (مبدأ DRY). تدعم صيغاً عربية وإنجليزية شائعة
 * في إيصالات التحويلات البنكية، وتتعامل بأمان مع النصوص الناقصة التي
 * يُرجعها ML Kit (الذي لا يقرأ العربية) عبر محاولة مطابقة التسميات
 * ثنائية اللغة إن وُجدت في الإيصال.
 *
 * ملاحظة أكاديمية: هذه الأنماط heuristic (ارتياضية) ومبنيّة على الصيغ
 * الشائعة، لأن ML Kit يُرجع نصاً لاتينياً غالباً. الطبقة السحابية
 * (CloudExtractor/Claude) تبقى المصدر الأوثق للعربية؛ الهدف هنا هو رفع
 * نسبة الاستخراج المحلي لتقليل الاعتماد على السحابة (توفير تكلفة).
 */
object FieldExtractors {

    // ---------------- المبلغ ----------------

    /**
     * يستخرج المبلغ الرقمي من النص. يجرّب عدة أنماط بترتيب الأوثق:
     *  1) "Amount: 1,234.56" / "المبلغ: 1234.56"
     *  2) "SAR 1,234.56" / "1,234.56 SAR" / "1,234.56 ر.س"
     *  3) أي رقم بصيغة x.yy (سنتان إلزاميتان) كحل أخير موثوق للإيصالات
     *
     * يعيد أول مبلغ صالح > 0، أو null إن لم يعثر على شيء.
     */
    fun extractAmount(text: String): Double? {
        val patterns = listOf(
            Regex("""(?:Amount|المبلغ)\s*[:：]?\s*([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE),
            Regex("""(?:SAR|ر\.س|ريال)\s*([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE),
            Regex("""([\d,]+\.?\d*)\s*(?:SAR|ر\.س|ريال)""", RegexOption.IGNORE_CASE),
            Regex("""([\d,]+\.\d{2})""")
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val cleaned = match.groupValues[1].replace(",", "")
            val value = cleaned.toDoubleOrNull()
            if (value != null && value > 0.0) return value
        }
        return null
    }

    // ---------------- التاريخ ----------------

    /**
     * يستخرج التاريخ ويعيده بصيغة موحّدة yyyy-MM-dd إن أمكن، وإلا يعيد
     * النص الخام المطابق. يدعم:
     *  - dd/mm/yyyy , dd-mm-yyyy (الصيغة المحلية الأكثر شيوعاً)
     *  - yyyy-mm-dd (ISO)
     *  - dd/mm/yy (سنتان فقط)
     */
    fun extractDate(text: String): String? {
        val dateRegex = Regex("""(\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\d{4}[/-]\d{1,2}[/-]\d{1,2})""")
        val raw = dateRegex.find(text)?.value ?: return null
        return normalizeDate(raw)
    }

    /** يحوّل "12/5/2024" → "2024-05-12" و"2024-05-12" يبقى كما هو. */
    fun normalizeDate(raw: String): String {
        return try {
            val parts = raw.split("/", "-").map { it.trim() }
            when {
                // yyyy-mm-dd
                parts[0].length == 4 -> "%s-%02d-%02d".format(parts[0], parts[1].toInt(), parts[2].toInt())
                // dd-mm-yyyy (نفترض dd/mm/yyyy للإيصالات المحلية)
                parts[2].length == 4 -> "%s-%02d-%02d".format(parts[2], parts[1].toInt(), parts[0].toInt())
                // سنتان فقط في السنة - نكمل بـ20
                else -> "20" + parts[2] + "-%02d-%02d".format(parts[1].toInt(), parts[0].toInt())
            }
        } catch (e: Exception) {
            raw // أعِد الخام إن فشل التوحيد (لا نكسر السلسلة)
        }
    }

    // ---------------- الأسماء ----------------

    /**
     * يستخرج اسماً بناءً على قائمة تسميات (labels) محتملة.
     * يبحث عن "Label: value" أو "Label value" (مع/without نقطتين رفيع/عريض)،
     * ويعيد value مقصوصاً حتى نهاية السطر أو فاصل، بحد أقصى 40 حرفاً.
     *
     * يفلتر القيم التي تبدو أرقاماً/رموزاً (ليست أسماء حقيقية) وينظّف
     * لاحقات العملة الملحقة بالخطأ.
     */
    fun extractNameByLabels(text: String, labels: List<String>): String? {
        for (label in labels) {
            val escaped = Regex.escape(label)
            val pattern = Regex("""(?:$escaped)\s*[:：]?\s*([^\n\r|]{2,40})""", RegexOption.IGNORE_CASE)
            val match = pattern.find(text) ?: continue
            val name = match.groupValues[1]
                .replace(Regex("""\s*(SAR|ر\.س|ريال)\s*$""", RegexOption.IGNORE_CASE), "")
                .trim()
            // تجاهل القيم التي تبدو أرقاماً أو رموزاً لا أسماء
            if (name.isNotBlank() && !name.matches(Regex("""[\d\s.,:/\\-]+"""))) {
                return name
            }
        }
        return null
    }

    // ---------------- تسميات شائعة ----------------

    /** تسميات حقل المرسل بالعربية والإنجليزية، مرتّبة من الأخص للأعم. */
    val SENDER_LABELS: List<String> = listOf(
        "Sender", "From", "Sent by",
        "المرسل", "المُرسِل", "اسم المرسل", "من"
    )

    /** تسميات حقل المستلم بالعربية والإنجليزية، مرتّبة من الأخص للأعم. */
    val RECIPIENT_LABELS: List<String> = listOf(
        "Beneficiary", "Recipient", "To", "Sent to",
        "المستفيد", "المستلم", "اسم المستفيد", "إلى", "الى"
    )
}
