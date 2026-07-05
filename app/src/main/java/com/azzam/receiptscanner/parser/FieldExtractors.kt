package com.azzam.receiptscanner.parser

/**
 * أدوات مشتركة لاستخراج الحقول من نص OCR الخام.
 *
 * إصلاح شامل بناءً على تحليل 3 نماذج حقيقية:
 *  - النموذج 1 (D360): المبلغ بلا عملة بعد "Successfully"، المستلم عربي
 *    (ML Kit لا يقرأه) + IBAN يُلتقط خطأً كاسم
 *  - النموذج 2 (الراجحي بنك-بنك): IBAN مقنّع كمرسل، رقم حساب كمستلم
 *  - النموذج 3 (SNB): "Prepared for" بدل "From"، مبالغ متعددة (رسوم/ضريبة/إجمالي)
 *
 * المبدأ: الدقة على الاستخراج الخطأ — لا تُرجع قيمة أبداً بدلاً من قيمة خاطئة.
 */
object FieldExtractors {

    // ---------------- المبلغ ----------------

    /**
     * يستخرج المبلغ بأقصى دقة ممكنة. يجرّب الأنماط بالترتيب من الأوثق للأعم:
     *
     *  1) "Amount: 1,234.56" / "المبلغ: 1234" — تسمية صريحة (وليست "Total Amount")
     *  2) "2709 SAR" / "1,234.56 ر.س" — عملة صريحة
     *  3) "Money Sent Successfully!\n50.00" — مبلغ بعد رسالة نجاح
     *  4) "50.00 ﷼" — مع رمز الريال السعودي
     *  5) "Credit Amount: 134.00" — مبلغ القيد (في إيصالات SNB)
     *
     * يرفض: مبالغ < 1.0، > 10,000,000، والمبالغ المرتبطة بـ "Fees"/"VAT"/"Total".
     */
    fun extractAmount(text: String): Double? {
        // قائمة سوداء للسياقات التي يجب تجنبها (رسوم/ضريبة/إجمالي)
        val feeContexts = listOf("Fees", "الرسوم", "VAT", "ضريبة", "Total Amount", "المبلغ الإجمالي", "Total")

        // 1) تسمية "Amount" أو "المبلغ" (ليست "Total Amount")
        // نستخدم negative lookbehind لتجنب "Total Amount"
        val labeledPattern = Regex(
            """(?<!Total\s)(?<!المجموع\s)(?:Amount|المبلغ)\s*[:：]?\s*(?:SAR|ر\.س|ريال)?\s*([\d,]+\.?\d*)""",
            RegexOption.IGNORE_CASE
        )
        labeledPattern.find(text)?.let { match ->
            // تحقق أن السياق ليس "Fees" أو "VAT" قريباً
            if (!isInFeeContext(text, match.range.first, feeContexts)) {
                parseAmount(match.groupValues[1])?.let { return it }
            }
        }

        // 2) عملة صريحة قبل أو بعد الرقم
        val currencyPatterns = listOf(
            Regex("""(?:SAR|ر\.س|ريال)\s*([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE),
            Regex("""([\d,]+\.?\d*)\s*(?:SAR|ر\.س|ريال)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in currencyPatterns) {
            pattern.find(text)?.let { match ->
                if (!isInFeeContext(text, match.range.first, feeContexts)) {
                    parseAmount(match.groupValues[1])?.let { return it }
                }
            }
        }

        // 3) مبلغ بعد رسالة نجاح (D360: "Money Sent Successfully!\n50.00")
        val successPattern = Regex(
            """(?:Successfully|نجاح|نجحت|تم التحويل)\s*!?\s*[:：\n\r]*\s*([\d,]+\.?\d*)""",
            RegexOption.IGNORE_CASE
        )
        successPattern.find(text)?.let { match ->
            parseAmount(match.groupValues[1])?.let { return it }
        }

        // 4) مبلغ مع رمز الريال السعودي ﷼
        val riyalSymbolPattern = Regex("""([\d,]+\.?\d*)\s*﷼""")
        riyalSymbolPattern.find(text)?.let { match ->
            parseAmount(match.groupValues[1])?.let { return it }
        }

        // 5) "Credit Amount" / "المبلغ الدائن" (SNB)
        val creditPattern = Regex(
            """(?:Credit Amount|المبلغ الدائن|Value)\s*[:：]?\s*([\d,]+\.?\d*)""",
            RegexOption.IGNORE_CASE
        )
        creditPattern.find(text)?.let { match ->
            parseAmount(match.groupValues[1])?.let { return it }
        }

        // 6) تسمية "Price"/"Total"/"Value" مع عملة
        val totalWithCurrency = Regex(
            """(?:Price|Total|Value|الإجمالي|المجموع)\s*[:：]?\s*([\d,]+\.?\d*)\s*(?:SAR|ر\.س|ريال)""",
            RegexOption.IGNORE_CASE
        )
        totalWithCurrency.find(text)?.let { match ->
            parseAmount(match.groupValues[1])?.let { return it }
        }

        return null
    }

    /** يتحقق إن كان موقعه قريب من كلمة رسوم/ضريبة (خطر التخبط). */
    private fun isInFeeContext(text: String, pos: Int, feeContexts: List<String>): Boolean {
        val windowStart = (pos - 40).coerceAtLeast(0)
        val windowEnd = (pos + 40).coerceAtMost(text.length)
        val window = text.substring(windowStart, windowEnd).lowercase()
        return feeContexts.any { it.lowercase() in window }
    }

    private fun parseAmount(raw: String): Double? {
        val cleaned = raw.replace(",", "")
        val value = cleaned.toDoubleOrNull() ?: return null
        // رفض المبالغ غير المنطقية
        return if (value >= 1.0 && value <= 10_000_000.0) value else null
    }

    // ---------------- التاريخ ----------------

    /**
     * يستخرج التاريخ بأمان:
     *  1) تسمية Date/التاريخ أولاً (أوثق)
     *  2) آخر تاريخ في النص كحل أخير
     *  3) يرفض التواريخ المستقبلية (> 7 أيام)
     */
    fun extractDate(text: String): String? {
        // 1) تسمية صريحة
        val labeledPattern = Regex(
            """(?:Date|التاريخ|في يوم|بتاريخ|Date & Time|التاريخ والوقت)\s*[:：]?\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\d{4}[/-]\d{1,2}[/-]\d{1,2})""",
            RegexOption.IGNORE_CASE
        )
        labeledPattern.find(text)?.let { return normalizeAndValidate(it.groupValues[1]) }

        // 2) "Value Date" / "تاريخ القيمة" (SNB)
        val valueDatePattern = Regex(
            """(?:Value Date|تاريخ القيمة)\s*[:：]?\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\d{4}[/-]\d{1,2}[/-]\d{1,2})""",
            RegexOption.IGNORE_CASE
        )
        valueDatePattern.find(text)?.let { return normalizeAndValidate(it.groupValues[1]) }

        // 3) كل التواريخ — نأخذ آخر واحد (عادة تاريخ التنفيذ)
        val dateRegex = Regex("""(\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\d{4}[/-]\d{1,2}[/-]\d{1,2})""")
        val allDates = dateRegex.findAll(text).map { it.value }.toList()
        if (allDates.isEmpty()) return null

        for (date in allDates.reversed()) {
            val normalized = normalizeAndValidate(date) ?: continue
            return normalized
        }
        return null
    }

    fun normalizeDate(raw: String): String? = normalizeAndValidate(raw)

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

        return try {
            val (y, m, d) = normalized.split("-").map { it.toInt() }
            val cal = java.util.Calendar.getInstance()
            val input = java.util.Calendar.getInstance().apply {
                set(y, m - 1, d, 0, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val diffDays = (input.timeInMillis - cal.timeInMillis) / (1000L * 60 * 60 * 24)
            if (diffDays > 7) null else normalized
        } catch (e: Exception) {
            normalized
        }
    }

    // ---------------- الأسماء ----------------

    /**
     * قائمة سوداء للقيم الشائعة الخاطئة عند استخراج الأسماء.
     */
    private val nameBlacklist = setOf(
        "منذ", "منذ يوم", "منذ أيام", "منذ شهر", "منذ سنة", "من هنا", "من هناك",
        "من فوق", "من تحت", "من عند", "من بين", "من ضمن",
        "في", "إلى", "الى", "على", "عن", "مع", "خلال", "بعد", "قبل", "حتى",
        "من", "ال", "هذا", "هذه", "ذلك", "تلك",
        "from", "to", "the", "this", "that", "with", "by", "for", "at", "on",
        "since", "yesterday", "today", "tomorrow",
        "null", "none", "na", "n/a", "—", "-", "?", "فارغ",
        "تحويل مالي", "لحساب المستفيد من البنك",
        "المستفيد من البنك الأهلي", "المستفيد من البنك", "البنك الأهلي", "Alrajhi Bank", "Al Rajhi Bank"
    )

    /**
     * يستخرج اسماً بناءً على قائمة تسميات محتملة، بصرامة شديدة.
     *
     * إصلاحات جوهرية:
     *  - التسمية يجب أن تكون في بداية سطر (منع التطابق داخل جمل)
     *  - يرفض IBAN (SA + أرقام) كاسم
     *  - يرفض أرقام الحسابات (أغلبها أرقام)
     *  - يرفض IBAN المقنّع (SA** **** ****)
     *  - يرفض القيم في القائمة السوداء
     *  - يرفض الأسماء التي تحتوي "من البنك" (جملة وليست اسماً)
     *  - يشترط حرفاً عربياً أو لاتينياً واحداً على الأقل
     */
    fun extractNameByLabels(text: String, labels: List<String>): String? {
        for (label in labels) {
            val escaped = Regex.escape(label)
            // ★ التسمية في بداية سطر + حدود كلمة بعدها
            // \b يمنع "To" من مطابقة "Total"
            // (?:^|\n) يمنع "من" من مطابقة "المستفيد من البنك"
            val pattern = Regex(
                """(?:^|\n)\s*(?:$escaped)\b\s*[:：]?\s*([^\n\r|]{2,40})""",
                RegexOption.IGNORE_CASE
            )
            val matches = pattern.findAll(text)
            for (match in matches) {
                val rawName = match.groupValues[1].trim()
                val name = cleanName(rawName)
                if (isValidName(name)) return name
            }
        }
        return null
    }

    /** ينظّف الاسم: يزيل لاحقات العملة والمسافات الزائدة. */
    private fun cleanName(raw: String): String {
        return raw
            .replace(Regex("""\s*(SAR|ر\.س|ريال)\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * يتحقق أن الاسم صالح — الإصلاح الجوهري لمنع IBAN/أرقام الحساب.
     */
    private fun isValidName(name: String): Boolean {
        if (name.length < 2 || name.length > 40) return false
        val lower = name.lowercase()
        if (lower in nameBlacklist) return false

        // ★ رفض IBAN السعودي: "SA" + رقمين + أرقام/مسافات
        if (Regex("""^SA\d{2}[\d\s\*]+""", RegexOption.IGNORE_CASE).matches(name)) return false

        // ★ رفض IBAN مقنّع: "SA** **** **** **** **** 7862"
        if (Regex("""^SA\*+""", RegexOption.IGNORE_CASE).matches(name)) return false

        // ★ رفض النصوص بين أقواس [placeholder] (ليست أسماء حقيقية)
        if (name.startsWith("[") || name.startsWith("(")) return false

        // ★ رفض أرقام الحسابات (أغلبها أرقام، حتى لو بدأت بـ SA)
        val digitCount = name.count { it.isDigit() }
        val letterCount = name.count { it.isLetter() }
        if (digitCount > letterCount && digitCount > 4) return false

        // رفض الأرقام/الرموز فقط
        if (name.matches(Regex("""[\d\s.,:/\\\-|*]+"""))) return false

        // رفض إن بدأ بحرف جر عربي شائع
        if (lower.startsWith("من ") && lower.length < 10) return false
        if (lower.startsWith("في ") && lower.length < 10) return false

        // رفض الأرقام بمفردها
        if (name.toDoubleOrNull() != null) return false

        // يشترط حرفاً واحداً على الأقل
        if (letterCount == 0) return false

        return true
    }

    // ---------------- تسميات شائعة (موسّعة) ----------------

    val SENDER_LABELS: List<String> = listOf(
        // إنجليزي
        "Sender Name", "Sender", "From", "Sent by", "From Account", "Prepared for",
        // عربي
        "اسم المرسل", "المرسل", "المُرسِل", "المرسِل", "من", "أعدّ لـ", "أعدت لـ"
    )

    val RECIPIENT_LABELS: List<String> = listOf(
        // إنجليزي
        "Beneficiary Name", "Beneficiary", "Recipient", "Recipient Name", "To", "Sent to", "To Account",
        // عربي
        "اسم المستفيد", "المستفيد", "المستلم", "اسم المستلم", "إلى", "الى", "المستلِم"
    )
}
