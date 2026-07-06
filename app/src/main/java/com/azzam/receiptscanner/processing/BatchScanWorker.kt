package com.azzam.receiptscanner.processing

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * معالج الدفعات (Batch Processing) — يمر على كل ملفات مجلد كامل.
 *
 * إصلاح جوهري:
 *  - دعم واتساب الأعمال (WhatsApp Business) عبر DocumentFile
 *  - فحص عميق (Recursive Traversal) للمجلدات الفرعية
 *  - معالجة استثناءات Scoped Storage مع رسائل واضحة للمستخدم
 *  - تنظيف الملفات المؤقتة بعد كل ملف
 */
class BatchScanWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val treeUriString = inputData.getString(KEY_TREE_URI) ?: return@withContext Result.failure()
        val treeUri = Uri.parse(treeUriString)

        try {
            val context = applicationContext
            val files = collectFilesRecursive(context, treeUri)
            val total = files.size

            // إن لم نجد ملفات، أبلغ المستخدم باحتمال قيود Scoped Storage
            if (files.isEmpty()) {
                notifyUser(context, context.getString(com.azzam.receiptscanner.R.string.batch_no_files))
                return@withContext Result.success(
                    androidx.work.workDataOf(KEY_PROCESSED to 0, KEY_SAVED to 0, KEY_TOTAL to 0)
                )
            }

            var processed = 0
            var saved = 0

            for (file in files) {
                processed++
                try {
                    val wasSaved = ReceiptProcessor.processFile(context, file)
                    if (wasSaved) saved++
                } catch (e: Exception) {
                    // تجاهل الملفات المعطوبة، تابع
                } finally {
                    // ★ تخلّص من الملف المؤقت دائماً
                    try { file.delete() } catch (_: Exception) {}
                }
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

            // تنظيف المجلد المؤقت
            try {
                File(context.cacheDir, "batch_scan").deleteRecursively()
            } catch (_: Exception) {}

            Result.success(
                androidx.work.workDataOf(
                    KEY_PROCESSED to processed,
                    KEY_SAVED to saved,
                    KEY_TOTAL to total
                )
            )
        } catch (e: SecurityException) {
            // قيود Scoped Storage — أبلغ المستخدم
            notifyUser(
                applicationContext,
                applicationContext.getString(com.azzam.receiptscanner.R.string.batch_scoped_storage_error)
            )
            Result.failure()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    /**
     * ★ فحص عميق (Recursive) عبر DocumentFile — يغوص في كل المجلدات الفرعية.
     *
     * يستخدم DocumentFile.fromTreeUri + listFiles() بشكل تكراري للعثور على
     * كل الصور وملفات PDF في الشجرة كاملةً، وليس فقط السطح.
     *
     * هذا ضروري لأن المستخدم قد يختار المجلد الجذري لواتساب الأعمال،
     * والصور تكون في مجلدات فرعية عميقة (Media/WhatsApp Business Images/...).
     */
    private fun collectFilesRecursive(context: Context, treeUri: Uri): List<File> {
        val files = mutableListOf<File>()
        val tempDir = File(context.cacheDir, "batch_scan").apply { mkdirs() }

        try {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
            traverseRecursive(context, root, files, tempDir, depth = 0)
        } catch (e: SecurityException) {
            // لا صلاحية للوصول — أعد قائمة فارغة (سيتعامل doWork معها)
            return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }

        return files
    }

    /**
     * دالة تكرارية (recursive) تمرّ على كل الملفات والمجلدات الفرعية.
     * حد أقصى للعمق = 10 لتجنب التكرار اللانهائي.
     */
    private fun traverseRecursive(
        context: Context,
        dir: DocumentFile,
        files: MutableList<File>,
        tempDir: File,
        depth: Int
    ) {
        if (depth > 10) return // حد أمان للعمق

        val children = try {
            dir.listFiles()
        } catch (e: SecurityException) {
            return // لا صلاحية لهذا المجلد — تخطّاه
        } catch (e: Exception) {
            return
        }

        for (child in children) {
            if (child.isDirectory) {
                // غص في المجلد الفرعي
                traverseRecursive(context, child, files, tempDir, depth + 1)
            } else if (child.isFile) {
                // فلتر النوع
                val name = child.name ?: continue
                val ext = name.substringAfterLast(".", "").lowercase()
                if (ext !in listOf("pdf", "jpg", "jpeg", "png", "webp")) continue

                // انسخ الملف إلى cache (ReceiptProcessor يعمل مع File)
                val tempFile = File(tempDir, "batch_${System.currentTimeMillis()}_$name")
                try {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
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
                } catch (e: SecurityException) {
                    tempFile.delete()
                    // تخطّى هذا الملف — لا صلاحية
                } catch (e: Exception) {
                    tempFile.delete()
                }
            }
        }
    }

    /** يعرض Toast للمستخدم (يتم تنفيذه على الـ Main thread). */
    private fun notifyUser(context: Context, message: String) {
        val mainHandler = android.os.Handler(context.mainLooper)
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
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
