package com.azzam.receiptscanner.parser

/**
 * مطابقة وتصحيح أسماء البنوك السعودية (Fuzzy Matching).
 *
 * يستخدم خوارزمية Levenshtein distance لتصحيح أخطاء الـ OCR الإملائية في
 * أسماء البنوك تلقائياً. مثلاً: "Alrajhi" → "al_rajhi"، "STCPay" → "stc_pay".
 *
 * القائمة تشمل البنوك والمحافظ السعودية المعتمدة.
 */
object BankMatcher {

    /** خريطة المرادفات: كل قيمة تُطابَق fuzzy لاسم البنك المعياري. */
    private data class BankAlias(
        val canonicalId: String,
        val displayName: String,
        val aliases: List<String>
    )

    private val banks = listOf(
        BankAlias("al_rajhi", "الراجحي", listOf(
            "rajhi", "alrajhi", "al rajhi", "الراجحي", "مصرف الراجحي", "bank alrajhi"
        )),
        BankAlias("al_ahli", "الأهلي السعودي (NCB)", listOf(
            "ncb", "alahli", "al ahli", "الاهلي", "الأهلي", "snb", "saudi national bank",
            "national commercial bank", "السعودي الوطني", "البنك الأهلي", "al rajhi bank beneficiary المستفيد من البنك الأهلي"
        )),
        BankAlias("alinma", "الإنماء", listOf(
            "alinma", "al inma", "al-inma", "الانماء", "الإنماء", "inma", "بنك الإنماء"
        )),
        BankAlias("stc_pay", "STC Pay", listOf(
            "stc pay", "stcpay", "stc-pay", "stc", "إس تي سي باي", "اس تي سي"
        )),
        BankAlias("urpay", "Urpay", listOf(
            "urpay", "ur pay", "ur-pay", "اور باي", "أور باي"
        )),
        BankAlias("al_rajhi_d360", "D360 (الراجحي)", listOf(
            "d360", "d 360", "d-360"
        )),
        BankAlias("albilad", "الببلاد", listOf(
            "albilad", "al bilad", "al-bilad", "الببلاد", "بنك البلاد", "bank albilad"
        )),
        BankAlias("sabb", "SABB", listOf(
            "sabb", "saab", "البنك السعودي البريطاني", "saudi british bank"
        )),
        BankAlias("riyad_bank", "بنك الرياض", listOf(
            "riyad bank", "riyadbank", "bank riyad", "بنك الرياض", "الرياض"
        )),
        BankAlias("bank_aljazira", "بنك الجزيرة", listOf(
            "bank aljazira", "aljazira", "al jazira", "بنك الجزيرة", "الجزيرة"
        )),
        BankAlias("safb", "البنك السعودي الفرنسي", listOf(
            "safb", "saudi french bank", "الفرنسي", "البنك السعودي الفرنسي"
        )),
        BankAlias("mada", "مدى", listOf(
            "mada", "مدى", "mada pay"
        ))
    )

    /** حد التشابه المقبول (0-1). فوقه يُعتبر تطابقاً. */
    private const val SIMILARITY_THRESHOLD = 0.75f

    /**
     * يطابق نصاً (ربما فيه أخطاء OCR) بأقرب بنك معتمد.
     * يعيد (canonicalId, displayName) أو null إن لم يطابق.
     */
    fun match(input: String?): Pair<String, String>? {
        if (input.isNullOrBlank()) return null
        val normalized = normalize(input)

        // 1. تطابق تام (بعد تطبيع النص)
        for (bank in banks) {
            for (alias in bank.aliases) {
                if (normalized == normalize(alias)) {
                    return bank.canonicalId to bank.displayName
                }
            }
        }

        // 2. تطابق يحتوي (contains) — للنصوص الطويلة
        for (bank in banks) {
            for (alias in bank.aliases) {
                val normAlias = normalize(alias)
                if (normalized.contains(normAlias) || normAlias.contains(normalized)) {
                    // تأكد من طول معقول لتجنب false positives
                    if (normalized.length >= 3 && normAlias.length >= 3) {
                        return bank.canonicalId to bank.displayName
                    }
                }
            }
        }

        // 3. Fuzzy matching (Levenshtein)
        var bestMatch: Pair<String, String>? = null
        var bestSimilarity = 0f

        for (bank in banks) {
            for (alias in bank.aliases) {
                val sim = similarity(normalized, normalize(alias))
                if (sim > bestSimilarity) {
                    bestSimilarity = sim
                    bestMatch = bank.canonicalId to bank.displayName
                }
            }
        }

        return if (bestSimilarity >= SIMILARITY_THRESHOLD) bestMatch else null
    }

    /** يطابق الـ bankId الخام ويعيده للمعياري إن أمكن. */
    fun normalizeBankId(rawBankId: String?): String {
        val matched = match(rawBankId) ?: return rawBankId ?: "unknown"
        return matched.first
    }

    /** يطابق ويعيد الاسم الجميل للعرض. */
    fun beautifyBankName(bankId: String?): String {
        val canonical = if (bankId.isNullOrEmpty()) "unknown" else bankId
        // ابحث عن البنك بالـ canonicalId
        for (bank in banks) {
            if (bank.canonicalId == canonical) return bank.displayName
        }
        // ابحث fuzzy في الـ bankId نفسه
        match(canonical)?.let { return it.second }
        return when (canonical.lowercase()) {
            "cloud_ai" -> "Claude AI"
            "llm_claude" -> "Claude AI"
            "llm_gemini" -> "Gemini AI"
            "llm_groq" -> "Groq AI"
            "llm_huggingface" -> "HuggingFace"
            "generic" -> "عام"
            "unknown" -> "غير مصنّف"
            else -> canonical
        }
    }

    // ===== أدوات مساعدة =====

    /** تطبيع النص: أحرف صغيرة + إزالة التشكيل + توحيد الألف. */
    private fun normalize(s: String): String {
        return s.lowercase()
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
            .replace("ة", "ه")
            .replace("ـ", "")
            .replace(Regex("[^a-z0-9\\u0600-\\u06FF\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * حساب التشابه (0-1) بين سلسلتين عبر Levenshtein distance.
     * 1.0 = متطابقتان، 0.0 = مختلفتان تماماً.
     */
    private fun similarity(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val maxLen = maxOf(a.length, b.length)
        val dist = levenshtein(a, b)
        return 1f - dist.toFloat() / maxLen
    }

    /** Levenshtein distance — عدد العمليات (insert/delete/substitute) للوصول من a إلى b. */
    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m

        // استخدم صفّين لتوفير الذاكرة
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,        // insert
                    prev[j] + 1,            // delete
                    prev[j - 1] + cost      // substitute
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[n]
    }
}
