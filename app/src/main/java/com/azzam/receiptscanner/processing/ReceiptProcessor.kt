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
 * تحسينات الأداء:
 *  - استخدام ImageCompressor لفك تشفير الصور بأبعاد مخفّضة (RGB_565)
 *    بدلاً من decodeFile الكامل — يوفر ~70% من الذاكرة
 *  - ضمان bitmap.recycle() بعد كل استخدام (تفادي Memory Leaks)
 *  - استخدام try-finally للتخلص الآمن من Bitmaps حتى عند الفشل
 */
object ReceiptProcessor {

    private val registry = ParserRegistry()

    /**
     * يعالج ملفاً واحداً. آمن للاستدعاء المتزامن (idempotent).
     * يعيد true إن تم حفظ سجل، false إن تُجُوهِل.
     */
    suspend fun processFile(context: Context, file: File): Boolean {
        if (!file.exists()) return false

        val key = "${file.absolutePath}_${file.lastModified()}_${file.length()}"
        if (ProcessedFilesTracker.isProcessed(context, key)) return false
        if (!FileFilter.isCandidateReceipt(file)) return false

        ProcessedFilesTracker.markProcessed(context, key)

        val filenameHint = FileFilter.filenameHint(file)
        if (filenameHint == false) return false

        val ocrText = try {
            extractText(file)
        } catch (e: Exception) {
            ""
        }

        if (filenameHint != true) {
            if (ocrText.isBlank() || !FileFilter.looksLikeReceipt(ocrText)) return false
        }

        // ===== المرحلة الأولى: Regex محلي صارم (سريع + مؤكّد) =====
        var bankId = "unknown"
        var fields: ParsedFields? = null
        if (ocrText.isNotBlank()) {
            val extraction = registry.extract(ocrText)
            bankId = extraction?.first ?: "unknown"
            fields = extraction?.second
        }

        // ===== بناء Hints من المستخرَجات المؤكّدة =====
        val hints = ExtractionHints(
            amount = fields?.amount,
            date = fields?.date,
            iban = extractIban(ocrText)
        )

        // ===== المرحلة الثانية: LLM مع hints (تركيز على الأسماء + البنك) =====
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

        val amountValue = fields?.amount
        val hasValidAmount = amountValue != null && amountValue >= 1.0
        val hasValidDate = fields?.date != null
        val hasName = !fields?.senderName.isNullOrBlank() || !fields?.recipientName.isNullOrBlank()

        val shouldSave = when {
            hasValidAmount -> true
            hasValidDate && hasName -> true
            else -> false
        }
        if (!shouldSave) return false

        // ★ تصحيح bankId عبر Fuzzy Matching (BankMatcher)
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
            // ★ حفظ المسار الأصلي للملف لشاشة المراجعة
            originalFilePath = file.absolutePath
        )

        TransferRepository.addTransfer(context, transfer)
        return true
    }

    private suspend fun tryLlmExtraction(context: Context, ocrText: String):
        com.azzam.receiptscanner.llm.LlmExtractionResult? {
        val activeEngineId = ApiKeyStore.getActiveEngine(context)
        val engine = LlmManager.getEngine(activeEngineId) ?: return null
        val apiKey = ApiKeyStore.getApiKey(context, activeEngineId) ?: return null
        return engine.extract(ocrText, apiKey)
    }

    /** ★ يستدعي المحرك النشط مع تمرير hints (المرحلة الثانية الهجينة). */
    private suspend fun tryLlmExtractionWithHints(
        context: Context,
        ocrText: String,
        hints: ExtractionHints
    ): com.azzam.receiptscanner.llm.LlmExtractionResult? {
        val activeEngineId = ApiKeyStore.getActiveEngine(context)
        val engine = LlmManager.getEngine(activeEngineId) ?: return null
        val apiKey = ApiKeyStore.getApiKey(context, activeEngineId) ?: return null
        // استخدم extractWithHints إذا وفّرها المحرك، وإلا fallback للعادية
        return engine.extractWithHints(ocrText, apiKey, hints)
    }

    /** يستخرج IBAN سعودي من النص (للـ hints). */
    private fun extractIban(text: String): String? {
        // IBAN كامل: SA + 22 رقم
        Regex("""SA\d{2}\s?\d{2}\s?\d{18}""").find(text)?.let { return it.value.replace(" ", "") }
        // IBAN مقنّع: SA** **** **** **** **** 7862
        Regex("""SA\*+\s*\*+\s*\*+\s*\*+\s*\*+\s*\d+""").find(text)?.let { return it.value }
        return null
    }

    private fun mergeFields(
        original: ParsedFields?,
        llm: com.azzam.receiptscanner.llm.LlmExtractionResult
    ): ParsedFields {
        // ★ تطبيق DataSanitizer على نتيجة LLM قبل الدمج
        val sanitized = DataSanitizer.sanitize(llm)
        return ParsedFields(
            senderName = original?.senderName?.takeIf { it.isNotBlank() } ?: sanitized.senderName,
            recipientName = original?.recipientName?.takeIf { it.isNotBlank() } ?: sanitized.recipientName,
            amount = original?.amount ?: sanitized.amount,
            date = original?.date?.takeIf { it.isNotBlank() } ?: sanitized.date
        )
    }

    /**
     * استخراج النص مع ضمان bitmap.recycle() — تفادي Memory Leaks.
     * يستخدم ImagePreprocessor (تدرج رمادي + تباين + binarization) لتحسين دقة OCR.
     */
    private suspend fun extractText(file: File): String {
        return if (FileFilter.isPdf(file)) {
            val pages = PdfHelper.renderPages(file)
            try {
                val texts = mutableListOf<String>()
                for (page in pages) {
                    try {
                        // ★ طبّق المعالجة المتقدمة على كل صفحة PDF
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
            // ★ استخدم ImagePreprocessor بدلاً من decodeSampled المباشر
            val bitmap = ImagePreprocessor.preprocess(file) ?: return ""
            try {
                MlKitOcrHelper.recognize(bitmap)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }
}
