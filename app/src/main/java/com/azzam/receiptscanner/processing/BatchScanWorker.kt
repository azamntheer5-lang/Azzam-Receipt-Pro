package com.azzam.receiptscanner.processing

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * معالج الدفعات (Batch Processing) — يمر على كل ملفات مجلد كامل.
 *
 * يستخدم Storage Access Framework (SAF) tree URI لاختيار مجلد.
 * يعمل في الخلفية عبر WorkManager، يعالج كل ملفات المجلد دفعة واحدة:
 *   قراءة ← فلترة ← OCR ← استخراج ← حفظ في Room DB
 *
 * صامت: لا نوافذ تأكيد لكل ملف. يُعلن التقدم عبر إشعار.
 */
class BatchScanWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val treeUriString = inputData.getString(KEY_TREE_URI) ?: return@withContext Result.failure()
        val treeUri = Uri.parse(treeUriString)

        try {
            val context = applicationContext
            val files = collectFilesFromTree(context, treeUri)
            val total = files.size
            var processed = 0
            var saved = 0

            for (file in files) {
                processed++
                try {
                    val wasSaved = ReceiptProcessor.processFile(context, file)
                    if (wasSaved) saved++
                } catch (e: Exception) {
                    // تجاهل الملفات المعطوبة، تابع
                }
                // إشعار التقدم كل 10 ملفات
                if (processed % 10 == 0 || processed == total) {
                    setProgressAsync(
                        androidx.work.workDataOf(
                            KEY_PROGRESS to processed,
                            KEY_TOTAL to total,
                            KEY_SAVED to saved
                        )
                    )
                }
            }

            Result.success(
                androidx.work.workDataOf(
                    KEY_PROCESSED to processed,
                    KEY_SAVED to saved,
                    KEY_TOTAL to total
                )
            )
        } catch (e: Exception) {
            Result.failure()
        }
    }

    /**
     * يجمع كل الملفات المرشّحة من شجرة SAF.
     * يحوّل كل URI إلى File مؤقت (نسخ) ليمرّ عبر ReceiptProcessor.
     */
    private fun collectFilesFromTree(context: Context, treeUri: Uri): List<File> {
        val files = mutableListOf<File>()
        val contentResolver = context.contentResolver

        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )

        contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { cursor ->
                val tempDir = File(context.cacheDir, "batch_scan").apply { mkdirs() }
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val name = docId.substringAfterLast("/")
                    val ext = name.substringAfterLast(".", "").lowercase()

                    // فلتر النوع
                    if (ext !in listOf("pdf", "jpg", "jpeg", "png", "webp")) continue

                    // انسخ الملف إلى cache (ReceiptProcessor يعمل مع File)
                    val tempFile = File(tempDir, "batch_${System.currentTimeMillis()}_$name")
                    try {
                        contentResolver.openInputStream(docUri)?.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        // فلتر الحجم
                        if (tempFile.length() in 1..(5L * 1024 * 1024)) {
                            files.add(tempFile)
                        } else {
                            tempFile.delete()
                        }
                    } catch (e: Exception) {
                        tempFile.delete()
                    }
                }
            }

        return files
    }

    companion object {
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"
        const val KEY_PROCESSED = "processed"
        const val KEY_SAVED = "saved"
        const val WORK_NAME = "batch_scan_work"
    }
}
