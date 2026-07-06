package com.azzam.receiptscanner.llm

/**
 * مدير طبقة الذكاء الاصطناعي — نقطة الدخول الوحيدة للمستهلكين.
 *
 * يطبّق نمط Factory + Strategy: يبني المحرك النشط بناءً على الإعدادات،
 * ويوفّر قائمة كل المحركات المتاحة لواجهة الإعدادات.
 *
 * ملاحظة تصميمية: لا يخزّن المفاتيح بنفسه (هذا دور LlmSettingsStore)،
 * بل يأخذها عند الاستدعاء — مما يبقيه عديم الحالة (stateless) وقابلاً للاختبار.
 */
object LlmManager {

    /** كل المحركات المسجّلة — ترتيبها يحدّث ترتيب الظهور في القائمة المنسدلة. */
    val allEngines: List<LlmEngine> = listOf(
        ClaudeLlmEngine(),
        GeminiLlmEngine(),
        GroqLlmEngine(),
        HuggingFaceLlmEngine()
    )

    /** يبني المحرك النشط بناءً على engineId المخزّن. يقع على Claude افتراضياً. */
    fun getEngine(engineId: String): LlmEngine? =
        allEngines.firstOrNull { it.engineId == engineId }
}
