package com.azzam.receiptscanner.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * محرك Google Gemini — مع آلية إعادة محاولة ذكية (Smart Retry).
 * temperature=0.0 + fallback prompt عند فشل الاستخراج الأول.
 */
class GeminiLlmEngine : LlmEngine {

    override val engineId = "gemini"
    override val displayName = "Gemini (Google)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    override suspend fun extract(ocrText: String, apiKey: String): LlmExtractionResult? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || ocrText.isBlank()) return@withContext null
            try {
                val r1 = callApi(ocrText, apiKey, LlmPrompt.SYSTEM_PROMPT)
                if (r1 != null && hasUsefulData(r1)) return@withContext r1
                val r2 = callApi(ocrText, apiKey, LlmPrompt.FALLBACK_PROMPT)
                r2 ?: r1
            } catch (e: Exception) { null }
        }

    private fun callApi(ocrText: String, apiKey: String, systemPrompt: String): LlmExtractionResult? {
        val requestJson = """
            {
              "system_instruction": { "parts": [{ "text": ${escapeJson(systemPrompt)} }] },
              "contents": [{ "role": "user", "parts": [{ "text": ${escapeJson(LlmPrompt.userPrompt(ocrText))} }] }],
              "generationConfig": { "temperature": 0.0, "maxOutputTokens": 1024 }
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("$API_URL$apiKey")
            .addHeader("content-type", "application/json")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string() ?: return null
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
            val content = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull
                ?: return null
            return LlmResponseParser.parse(content, engineId)
        }
    }

    private fun hasUsefulData(r: LlmExtractionResult) =
        !r.senderName.isNullOrBlank() || !r.receiverName.isNullOrBlank() || r.amount != null

    private fun escapeJson(s: String): String =
        kotlinx.serialization.json.JsonPrimitive(s).toString()

    companion object {
        private const val API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key="
    }
}
