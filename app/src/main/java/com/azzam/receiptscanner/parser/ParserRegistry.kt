package com.azzam.receiptscanner.parser

class ParserRegistry {
    private val parsers: List<BankReceiptParser> = listOf(
        RajhiParser(),
        StcPayParser()
        // أضف بنكاً جديداً هنا، مثلاً: AlAhliParser()
    )
    private val fallback = GenericParser()

    fun extract(ocrText: String): Pair<String, ParsedFields>? {
        val matched = parsers.firstOrNull { it.matches(ocrText) }
        if (matched != null) {
            matched.parse(ocrText)?.let { return matched.bankId to it }
        }
        // لم يُتعرّف على بنك محدد، أو تعرّف عليه لكن فشل استخراج حقوله - جرّب المحلل العام
        return fallback.parse(ocrText)?.let { fallback.bankId to it }
    }
}
