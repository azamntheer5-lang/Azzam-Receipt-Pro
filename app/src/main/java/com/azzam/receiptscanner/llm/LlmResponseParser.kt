package com.azzam.receiptscanner.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * محلّل JSON موحّد يأخذ استجابة محرك الذكاء الاصطناعي الخام ويحوّلها إلى
 * [LlmExtractionResult].
 *
 * يحل مشكلة شائعة: النماذج قد تُحاط الـ JSON بعلامات ```json ... ``` أو
 * نص إضافي رغم الـ System Prompt الصارم. هذا المحلّل يستخرج أول كائن JSON
 * صالح من النص بأمان.
 *
 * ملاحظة عن أسماء الحقول: يتعامل مع تسميتين لاسم المستلم (receiver_name
 * وrecipient_name) لأن بعض المحركات قد تستخدم إحداهما. كما ينظّف المبلغ
 * من أي فواصل آلاف أو عملات متسللة.
 */
object LlmResponseParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * يستخرج أول كائن JSON صالح من النص الخام ويُفسّره.
     * يعيد null إن لم يعثر على JSON صالح.
     */
    fun parse(rawResponse: String, engineId: String): LlmExtractionResult? {
        val jsonText = extractJsonBlock(rawResponse) ?: return null
        return try {
            val obj = json.parseToJsonElement(jsonText) as? JsonObject ?: return null
            val sender = obj.stringField("sender_name")
            val receiver = obj.stringField("receiver_name")
                ?: obj.stringField("recipient_name") // توافق مع تسميات سابقة
            val amount = obj.doubleField("amount")
            val date = obj.stringField("date")
            if (sender == null && receiver == null && amount == null && date == null) return null
            LlmExtractionResult(
                senderName = sender,
                receiverName = receiver,
                amount = amount,
                date = date,
                engineId = engineId
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * يقطع أول كائن JSON متوازن الأقواس { ... } من النص الخام، متجاهلاً
     * أي علامات markdown أو نص قبل/بعد. يعالج أيضاً الأقواس المتداخلة.
     */
    private fun extractJsonBlock(text: String): String? {
        val cleaned = text
            .replace("```json", "")
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until cleaned.length) {
            val c = cleaned[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return cleaned.substring(start, i + 1)
                }
            }
        }
        return null // أقواس غير متوازنة
    }

    private fun JsonObject.stringField(key: String): String? {
        val prim = this[key] as? JsonPrimitive ?: return null
        if (!prim.isString) return prim.contentOrNull
        val content = prim.contentOrNull ?: return null
        return content.trim().takeIf { it.isNotBlank() && it != "فارغ" && it != "null" }
    }

    private fun JsonObject.doubleField(key: String): Double? {
        val prim = this[key] as? JsonPrimitive ?: return null
        // contentOrNull يعطي النص الحرفي سواء كان المبدأ string أم number
        val raw = prim.contentOrNull
        return raw
            ?.replace(",", "")
            ?.replace(Regex("""[^\d.\-]"""), "")
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
    }
}
