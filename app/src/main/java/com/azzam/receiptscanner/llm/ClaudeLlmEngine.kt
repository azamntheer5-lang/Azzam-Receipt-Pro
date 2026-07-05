package com.azzam.receiptscanner.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * محرك Anthropic Claude لاستخراج بيانات الحوالة من نص OCR.
 *
 * التطوير (المرحلة 2): لم يعد يرسل الصورة/PDF مباشرةً، بل يعالج نص OCR الخام
 * مع [LlmPrompt] الموحّد — هذا يتوافق مع طلب استخدام System Prompt صارم
 * مع جميع المحركات، ويقلّل التكلفة (text input أرخص من vision).
 *
 * التصميم: كل محرك يطبّق [LlmEngine] ويعزل تفاصيل API خلف الواجهة الموحّدة.
 */
class ClaudeLlmEngine : LlmEngine {

    override val engineId = "claude"
    override val displayName = "Claude (Anthropic)"

    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-sonnet-5"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    override suspend fun extract(ocrText: String, apiKey: String): LlmExtractionResult? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || ocrText.isBlank()) return@withContext null
            try {
                val requestJson = buildJsonObject {
                    put("model", MODEL)
                    put("max_tokens", 512)
                    put("system", LlmPrompt.SYSTEM_PROMPT)
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "user")
                            put("content", LlmPrompt.userPrompt(ocrText))
                        }
                    }
                }
                val body = requestJson.toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(API_URL)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("content-type", "application/json")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val responseText = response.body?.string() ?: return@withContext null
                    val content = Json.parseToJsonElement(responseText).jsonObject
                        ["content"]?.jsonArray ?: return@withContext null
                    // Claude يُرجع مصفوفة من blocks؛ نجمّع نصها
                    val text = content.joinToString("") { block ->
                        val obj = block.jsonObject
                        if (obj["type"]?.jsonPrimitive?.contentOrNull == "text")
                            obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        else ""
                    }
                    LlmResponseParser.parse(text, engineId)
                }
            } catch (e: Exception) {
                null
            }
        }
}
