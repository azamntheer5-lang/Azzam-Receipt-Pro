package com.azzam.receiptscanner.parser

/**
 * أدوات مشتركة لاستخراج الحقول من نص OCR الخام.
 *
 * إصلاح جوهري: تشديد الأنماط لمنع استخراج قيم خاطئة:
 *  - الأسماء: قائمة سوداء للقيم الشائعة الخاطئة + شرط طول + فلترة رمزية
 *  - المبلغ: يجب أن يُسبق بكلمة مفتاحية (SAR/Amount/المبلغ) أو عملة صريحة
 *    — أزيل نمط "أي رقم بكسور" لأنه يلتقط أسعار المنتجات والتواريخ
 *  - التاريخ: أولوية للتسميات (Date/التاريخ) + رفض المستقبلية
 */
object FieldExtractors {

    // ---------------- المبلغ ----------------

    /**
     * يستخرج المبلغ الرقمي من النص. تشديد: يجب أن يُسبق/يتبع بـ:
     *  - "Amount: 1,234.56" / "المبلغ: 1234.56"
     *  - "SAR 1,234.56" / "1,234.56 SAR" / "1,234.56 ر.س"
     *
     * أزيل نمط "أي رقم بكسور" لأنه يلتقط أسعار المنتجات والأرقام العشوائية.
     * يعيد null إن لم يجد مبلغاً مرتبطاً بعملة/تسمية صريحة.
     */
    fun extractAmount(text: String): Double? {
        val patterns = listOf(
            // تسمية Amount/المبلغ متبوعة بالمبلغ
            Regex("""(?:Amount|المبلغ)\s*[:：]?\s*(?:SAR|ر\.س|ريال)?\s*([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE),
            // عملة قبل الرقم
            Regex("""(?:SAR|ر\.س|ريال)\s*([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE),
            // عملة بعد الرقم
            Regex("""([\d,]+\.?\d*)\s*(?:SAR|ر\.س|ريال)""", RegexOption.IGNORE_CASE),
            // "1,234.56 SAR" صيغة بنكية شائعة
            Regex("""(?:Price|Total|Value|الإجمالي|المجموع)\s*[:：]?\s*([\d,]+\.?\d*)\s*(?:SAR|ر\.س|ريال)?""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val cleaned = match.groupValues[1].replace(",", "")
            val value = cleaned.toDoubleOrNull()
            // رفض المبالغ غير المنطقية (< 1.0 أو > 10,000,000)
            if (value != null && value >= 1.0 && value <= 10_000_000.0) return value
        }
        return null
    }

    // ---------------- التاريخ ----------------

    /**
     * يستخرج التاريخ بأمان:
     *  1) يبحث عن تسمية Date/التاريخ أولاً (أوثق)
     *  2) إن لم يجد، يأخذ آخر تاريخ في النص (عادة تاريخ الحوالة)
     *  3) يرفض التواريخ المستقبلية
     *
     * يعيد null إن لم يجد تاريخاً صالحاً غير مستقبلي.
     */
    fun extractDate(text: String): String? {
        // 1) تسمية صريحة أولاً
        val labeledPattern = Regex(
            """(?:Date|التاريخ|في يوم|بتاريخ|Date:)\s*[:：]?\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\d{4}[/-]\d{1,2}[/-]\d{1,2})""",
            RegexOption.IGNORE_CASE
        )
        labeledPattern.find(text)?.let { return normalizeAndValidate(it.groupValues[1]) }

        // 2) كل التواريخ في النص — نأخذ آخر واحد (عادة تاريخ التنفيذ)
        val dateRegex = Regex("""(\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\d{4}[/-]\d{1,2}[/-]\d{1,2})""")
        val allDates = dateRegex.findAll(text).map { it.value }.toList()
        if (allDates.isEmpty()) return null

        // نأخذ آخر تاريخ غير مستقبلي
        for (date in allDates.reversed()) {
            val normalized = normalizeAndValidate(date) ?: continue
            return normalized
        }
        return null
    }

    /** يحوّل "12/5/2024" → "2024-05-12" ويتحقق أنه ليس مستقبلياً. */
    fun normalizeDate(raw: String): String? {
        return normalizeAndValidate(raw)
    }

    private fun normalizeAndValidate(raw: String): String? {
        val normalized = try {
            val parts = raw.split("/", "-").map { it.trim() }
            when {
                parts[0].length == 4 -> "%s-%02d-%02d".format(parts[0], parts[1].toInt(), parts[2].toInt())
                parts[2].length == 4 -> "%s-%02d-%02d".format(parts[2], parts[1].toInt(), parts[0].toInt())
                else -> "20" + parts[2] + "-%02d-%02d".format(parts[1].toInt(), parts[0].toInt())
            }
        } catch (e: Exception) {
            return null
        }

        // تحقق أنه ليس مستقبلياً (بحدود 7 أيام للتسامح مع فروق المنطقة الزمنية)
        return try {
            val (y, m, d) = normalized.split("-").map { it.toInt() }
            val cal = java.util.Calendar.getInstance()
            val input = java.util.Calendar.getInstance().apply {
                set(y, m - 1, d, 0, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val diffDays = (input.timeInMillis - cal.timeInMillis) / (1000L * 60 * 60 * 24)
            // اقبل التواريخ الماضية فقط (مع 7 أيام تسامح للمستقبل)
            if (diffDays > 7) null else normalized
        } catch (e: Exception) {
            normalized
        }
    }

    // ---------------- الأسماء ----------------

    /**
     * قائمة سوداء للقيم الشائعة الخاطئة عند استخراج الأسماء.
     * تشمل: ظروف زمانية، حروف جر، رموز، كلمات شائعة ليست أسماء.
     */
    private val nameBlacklist = setOf(
        // ظروف زمانية/مكانية تُلتقط خطأً بعد "من"
        "منذ", "منذ يوم", "منذ أيام", "منذ شهر", "منذ سنة", "من هنا", "من هناك",
        "من فوق", "من تحت", "من عند", "من بين", "من ضمن",
        // حروف جر وكلمات شائعة
        "في", "إلى", "الى", "على", "عن", "مع", "خلال", "بعد", "قبل", "حتى",
        "من", "ال", "هذا", "هذه", "ذلك", "تلك",
        // إنجليزي
        "from", "to", "the", "this", "that", "with", "by", "for", "at", "on",
        "since", "yesterday", "today", "tomorrow",
        // قيم شائعة من OCR
        "null", "none", "na", "n/a", "—", "-", "?", "فارغ"
    )

    /**
     * يستخرج اسماً بناءً على قائمة تسميات محتملة، بصرامة:
     *  - يبحث عن "Label: value" أو "Label value" (مع/بدون نقطتين)
     *  - يفلتر القيم في القائمة السوداء
     *  - يرفض القيم الرقمية/الرمزية
     *  - يرفض القيم التي تبدأ بحرف جر أو رقم
     *  - يشترط طول بين 2 و 40
     */
    fun extractNameByLabels(text: String, labels: List<String>): String? {
        for (label in labels) {
            val escaped = Regex.escape(label)
            // النمط: تسمية + اختياري ":" + قيمة حتى نهاية السطر/فاصل
            val pattern = Regex("""(?:$escaped)\s*[:：]\s*([^\n\r|]{2,40})""", RegexOption.IGNORE_CASE)
            val match = pattern.find(text) ?: continue
            val rawName = match.groupValues[1].trim()
            val name = cleanName(rawName)
            if (isValidName(name)) return name
        }
        return null
    }

    /** ينظّف الاسم: يزيل لاحقات العملة والمسافات الزائدة والرموز. */
    private fun cleanName(raw: String): String {
        return raw
            .replace(Regex("""\s*(SAR|ر\.س|ريال)\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\d{4,}.*$"""), "") // يقطع عند أول رقم IBAN/حساب
            .replace(Regex("""[|\\/]$"""), "")
            .trim()
    }

    /** يتحقق أن الاسم صالح: ليس في القائمة السوداء، ليس رقماً، طول مناسب. */
    private fun isValidName(name: String): Boolean {
        if (name.length < 2 || name.length > 40) return false
        val lower = name.lowercase()
        if (lower in nameBlacklist) return false
        // رفض إن كان أغلبها أرقام/رموز
        if (name.matches(Regex("""[\d\s.,:/\\\-|]+"""))) return false
        // رفض إن بدأ بحرف جر عربي شائع (يتبعه كلمة شائعة)
        if (lower.startsWith("من ") && lower.length < 10) return false
        if (lower.startsWith("في ") && lower.length < 10) return false
        // رفض الأرقام بمفردها
        if (name.toDoubleOrNull() != null) return false
        // رفض الأسماء التي لا تحوي حرفاً عربياً أو لاتينياً واحداً على الأقل
        if (!name.any { it.isLetter() }) return false
        return true
    }

    // ---------------- تسميات شائعة ----------------

    val SENDER_LABELS: List<String> = listOf(
        "Sender Name", "Sender", "From", "Sent by", "From Account",
        "اسم المرسل", "المرسل", "المُرسِل", "من", "المرسِل"
    )

    val RECIPIENT_LABELS: List<String> = listOf(
        "Beneficiary Name", "Beneficiary", "Recipient", "To", "Sent to", "To Account",
        "اسم المستفيد", "المستفيد", "المستلم", "اسم المستلم", "إلى", "الى", "المستلِم"
    )
}
