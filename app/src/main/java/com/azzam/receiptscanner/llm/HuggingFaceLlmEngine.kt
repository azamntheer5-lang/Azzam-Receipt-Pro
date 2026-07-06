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
 * محرك Hugging Face — مع آلية إعادة محاولة ذكية (Smart Retry).
 */
class HuggingFaceLlmEngine : LlmEngine {

    override val engineId = "huggingface"
    override val displayName = "Hugging Face"

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun extractFromFile(file: File, apiKey: String): LlmExtractionResult? = null

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
              "model": "$MODEL",
              "temperature": 0.0,
              "max_tokens": 1024,
              "messages": [
                { "role": "system", "content": ${escapeJson(systemPrompt)} },
                { "role": "user", "content": ${escapeJson(LlmPrompt.userPrompt(ocrText))} }
              ]
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("authorization", "Bearer $apiKey")
            .addHeader("content-type", "application/json")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string() ?: return null
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
            val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull
                ?: return null
            return LlmResponseParser.parse(content, engineId)
        }
    }

    private fun hasUsefulData(r: LlmExtractionResult) =
        !r.senderName.isNullOrBlank() || !r.receiverName.isNullOrBlank() || r.amount != null

    private fun escapeJson(s: String): String =
        kotlinx.serialization.json.JsonPrimitive(s).toString()

    companion object {
        private const val API_URL = "https://router.huggingface.co/v1/chat/completions"
        private const val MODEL = "meta-llama/Llama-3.1-8B-Instruct"
    }
}
