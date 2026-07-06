package com.azzam.receiptscanner.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * محرك Anthropic Claude — مع آلية إعادة محاولة ذكية (Smart Retry).
 *
 * التحسين:
 *  - temperature=0.0 لضمان مخرجات حتمية (deterministic)
 *  - retry مرة واحدة تلقائياً عند فشل الاستخراج أو JSON فارغ
 *  - max_tokens مرفوع إلى 1024 لتجنب القطع
 */
class ClaudeLlmEngine : LlmEngine {

    override val engineId = "claude"
    override val displayName = "Claude (Anthropic)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun extract(ocrText: String, apiKey: String): LlmExtractionResult? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || ocrText.isBlank()) return@withContext null
            try {
                val result1 = callApi(ocrText, apiKey, LlmPrompt.systemPromptWithHints(null))
                if (result1 != null && hasUsefulData(result1)) return@withContext result1
                val result2 = callApi(ocrText, apiKey, LlmPrompt.FALLBACK_PROMPT)
                result2 ?: result1
            } catch (e: Exception) {
                null
            }
        }

    /** ★ يستخرج مع تضمين hints من Regex كقرائن مؤكّدة. */
    override suspend fun extractWithHints(
        ocrText: String,
        apiKey: String,
        hints: ExtractionHints?
    ): LlmExtractionResult? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || ocrText.isBlank()) return@withContext null
        try {
            // المرحلة الأولى: hints-aware prompt
            val result1 = callApi(ocrText, apiKey, LlmPrompt.systemPromptWithHints(hints))
            if (result1 != null && hasUsefulData(result1)) return@withContext result1
            // المرحلة الثانية: fallback prompt صارم
            val result2 = callApi(ocrText, apiKey, LlmPrompt.FALLBACK_PROMPT)
            result2 ?: result1
        } catch (e: Exception) { null }
    }

    /** يستدعي Claude API مع prompt محدّد. */
    private fun callApi(ocrText: String, apiKey: String, systemPrompt: String): LlmExtractionResult? {
        val requestJson = buildJsonObject {
            put("model", MODEL)
            put("max_tokens", 1024)
            put("temperature", 0.0) // ★ حتمية كاملة
            put("system", systemPrompt)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", LlmPrompt.userPrompt(ocrText))
                }
            }
        }
        val body = requestJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val responseText = response.body?.string() ?: return null
            val content = json.parseToJsonElement(responseText)
                .jsonObject["content"]?.jsonArray ?: return null
            val text = content.joinToString("") { block ->
                val obj = block.jsonObject
                if (obj["type"]?.jsonPrimitive?.contentOrNull == "text")
                    obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                else ""
            }
            return LlmResponseParser.parse(text, engineId)
        }
    }

    /** يتحقق أن النتيجة تحوي بيانات مفيدة (وليست JSON فارغاً). */
    private fun hasUsefulData(result: LlmExtractionResult): Boolean {
        return !result.senderName.isNullOrBlank() ||
            !result.receiverName.isNullOrBlank() ||
            result.amount != null
    }

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-sonnet-5"
    }
}
