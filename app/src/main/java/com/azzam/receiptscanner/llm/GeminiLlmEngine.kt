package com.azzam.receiptscanner.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * محرك Google Gemini لاستخراج بيانات الحوالة من نص OCR.
 *
 * يستخدم Gemini 1.5 Flash (متوازن السرعة/التكلفة/الجودة للعربية).
 * النموذج يدعم العربية بقوة، وهذا يكمّل قصور ML Kit المحلي.
 */
class GeminiLlmEngine : LlmEngine {

    override val engineId = "gemini"
    override val displayName = "Gemini (Google)"

    private const val API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key="
    private const val MODEL_REF = "gemini-1.5-flash"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    override suspend fun extract(ocrText: String, apiKey: String): LlmExtractionResult? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || ocrText.isBlank()) return@withContext null
            try {
                val requestJson = """
                    {
                      "system_instruction": {
                        "parts": [{ "text": ${escapeJson(LlmPrompt.SYSTEM_PROMPT)} }]
                      },
                      "contents": [{
                        "role": "user",
                        "parts": [{ "text": ${escapeJson(LlmPrompt.userPrompt(ocrText))} }]
                      }],
                      "generationConfig": {
                        "temperature": 0.0,
                        "maxOutputTokens": 512
                      }
                    }
                """.trimIndent()

                val body = requestJson.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$API_URL$apiKey")
                    .addHeader("content-type", "application/json")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val responseText = response.body?.string() ?: return@withContext null
                    val root = Json.parseToJsonElement(responseText) as? JsonObject
                        ?: return@withContext null
                    val candidates = root["candidates"]?.jsonArray ?: return@withContext null
                    val text = candidates.firstOrNull()?.jsonObject
                        ?.get("content")?.jsonObject
                        ?.get("parts")?.jsonArray
                        ?.firstOrNull()?.jsonObject
                        ?.get("text")?.jsonPrimitive?.contentOrNull
                        ?: return@withContext null
                    LlmResponseParser.parse(text, engineId)
                }
            } catch (e: Exception) {
                null
            }
        }

    /** يُرجع تمثيل JSON صالح للسلسلة (مع علامات اقتباس وتهريب). */
    private fun escapeJson(s: String): String =
        kotlinx.serialization.json.JsonPrimitive(s).toString()
}
