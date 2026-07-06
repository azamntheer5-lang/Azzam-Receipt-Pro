package com.azzam.receiptscanner.processing

import android.content.Context
import android.graphics.Bitmap
import com.azzam.receiptscanner.llm.ExtractionHints
import com.azzam.receiptscanner.llm.LlmManager
import com.azzam.receiptscanner.model.Transfer
import com.azzam.receiptscanner.ocr.ImageCompressor
import com.azzam.receiptscanner.ocr.ImagePreprocessor
import com.azzam.receiptscanner.ocr.MlKitOcrHelper
import com.azzam.receiptscanner.ocr.PdfHelper
import com.azzam.receiptscanner.parser.BankMatcher
import com.azzam.receiptscanner.parser.DataSanitizer
import com.azzam.receiptscanner.parser.FieldExtractors
import com.azzam.receiptscanner.parser.ParsedFields
import com.azzam.receiptscanner.parser.ParserRegistry
import com.azzam.receiptscanner.storage.ApiKeyStore
import com.azzam.receiptscanner.storage.ProcessedFilesTracker
import com.azzam.receiptscanner.storage.TransferRepository
import java.io.File
import java.util.UUID

/**
 * خط المعالجة الكامل لملف واحد.
 *
 * تحسين: تسجيل تشخيصي كامل لكل ملف لمعرفة سبب الرفض/القبول.
 */
object ReceiptProcessor {

    private val registry = ParserRegistry()

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
            val size = file.length()
            val ext = file.extension
            DiagnosticLogger.logRejection("ليس صورة/PDF أو حجم غير مناسب ($ext, ${size/1024}KB)")
            return false
        }

        ProcessedFilesTracker.markProcessed(context, key)

        val filenameHint = FileFilter.filenameHint(file)
        if (filenameHint == false) {
            DiagnosticLogger.logRejection("اسم الملف يدل على غير إيصال (${file.name})")
            return false
        }

        val ocrText = try {
            extractText(file)
        } catch (e: Exception) {
            DiagnosticLogger.logRejection("فشل OCR: ${e.message?.take(50)}")
            ""
        }

        if (filenameHint != true) {
            if (ocrText.isBlank()) {
                DiagnosticLogger.logRejection("نص OCR فارغ")
                return false
            }
            if (!FileFilter.looksLikeReceipt(ocrText)) {
                DiagnosticLogger.logRejection("لا يحوي كلمات إيصال (${file.name.take(30)})")
                return false
            }
        }

        // ===== المرحلة الأولى: Regex محلي صارم =====
        var bankId = "unknown"
        var fields: ParsedFields? = null
        if (ocrText.isNotBlank()) {
            val extraction = registry.extract(ocrText)
            bankId = extraction?.first ?: "unknown"
            fields = extraction?.second
        }

        // ===== بناء Hints =====
        val hints = ExtractionHints(
            amount = fields?.amount,
            date = fields?.date,
            iban = extractIban(ocrText)
        )

        // ===== المرحلة الثانية: LLM مع hints =====
        val needsLlmHelp = fields?.amount == null ||
            (fields.recipientName.isNullOrBlank() && fields.senderName.isNullOrBlank())

        var usedLlm = false
        var llmEngineUsed: String? = null
        if (needsLlmHelp && ocrText.isNotBlank()) {
            val llmFields = tryLlmExtractionWithHints(context, ocrText, hints)
            if (llmFields != null) {
                fields = mergeFields(fields, llmFields)
                if (bankId == "unknown") bankId = "llm_${llmFields.engineId}"
                usedLlm = true
                llmEngineUsed = llmFields.engineId
            }
        }

        // ===== عتبة الحفظ =====
        val amountValue = fields?.amount
        val hasValidAmount = amountValue != null && amountValue >= 1.0
        val hasValidDate = fields?.date != null
        val hasName = !fields?.senderName.isNullOrBlank() || !fields?.recipientName.isNullOrBlank()

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

        // ★ تصحيح bankId عبر Fuzzy Matching
        bankId = BankMatcher.normalizeBankId(bankId)

        val transfer = Transfer(
            id = UUID.randomUUID().toString(),
            senderName = fields?.senderName,
            recipientName = fields?.recipientName,
            amount = fields?.amount,
            date = fields?.date,
            bankId = bankId,
            confidence = when {
                hasValidAmount && usedLlm && hasName -> 0.9f
                hasValidAmount && hasName -> 0.75f
                hasValidAmount -> 0.5f
                hasValidDate && hasName -> 0.4f
                else -> 0.2f
            },
            sourceFileName = file.name,
            processedAt = System.currentTimeMillis(),
            rawText = ocrText.take(500),
            llmEngineUsed = llmEngineUsed,
            originalFilePath = file.absolutePath
        )

        TransferRepository.addTransfer(context, transfer)
        DiagnosticLogger.logFileSaved()
        return true
    }

    private suspend fun tryLlmExtractionWithHints(
        context: Context,
        ocrText: String,
        hints: ExtractionHints
    ): com.azzam.receiptscanner.llm.LlmExtractionResult? {
        val activeEngineId = ApiKeyStore.getActiveEngine(context)
        val engine = LlmManager.getEngine(activeEngineId) ?: return null
        val apiKey = ApiKeyStore.getApiKey(context, activeEngineId) ?: return null
        return engine.extractWithHints(ocrText, apiKey, hints)
    }

    private fun mergeFields(
        original: ParsedFields?,
        llm: com.azzam.receiptscanner.llm.LlmExtractionResult
    ): ParsedFields {
        val sanitized = DataSanitizer.sanitize(llm)
        return ParsedFields(
            senderName = original?.senderName?.takeIf { it.isNotBlank() } ?: sanitized.senderName,
            recipientName = original?.recipientName?.takeIf { it.isNotBlank() } ?: sanitized.recipientName,
            amount = original?.amount ?: sanitized.amount,
            date = original?.date?.takeIf { it.isNotBlank() } ?: sanitized.date
        )
    }

    private fun extractIban(text: String): String? {
        Regex("""SA\d{2}\s?\d{2}\s?\d{18}""").find(text)?.let { return it.value.replace(" ", "") }
        Regex("""SA\*+\s*\*+\s*\*+\s*\*+\s*\*+\s*\d+""").find(text)?.let { return it.value }
        return null
    }

    private suspend fun extractText(file: File): String {
        return if (FileFilter.isPdf(file)) {
            val pages = PdfHelper.renderPages(file)
            try {
                val texts = mutableListOf<String>()
                for (page in pages) {
                    try {
                        val processed = ImagePreprocessor.preprocessBitmap(page)
                        texts.add(MlKitOcrHelper.recognize(processed))
                        processed.recycle()
                    } finally {
                        page.recycle()
                    }
                }
                texts.joinToString("\n")
            } catch (e: Exception) {
                pages.forEach { if (!it.isRecycled) it.recycle() }
                throw e
            }
        } else {
            val bitmap = ImagePreprocessor.preprocess(file) ?: return ""
            try {
                MlKitOcrHelper.recognize(bitmap)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }
}
