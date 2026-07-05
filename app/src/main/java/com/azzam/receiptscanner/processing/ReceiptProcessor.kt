package com.azzam.receiptscanner.processing

import android.content.Context
import android.graphics.BitmapFactory
import com.azzam.receiptscanner.llm.LlmManager
import com.azzam.receiptscanner.model.Transfer
import com.azzam.receiptscanner.ocr.MlKitOcrHelper
import com.azzam.receiptscanner.ocr.PdfHelper
import com.azzam.receiptscanner.parser.ParsedFields
import com.azzam.receiptscanner.parser.ParserRegistry
import com.azzam.receiptscanner.storage.ApiKeyStore
import com.azzam.receiptscanner.storage.ProcessedFilesTracker
import com.azzam.receiptscanner.storage.TransferRepository
import java.io.File
import java.util.UUID

/**
 * خط المعالجة الكامل لملف واحد:
 * فلترة النوع/الحجم → OCR → فلتر المحتوى → Regex → (عند الحاجة) LLM → حفظ.
 *
 * إصلاح جوهري: أضفنا فلتر المحتوى [FileFilter.looksLikeReceipt] بعد OCR
 * لمنع معالجة الملفات غير الإيصالية (ميمز/صور شخصية/مستندات عادية).
 * كما رفعنا عتبة الحفظ: لا يُحفظ سجل إلا بـ:
 *  - مبلغ موثوق (مرتبط بعملة) > 1.0، OR
 *  - تاريخ صالح غير مستقبلي + على الأقل اسم واحد
 */
object ReceiptProcessor {

    private val registry = ParserRegistry()

    suspend fun processFile(context: Context, file: File) {
        if (!file.exists()) return

        val key = "${file.absolutePath}_${file.lastModified()}_${file.length()}"
        if (ProcessedFilesTracker.isProcessed(context, key)) return
        if (!FileFilter.isCandidateReceipt(file)) return

        // علّم كمعالَج فوراً لمنع سباق
        ProcessedFilesTracker.markProcessed(context, key)

        val ocrText = try {
            extractText(file)
        } catch (e: Exception) {
            ""
        }

        // ===== فلتر المحتوى الجوهري =====
        // لا تعالج الملف إن لم يبدُ كإيصال حوالة (كلمات مفتاحية/مبلغ/IBAN)
        if (ocrText.isBlank() || !FileFilter.looksLikeReceipt(ocrText)) {
            return // تجاهل بهدوء: ميمز/صورة شخصية/مستند عادي
        }

        var bankId = "unknown"
        var fields: ParsedFields? = null
        if (ocrText.isNotBlank()) {
            val extraction = registry.extract(ocrText)
            bankId = extraction?.first ?: "unknown"
            fields = extraction?.second
        }

        // نحتاج مساعدة LLM إذا لم نحصل على مبلغ، أو بلا أي اسم
        val needsLlmHelp = fields?.amount == null ||
            (fields.recipientName.isNullOrBlank() && fields.senderName.isNullOrBlank())

        var usedLlm = false
        var llmEngineUsed: String? = null
        if (needsLlmHelp) {
            val llmFields = tryLlmExtraction(context, ocrText)
            if (llmFields != null) {
                fields = mergeFields(fields, llmFields)
                if (bankId == "unknown") bankId = "llm_${llmFields.engineId}"
                usedLlm = true
                llmEngineUsed = llmFields.engineId
            }
        }

        // ===== عتبة الحفظ المشددة =====
        // لا تحفظ سجلاً بلا مبلغ موثوق، إلا إن كان فيه اسم + تاريخ صالح
        val hasValidAmount = fields?.amount != null && fields.amount >= 1.0
        val hasValidDate = fields?.date != null
        val hasName = !fields?.senderName.isNullOrBlank() || !fields?.recipientName.isNullOrBlank()

        val shouldSave = when {
            hasValidAmount -> true // مبلغ موثوق = يكفي للحفظ
            hasValidDate && hasName -> true // تاريخ + اسم = قد يكون حوالة فعلية
            else -> false // بدون أي معيار موثوق = تجاهل (منع false positive)
        }

        if (!shouldSave) return

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
            llmEngineUsed = llmEngineUsed
        )

        TransferRepository.addTransfer(context, transfer)
    }

    private suspend fun tryLlmExtraction(context: Context, ocrText: String):
        com.azzam.receiptscanner.llm.LlmExtractionResult? {
        val activeEngineId = ApiKeyStore.getActiveEngine(context)
        val engine = LlmManager.getEngine(activeEngineId) ?: return null
        val apiKey = ApiKeyStore.getApiKey(context, activeEngineId) ?: return null
        return engine.extract(ocrText, apiKey)
    }

    private fun mergeFields(
        original: ParsedFields?,
        llm: com.azzam.receiptscanner.llm.LlmExtractionResult
    ): ParsedFields {
        return ParsedFields(
            senderName = original?.senderName?.takeIf { it.isNotBlank() } ?: llm.senderName,
            recipientName = original?.recipientName?.takeIf { it.isNotBlank() } ?: llm.receiverName,
            amount = original?.amount ?: llm.amount,
            date = original?.date?.takeIf { it.isNotBlank() } ?: llm.date
        )
    }

    private suspend fun extractText(file: File): String {
        return if (FileFilter.isPdf(file)) {
            val pages = PdfHelper.renderPages(file)
            val texts = mutableListOf<String>()
            for (page in pages) {
                texts.add(MlKitOcrHelper.recognize(page))
            }
            texts.joinToString("\n")
        } else {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return ""
            MlKitOcrHelper.recognize(bitmap)
        }
    }
}
