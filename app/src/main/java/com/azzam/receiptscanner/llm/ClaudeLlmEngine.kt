package com.azzam.receiptscanner.llm

import android.util.Base64
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * محرك Anthropic Claude — Vision API مباشر (بدون OCR محلي).
 *
 * يرسل الصورة/PDF كـ base64 للـ API، ويستخرج البيانات كاملة.
 * Claude يقرأ العربية بدقة عالية عبر Vision.
 */
class ClaudeLlmEngine : LlmEngine {

    override val engineId = "claude"
    override val displayName = "Claude (Anthropic)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun extractFromFile(file: File, apiKey: String): LlmExtractionResult? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || !file.exists()) return@withContext null
            try {
                val result1 = callVisionApi(file, apiKey, LlmPrompt.VISION_PROMPT)
                if (result1 != null && hasUsefulData(result1)) return@withContext result1
                val result2 = callVisionApi(file, apiKey, LlmPrompt.FALLBACK_PROMPT)
                result2 ?: result1
            } catch (e: Exception) { null }
        }

    private fun callVisionApi(file: File, apiKey: String, systemPrompt: String): LlmExtractionResult? {
        val base64Data = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        val isPdf = file.extension.lowercase() == "pdf"
        val mediaType = if (isPdf) "application/pdf" else guessImageMime(file)

        val mediaBlock = buildJsonObject {
            put("type", if (isPdf) "document" else "image")
            putJsonObject("source") {
                put("type", "base64")
                put("media_type", mediaType)
                put("data", base64Data)
            }
        }

        val requestJson = buildJsonObject {
            put("model", MODEL)
            put("max_tokens", 1024)
            put("temperature", 0.0)
            put("system", systemPrompt)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(mediaBlock)
                        addJsonObject {
                            put("type", "text")
                            put("text", "استخرج بيانات هذه الحوالة. ارد JSON فقط.")
                        }
                    }
                }
            }
        }

        val body = requestJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .apply { if (isPdf) addHeader("anthropic-beta", "pdfs-2024-09-25") }
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

    private fun hasUsefulData(r: LlmExtractionResult) =
        !r.senderName.isNullOrBlank() || !r.receiverName.isNullOrBlank() || r.amount != null

    private fun guessImageMime(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-sonnet-5"
    }
}
