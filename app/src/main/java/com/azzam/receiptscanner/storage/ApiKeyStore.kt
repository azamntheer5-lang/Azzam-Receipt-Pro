package com.azzam.receiptscanner.storage

import android.content.Context
import java.io.File

/**
 * يخزّن مفاتيح API مشفّرة لكل محركات الذكاء الاصطناعي + المحرك النشط.
 *
 * التطوير (المرحلة 2): توسّع ليدعم مفاتيح متعددة (Claude/Gemini/Groq/HF)
 * بدل مفتاح واحد، إضافةً لتخزين المحرك النشط. يستخدم نفس آلية SecureStorage
 * (AES-256-GCM عبر Keystore) لكل مفتاح على حدة.
 *
 * التوافق الخلفي: [getKey] (القديمة) تُرجع مفتاح Claude فقط — للحفاظ على
 * أي كود قديم يشير إليها. الكود الجديد يستخدم [getApiKey].
 */
object ApiKeyStore {

    // معرّفات المحركات — يجب أن تتطابق مع LlmEngine.engineId
    const val ENGINE_CLAUDE = "claude"
    const val ENGINE_GEMINI = "gemini"
    const val ENGINE_GROQ = "groq"
    const val ENGINE_HUGGINGFACE = "huggingface"

    private const val ACTIVE_ENGINE_PREF = "llm_settings"
    private const val KEY_ACTIVE_ENGINE = "active_engine"

    /** يُرجع مفتاح API للمحرك المحدد، أو null إن غير موجود. */
    fun getApiKey(context: Context, engineId: String): String? {
        val file = keyFile(context, engineId)
        return SecureStorage.readAndDecrypt(file)?.takeIf { it.isNotBlank() }
    }

    /** يخزّن مفتاح API للمحرك المحدد. */
    fun setApiKey(context: Context, engineId: String, apiKey: String) {
        SecureStorage.encryptAndWrite(keyFile(context, engineId), apiKey.trim())
    }

    /** يحذف مفتاح API للمحرك المحدد. */
    fun clearApiKey(context: Context, engineId: String) {
        keyFile(context, engineId).delete()
    }

    /** المحرك النشط حالياً (افتراضي: claude للحفاظ على السلوك القديم). */
    fun getActiveEngine(context: Context): String {
        val prefs = context.getSharedPreferences(ACTIVE_ENGINE_PREF, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTIVE_ENGINE, ENGINE_CLAUDE) ?: ENGINE_CLAUDE
    }

    /** يضبط المحرك النشط. */
    fun setActiveEngine(context: Context, engineId: String) {
        context.getSharedPreferences(ACTIVE_ENGINE_PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE_ENGINE, engineId).apply()
    }

    // ---------- توافق خلفي مع الواجهة القديمة ----------

    /** @deprecated استخدم [getApiKey] مع engineId = ENGINE_CLAUDE. */
    fun getKey(context: Context): String? = getApiKey(context, ENGINE_CLAUDE)

    /** @deprecated استخدم [setApiKey] مع engineId = ENGINE_CLAUDE. */
    fun setKey(context: Context, apiKey: String) {
        setApiKey(context, ENGINE_CLAUDE, apiKey)
    }

    /** @deprecated استخدم [clearApiKey] مع engineId = ENGINE_CLAUDE. */
    fun clearKey(context: Context) {
        clearApiKey(context, ENGINE_CLAUDE)
    }

    private fun keyFile(context: Context, engineId: String): File =
        File(context.filesDir, "api_key_$engineId.enc")
}
