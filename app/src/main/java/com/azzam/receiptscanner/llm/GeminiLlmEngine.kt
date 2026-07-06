package com.azzam.receiptscanner.llm

import android.util.Base64
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * محرك Google Gemini — Vision API مباشر (بدون OCR محلي).
 * يرسل الصورة كـ base64 ويستخرج البيانات كاملة.
 */
class GeminiLlmEngine : LlmEngine {

    override val engineId = "gemini"
    override val displayName = "Gemini (Google)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun extractFromFile(file: File, apiKey: String): LlmExtractionResult? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || !file.exists()) return@withContext null
            try {
                val r1 = callVisionApi(file, apiKey, LlmPrompt.VISION_PROMPT)
                if (r1 != null && hasUsefulData(r1)) return@withContext r1
                val r2 = callVisionApi(file, apiKey, LlmPrompt.FALLBACK_PROMPT)
                r2 ?: r1
            } catch (e: Exception) { null }
        }

    private fun callVisionApi(file: File, apiKey: String, systemPrompt: String): LlmExtractionResult? {
        val base64Data = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        val isPdf = file.extension.lowercase() == "pdf"
        val mime = if (isPdf) "application/pdf" else guessImageMime(file)

        val requestJson = """
            {
              "system_instruction": { "parts": [{ "text": ${escapeJson(systemPrompt)} }] },
              "contents": [{
                "role": "user",
                "parts": [
                  { "inline_data": { "mime_type": "$mime", "data": "$base64Data" } },
                  { "text": "استخرج بيانات هذه الحوالة. ارد JSON فقط." }
                ]
              }],
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

    private fun guessImageMime(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun escapeJson(s: String): String =
        kotlinx.serialization.json.JsonPrimitive(s).toString()

    companion object {
        private const val API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key="
    }
}
