package com.azzam.receiptscanner.storage

import android.content.Context

/**
 * يتتبّع الملفات التي عولجت مسبقاً (بالمسار + وقت التعديل + الحجم) حتى لا
 * تُعالَج الفاتورة نفسها مرتين.
 *
 * إصلاح جوهري: إضافة resetAll() لإعادة الضبط الكامل، وإصدار schema
 * يسمح بإعادة المعالجة عند تحديث التطبيق أو إعادة تثبيته.
 */
object ProcessedFilesTracker {
    private const val PREFS = "processed_files"
    private const val KEY_SET = "keys"
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val CURRENT_SCHEMA_VERSION = 3
    private const val MAX_KEPT = 5000

    fun isProcessed(context: Context, key: String): Boolean {
        ensureSchemaVersion(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_SET, emptySet())?.contains(key) == true
    }

    @Synchronized
    fun markProcessed(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet(KEY_SET, emptySet()) ?: emptySet()).toMutableSet()
        current.add(key)

        val trimmed = if (current.size > MAX_KEPT) {
            current.toList().takeLast(MAX_KEPT).toSet()
        } else {
            current
        }
        prefs.edit().putStringSet(KEY_SET, trimmed).apply()
    }

    /**
     * ★ يعيد ضبط كل الملفات المعالَجة — يسمح بإعادة المعالجة الكاملة.
     * يُستدعى عند تحديث التطبيق أو إعادة التثبيت أو طلب المستخدم.
     */
    @Synchronized
    fun resetAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        prefs.edit().putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION).apply()
    }

    /**
     * ★ يتأكد أن إصدار schema محدّث. إن كان قديماً، أعد الضبط.
     * هذا يضمن أن أي تحديث للتطبيق (يغيّر منطق المعالجة) يعيد معالجة الملفات.
     */
    private fun ensureSchemaVersion(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getInt(KEY_SCHEMA_VERSION, 0)
        if (stored != CURRENT_SCHEMA_VERSION) {
            prefs.edit().clear().putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION).apply()
        }
    }
}
