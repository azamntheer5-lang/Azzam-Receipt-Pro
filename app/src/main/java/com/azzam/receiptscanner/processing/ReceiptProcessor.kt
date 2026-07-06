package com.azzam.receiptscanner.processing

import android.content.Context
import com.azzam.receiptscanner.llm.LlmManager
import com.azzam.receiptscanner.model.Transfer
import com.azzam.receiptscanner.parser.BankMatcher
import com.azzam.receiptscanner.parser.DataSanitizer
import com.azzam.receiptscanner.storage.ApiKeyStore
import com.azzam.receiptscanner.storage.ProcessedFilesTracker
import com.azzam.receiptscanner.storage.TransferRepository
import java.io.File
import java.util.UUID

/**
 * خط المعالجة الكامل — API فقط بدون OCR محلي.
 *
 * إصلاح جوهري: أزلنا ML Kit OCR و ImagePreprocessor تماماً.
 * الملف (صورة/PDF) يُرسل مباشرة للـ LLM عبر Vision API.
 * هذا أسرع بكثير ويدعم العربية بدقة عالية.
 *
 * المتطلبات: مفتاح API مُعد في الإعدادات (Claude أو Gemini).
 */
object ReceiptProcessor {

    suspend fun processFile(context: Context, file: File): Boolean {
        DiagnosticLogger.logFileProcessed()

        if (!file.exists()) {
            DiagnosticLogger.logRejection("الملف غير موجود")
            return false
        }

        val key = "${file.absolutePath}_${file.lastModified()}_${file.length()}"
        if (ProcessedFilesTracker.isProcessed(context, key)) {
            DiagnosticLogger.logRejection("معالَج مسبقاً")
            return false
        }
        if (!FileFilter.isCandidateReceipt(file)) {
            DiagnosticLogger.logRejection("ليس صورة/PDF أو حجم غير مناسب")
            return false
        }

        ProcessedFilesTracker.markProcessed(context, key)

        // ★ تحقق من وجود مفتاح API
        val activeEngineId = ApiKeyStore.getActiveEngine(context)
        val engine = LlmManager.getEngine(activeEngineId)
        val apiKey = ApiKeyStore.getApiKey(context, activeEngineId)

        if (engine == null || apiKey.isNullOrBlank()) {
            DiagnosticLogger.logRejection("لا يوجد مفتاح API — اذهب للإعدادات")
            return false
        }

        // ★ أرسل الملف مباشرة للـ API (بدون OCR محلي)
        val result = try {
            engine.extractFromFile(file, apiKey)
        } catch (e: Exception) {
            DiagnosticLogger.logRejection("فشل API: ${e.message?.take(50)}")
            null
        }

        if (result == null) {
            DiagnosticLogger.logRejection("API أعاد null")
            return false
        }

        // ★ تنظيف البيانات
        val sanitized = DataSanitizer.sanitize(result)
        val amountValue = sanitized.amount
        val hasValidAmount = amountValue != null && amountValue >= 1.0
        val hasValidDate = sanitized.date != null
        val hasName = !sanitized.senderName.isNullOrBlank() || !sanitized.recipientName.isNullOrBlank()

        val shouldSave = when {
            hasValidAmount -> true
            hasValidDate && hasName -> true
            else -> false
        }
        if (!shouldSave) {
            val reason = mutableListOf<String>()
            if (!hasValidAmount) reason.add("بلا مبلغ")
            if (!hasValidDate) reason.add("بلا تاريخ")
            if (!hasName) reason.add("بلا اسم")
            DiagnosticLogger.logRejection("بيانات ناقصة: ${reason.joinToString()}")
            return false
        }

        // ★ تصحيح bankId
        var bankId = BankMatcher.normalizeBankId("unknown")
        // حاول استخراج البنك من اسم الملف أو المحتوى
        bankId = BankMatcher.normalizeBankId(file.name) ?: bankId

        val transfer = Transfer(
            id = UUID.randomUUID().toString(),
            senderName = sanitized.senderName,
            recipientName = sanitized.recipientName,
            amount = sanitized.amount,
            date = sanitized.date,
            bankId = bankId,
            confidence = 0.95f, // API extraction = high confidence
            sourceFileName = file.name,
            processedAt = System.currentTimeMillis(),
            rawText = "",
            llmEngineUsed = result.engineId,
            originalFilePath = file.absolutePath
        )

        TransferRepository.addTransfer(context, transfer)
        DiagnosticLogger.logFileSaved()
        return true
    }
}
