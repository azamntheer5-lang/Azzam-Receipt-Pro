package com.azzam.receiptscanner

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.azzam.receiptscanner.processing.ReceiptProcessor
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * خط الدفاع الثاني: مسح دوري + فوري عند الطلب.
 *
 * إصلاح جوهري:
 *  - فحص عميق (Recursive) لكل المسارات الفرعية
 *  - مسارات إضافية: Pictures, Download, DCIM, ومجلدات واتساب الأعمال البديلة
 *  - معالجة استثناءات الوصول (يستمر عند فشل مسار محمي)
 *  - كل استدعاء لـ ReceiptProcessor.processFile آمن (idempotent)
 */
class PeriodicScanWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // اجمع كل المسارات المرشّحة
        val allPaths = mutableListOf<String>().apply {
            addAll(ReceiptWatcherService.WHATSAPP_PATHS)
            // مسارات إضافية شائعة للصور/المستندات
            add("/storage/emulated/0/Pictures")
            add("/storage/emulated/0/Download")
            add("/storage/emulated/0/DCIM")
            add("/storage/emulated/0/Documents")
            // مسارات بديلة لواتساب الأعمال
            add("/storage/emulated/0/WhatsApp Business/Media")
            add("/storage/emulated/0/WhatsApp/Media")
        }.distinct()

        var scannedCount = 0
        for (path in allPaths) {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) continue
            try {
                scannedCount += scanRecursive(dir, depth = 5)
            } catch (e: SecurityException) {
                continue
            } catch (e: Exception) {
                continue
            }
        }

        // ★ أبلغ المستخدم بالنتيجة
        if (scannedCount > 0) {
            notifyUser(applicationContext, applicationContext.getString(com.azzam.receiptscanner.R.string.scan_complete_count, scannedCount))
        }

        return Result.success()
    }

    /** يعرض Toast للمستخدم على الـ Main thread. */
    private fun notifyUser(context: Context, message: String) {
        val mainHandler = android.os.Handler(context.mainLooper)
        mainHandler.post {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * فحص عميق (Recursive) للمجلدات الفرعية.
     * @return عدد الملفات المعالَجة
     */
    private suspend fun scanRecursive(dir: File, depth: Int, currentDepth: Int = 0): Int {
        if (currentDepth > depth) return 0
        if (!dir.exists() || !dir.isDirectory) return 0

        var count = 0
        val children = try {
            dir.listFiles() ?: return 0
        } catch (e: SecurityException) {
            return 0
        } catch (e: Exception) {
            return 0
        }

        for (child in children) {
            try {
                if (child.isDirectory) {
                    // غص في المجلد الفرعي
                    count += scanRecursive(child, depth, currentDepth + 1)
                } else if (child.isFile) {
                    // عالج الملف (آمن إن كان معالَجاً مسبقاً)
                    ReceiptProcessor.processFile(applicationContext, child)
                    count++
                }
            } catch (e: Exception) {
                // تجاهل الأخطاء الفردية، تابع
            }
        }
        return count
    }

    companion object {
        private const val WORK_NAME = "receipt_backstop_scan"

        /** يجدول الفحص الدوري كل 15 دقيقة. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PeriodicScanWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        /** ★ يطلق فحصاً فورياً (OneTime) — يُستدعى عند بدء التطبيق. */
        fun triggerImmediateScan(context: Context) {
            val request = OneTimeWorkRequestBuilder<PeriodicScanWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
