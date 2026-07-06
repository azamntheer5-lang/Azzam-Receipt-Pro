package com.azzam.receiptscanner.ocr

import android.util.Base64
import com.azzam.receiptscanner.parser.ParsedFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * استخراج احتياطي عبر Claude API عندما يفشل/ينقص استخراج Regex - يقرأ
 * الصورة أو PDF مباشرة (بدون خطوة OCR منفصلة)، فيقرأ العربية بدقة عالية
 * خلافاً لـ ML Kit. يعمل من أي سياق (خدمة خلفية، Worker، أو الواجهة)
 * لأنه مجرد طلب HTTP عادي - بعكس Gemini Nano المقيّد بالواجهة الأمامية
 * فقط، وهو قيد يجعله غير مناسب لخط المراقبة التلقائية في هذا التطبيق.
 *
 * ⚠️ المفتاح مخزَّن مشفَّراً على الجهاز ويُستخدم من التطبيق مباشرة - مقبول
 * لمشروع شخصي غير موزَّع (راجع الدليل التقني السابق لسبب هذا القرار).
 * إن شاركت هذا التطبيق أو كوده مع أي أحد، انقل هذا الاستدعاء لخادم خلفي
 * بدل تضمين المفتاح في التطبيق مباشرة.
 *
 * التكلفة: كل إيصال يحتاج السحابة = استدعاء واحد مدفوع. الكود هنا يستدعي
 * هذا المسار فقط عند فشل/نقص Regex، وليس على كل ملف.
 */
object CloudExtractor {

    private const val API_URL = "https://api.anthropic.com/v1/messages"
    // Sonnet هنا وليس Haiku لأن دعم PDF عبر هذه الطريقة (base64 مباشر) لا
    // يزال Beta ومحدوداً ببعض الموديلات - تحقّق من آخر التوصيات في
    // docs.claude.com/en/docs/build-with-claude/pdf-support قبل التبديل لموديل أرخص
    private const val MODEL = "claude-sonnet-5"
    private const val PDF_BETA_HEADER = "pdfs-2024-09-25"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun extract(file: File, apiKey: String, isPdf: Boolean): ParsedFields? =
        withContext(Dispatchers.IO) {
            try {
                val base64Data = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)

                val mediaBlock = buildJsonObject {
                    put("type", if (isPdf) "document" else "image")
                    putJsonObject("source") {
                        put("type", "base64")
                        put("media_type", if (isPdf) "application/pdf" else guessImageMime(file))
                        put("data", base64Data)
                    }
                }

                val requestJson = buildJsonObject {
                    put("model", MODEL)
                    put("max_tokens", 300)
                    putJsonArray("tools") {
                        addJsonObject {
                            put("name", "record_transfer")
                            put("description", "سجّل بيانات التحويل البنكي المستخرجة من الإيصال. اترك الحقل فارغاً/صفراً إن لم يظهر بوضوح.")
                            putJsonObject("input_schema") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("sender_name") { put("type", "string") }
                                    putJsonObject("recipient_name") { put("type", "string") }
                                    putJsonObject("amount") { put("type", "number") }
                                    putJsonObject("date") { put("type", "string") }
                                }
                            }
                        }
                    }
                    putJsonObject("tool_choice") {
                        put("type", "tool")
                        put("name", "record_transfer")
                    }
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                add(mediaBlock)
                                addJsonObject {
                                    put("type", "text")
                                    put(
                                        "text",
                                        "هذا إيصال تحويل بنكي بالعربية أو الإنجليزية. استخرج اسم المرسل، " +
                                            "اسم المستلم، المبلغ، والتاريخ بدقة."
                                    )
                                }
                            }
                        }
                    }
                }

                val body = requestJson.toString().toRequestBody("application/json".toMediaType())
                val requestBuilder = Request.Builder()
                    .url(API_URL)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .post(body)

                if (isPdf) {
                    requestBuilder.addHeader("anthropic-beta", PDF_BETA_HEADER)
                }

                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val responseText = response.body?.string() ?: return@withContext null
                    parseToolResult(responseText)
                }
            } catch (e: Exception) {
                null // فشل اتصال/مفتاح غير صالح/انتهاء مهلة/أي خطأ آخر - تجاهل بهدوء
            }
        }

    private fun parseToolResult(responseText: String): ParsedFields? {
        return try {
            val root = Json.parseToJsonElement(responseText).jsonObject
            val content = root["content"]?.jsonArray ?: return null
            val toolUse = content.firstOrNull {
                it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_use"
            }?.jsonObject ?: return null

            val input = toolUse["input"]?.jsonObject ?: return null
            val sender = input["sender_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val recipient = input["recipient_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val amount = input["amount"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }
            val date = input["date"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

            if (sender == null && recipient == null && amount == null && date == null) return null
            ParsedFields(sender, recipient, amount, date)
        } catch (e: Exception) {
            null
        }
    }

    private fun guessImageMime(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
}
