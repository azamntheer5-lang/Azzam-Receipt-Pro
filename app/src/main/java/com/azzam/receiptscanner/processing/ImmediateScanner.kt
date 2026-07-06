package com.azzam.receiptscanner.processing

import android.content.Context
import com.azzam.receiptscanner.ReceiptWatcherService
import com.azzam.receiptscanner.storage.ApiKeyStore
import java.io.File

/**
 * ماسح فوري — يعمل في lifecycleScope مباشرة.
 *
 * إصلاح: حد أقصى 20 ملف لمنع التعليق + تحقق من مفتاح API.
 */
object ImmediateScanner {

    private const val MAX_FILES = 20

    suspend fun scanNow(context: Context): String {
        // ★ تحقق من مفتاح API أولاً
        val activeEngineId = ApiKeyStore.getActiveEngine(context)
        val apiKey = ApiKeyStore.getApiKey(context, activeEngineId)
        if (apiKey.isNullOrBlank()) {
            return "❌ لا يوجد مفتاح API!\n\n" +
                "الفحص عبر API يتطلب مفتاح Claude أو Gemini.\n\n" +
                "الحل:\n" +
                "1. اذهب للقائمة (⋮) → ⚙️ الإعدادات\n" +
                "2. أدخل مفتاح Claude (من console.anthropic.com)\n" +
                "   أو Gemini (من aistudio.google.com — مجاني)\n" +
                "3. اختر المحرك النشط\n" +
                "4. ارجع واضغط زر المسح (FAB)"
        }

        DiagnosticLogger.reset()

        val allPaths = mutableListOf<String>().apply {
            addAll(ReceiptWatcherService.WHATSAPP_PATHS)
            add("/storage/emulated/0/Pictures")
            add("/storage/emulated/0/Download")
            add("/storage/emulated/0/DCIM")
            add("/storage/emulated/0/Documents")
            add("/storage/emulated/0/WhatsApp Business/Media")
            add("/storage/emulated/0/WhatsApp/Media")
        }.distinct()

        // ★ اجمع الملفات المرشّحة أولاً (قبل المعالجة)
        val candidateFiles = mutableListOf<File>()
        for (path in allPaths) {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) continue
            try {
                collectFilesRecursive(dir, candidateFiles, depth = 5, maxFiles = MAX_FILES)
            } catch (e: Exception) {
                continue
            }
            if (candidateFiles.size >= MAX_FILES) break
        }

        if (candidateFiles.isEmpty()) {
            return "❌ لم نجد أي ملفات صور/PDF.\n\n" +
                "الحل: من القائمة (⋮) اختر:\n" +
                "📁 فحص مجلد كامل\n" +
                "واختر مجلد الصور من المعرض مباشرة."
        }

        // ★ عالج الملفات (بحد أقصى MAX_FILES)
        for (file in candidateFiles.take(MAX_FILES)) {
            try {
                ReceiptProcessor.processFile(context, file)
            } catch (e: Exception) {
                DiagnosticLogger.logRejection("خطأ: ${e.message?.take(30)}")
            }
        }

        val report = DiagnosticLogger.buildReport()
        // أضف تنبيه الحد الأقصى إن لزم
        return if (candidateFiles.size >= MAX_FILES) {
            report + "\n\n⚠️ تم فحص أول $MAX_FILES ملف فقط. للفحص الكامل استخدم 'فحص مجلد كامل'."
        } else {
            report
        }
    }

    private fun collectFilesRecursive(dir: File, files: MutableList<File>, depth: Int, currentDepth: Int = 0, maxFiles: Int) {
        if (currentDepth > depth) return
        if (files.size >= maxFiles) return

        val children = try {
            dir.listFiles() ?: return
        } catch (e: Exception) {
            return
        }

        for (child in children) {
            if (files.size >= maxFiles) return
            try {
                if (child.isDirectory) {
                    collectFilesRecursive(child, files, depth, currentDepth + 1, maxFiles)
                } else if (child.isFile) {
                    val ext = child.extension.lowercase()
                    if (ext in listOf("pdf", "jpg", "jpeg", "png", "webp")) {
                        if (child.length() in 1..(5L * 1024 * 1024)) {
                            files.add(child)
                        }
                    }
                }
            } catch (e: Exception) {
                // تجاهل
            }
        }
    }
}
