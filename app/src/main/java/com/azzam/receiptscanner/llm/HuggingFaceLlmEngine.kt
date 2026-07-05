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
 * محرك Hugging Face لاستخراج بيانات الحوالة من نص OCR.
 *
 * يستخدم Inference API (router) مع نموذج chat مفتوح. Hugging Face يوفّر
 * وصولاً مجانياً محدوداً لنماذج متعددة. نستخدم واجهة OpenAI-compatible
 * عبر router لتبسيط الاستدعاء.
 *
 * ملاحظة: قد يختلف النموذج المتاح حسب التوفر؛ إن فشل النموذج الافتراضي
 * يمكن للمستخدم تجربة نموذج آخر عبر تعديل MODEL.
 */
class HuggingFaceLlmEngine : LlmEngine {

    override val engineId = "huggingface"
    override val displayName = "Hugging Face"

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // HF أبطأ أحياناً
        .build()

    override suspend fun extract(ocrText: String, apiKey: String): LlmExtractionResult? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || ocrText.isBlank()) return@withContext null
            try {
                val requestJson = """
                    {
                      "model": "$MODEL",
                      "temperature": 0.0,
                      "max_tokens": 512,
                      "messages": [
                        { "role": "system", "content": ${escapeJson(LlmPrompt.SYSTEM_PROMPT)} },
                        { "role": "user", "content": ${escapeJson(LlmPrompt.userPrompt(ocrText))} }
                      ]
                    }
                """.trimIndent()

                val body = requestJson.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(API_URL)
                    .addHeader("authorization", "Bearer $apiKey")
                    .addHeader("content-type", "application/json")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val responseText = response.body?.string() ?: return@withContext null
                    val root = Json.parseToJsonElement(responseText) as? JsonObject
                        ?: return@withContext null
                    // HF router يتبع صيغة OpenAI: choices[].message.content
                    val choices = root["choices"]?.jsonArray ?: return@withContext null
                    val text = choices.firstOrNull()?.jsonObject
                        ?.get("message")?.jsonObject
                        ?.get("content")?.jsonPrimitive?.contentOrNull
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

    companion object {
        private const val API_URL = "https://router.huggingface.co/v1/chat/completions"
        // نموذج chat متاح شائع على HF Inference — قابل للتغيير حسب التوفر
        private const val MODEL = "meta-llama/Llama-3.1-8B-Instruct"
    }
}
