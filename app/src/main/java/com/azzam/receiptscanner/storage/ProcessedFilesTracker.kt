package com.azzam.receiptscanner.storage

import android.content.Context

/**
 * يتتبّع الملفات التي عولجت مسبقاً (بالمسار + وقت التعديل + الحجم) حتى لا
 * تُعالَج الفاتورة نفسها مرتين بين المسح الفوري (FileObserver) والمسح
 * الدوري الاحتياطي (WorkManager). بيانات غير حساسة (أسماء ملفات فقط)
 * لذا لا حاجة لتشفيرها.
 */
object ProcessedFilesTracker {
    private const val PREFS = "processed_files"
    private const val KEY_SET = "keys"
    private const val MAX_KEPT = 5000

    fun isProcessed(context: Context, key: String): Boolean {
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
}
