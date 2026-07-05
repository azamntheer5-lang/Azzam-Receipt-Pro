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
 * فلترة -> OCR محلي -> Regex -> (عند الحاجة فقط) Multi-LLM كطبقة ثانية -> حفظ.
 *
 * التطوير (المرحلة 2): يستبدل CloudExtractor (محرك Claude وحيد) بنظام
 * Multi-LLM عبر [LlmManager]. المحرك النشط ومفتاحه يُحدّدان من الإعدادات،
 * والـ System Prompt الموحّد يُطبّق على جميع المحركات.
 *
 * قابل للاستدعاء بأمان من مصادر متعددة (الخدمة الفورية، المسح الدوري،
 * زر "مسح الآن") لأن كل خطوة idempotent.
 */
object ReceiptProcessor {

    private val registry = ParserRegistry()

    suspend fun processFile(context: Context, file: File) {
        if (!file.exists()) return

        val key = "${file.absolutePath}_${file.lastModified()}_${file.length()}"
        if (ProcessedFilesTracker.isProcessed(context, key)) return
        if (!FileFilter.isCandidateReceipt(file)) return

        // علّم كمعالَج فوراً لمنع سباق (race) بين المسح الفوري والدوري لنفس الملف
        ProcessedFilesTracker.markProcessed(context, key)

        val ocrText = try {
            extractText(file)
        } catch (e: Exception) {
            "" // فشل القراءة — أكمل، فقد يظل الاستخراج السحابي ممكناً
        }

        var bankId = "unknown"
        var fields: ParsedFields? = null
        if (ocrText.isNotBlank()) {
            val extraction = registry.extract(ocrText)
            bankId = extraction?.first ?: "unknown"
            fields = extraction?.second
        }

        // نحتاج مساعدة LLM إذا لم نحصل على مبلغ، أو حصلنا على مبلغ لكن
        // بلا أي اسم مرسل/مستلم (الحالة الأكثر شيوعاً مع الإيصالات العربية
        // التي لا يقرأها ML Kit بشكل صحيح)
        val needsLlmHelp = fields?.amount == null ||
            (fields.recipientName.isNullOrBlank() && fields.senderName.isNullOrBlank())

        var usedLlm = false
        var llmEngineUsed: String? = null
        if (needsLlmHelp && ocrText.isNotBlank()) {
            val llmFields = tryLlmExtraction(context, ocrText)
            if (llmFields != null) {
                fields = mergeFields(fields, llmFields)
                if (bankId == "unknown") bankId = "llm_${llmFields.engineId}"
                usedLlm = true
                llmEngineUsed = llmFields.engineId
            }
        }

        val transfer = Transfer(
            id = UUID.randomUUID().toString(),
            senderName = fields?.senderName,
            recipientName = fields?.recipientName,
            amount = fields?.amount,
            date = fields?.date,
            bankId = bankId,
            confidence = when {
                fields?.amount != null && usedLlm -> 0.9f
                fields?.amount != null -> 0.7f
                else -> 0.2f
            },
            sourceFileName = file.name,
            processedAt = System.currentTimeMillis(),
            rawText = ocrText.take(500),
            llmEngineUsed = llmEngineUsed
        )

        // احفظ فقط إذا استخرجنا شيئاً مفيداً على الأقل (مبلغ أو تاريخ)
        if (transfer.amount != null || transfer.date != null) {
            TransferRepository.addTransfer(context, transfer)
        }
    }

    /**
     * يستدعي المحرك النشط لاستخراج البيانات من نص OCR الخام.
     * يعيد null إذا لم يُضبط مفتاح للمحرك النشط أو فشل الاستدعاء.
     */
    private suspend fun tryLlmExtraction(context: Context, ocrText: String):
        com.azzam.receiptscanner.llm.LlmExtractionResult? {
        val activeEngineId = ApiKeyStore.getActiveEngine(context)
        val engine = LlmManager.getEngine(activeEngineId) ?: return null
        val apiKey = ApiKeyStore.getApiKey(context, activeEngineId) ?: return null
        return engine.extract(ocrText, apiKey)
    }

    /**
     * يفضّل قيم Regex المحلية عند توفرها (أسرع/بلا تكلفة)، ويملأ الفراغات
     * من نتيجة LLM. يحوّل تسمية receiver_name إلى recipientName المتّبعة محلياً.
     */
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
