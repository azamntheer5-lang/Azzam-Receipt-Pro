package com.azzam.receiptscanner

import android.content.Context
import android.widget.Toast
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
 * إصلاح شامل:
 *  - فحص عميق (Recursive) لكل المسارات الفرعية (عمق 5)
 *  - مسارات إضافية: Pictures, Download, DCIM, Documents, WhatsApp Business
 *  - معالجة استثناءات الوصول (يستمر عند فشل مسار محمي)
 *  - ★ تشخيص واضح: يُظهر للمستخدم المسارات المكتشفة + عدد الملفات
 */
class PeriodicScanWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // ★ أعد ضبط التشخيص قبل كل فحص
        com.azzam.receiptscanner.processing.DiagnosticLogger.reset()

        // اجمع كل المسارات المرشّحة
        val allPaths = mutableListOf<String>().apply {
            addAll(ReceiptWatcherService.WHATSAPP_PATHS)
            add("/storage/emulated/0/Pictures")
            add("/storage/emulated/0/Download")
            add("/storage/emulated/0/DCIM")
            add("/storage/emulated/0/Documents")
            add("/storage/emulated/0/WhatsApp Business/Media")
            add("/storage/emulated/0/WhatsApp/Media")
        }.distinct()

        var foundPaths = 0
        for (path in allPaths) {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) continue
            foundPaths++
            try {
                scanRecursive(dir, depth = 5)
            } catch (e: SecurityException) {
                continue
            } catch (e: Exception) {
                continue
            }
        }

        // ★ أعرض التقرير التشخيصي الكامل
        if (foundPaths == 0) {
            notifyUser(applicationContext, "❌ لم نجد أي مجلدات. اذهب للقائمة → 📁 فحص مجلد كامل واختر مجلد الصور يدوياً.")
        } else {
            val report = com.azzam.receiptscanner.processing.DiagnosticLogger.buildReport()
            notifyUser(applicationContext, report)
        }

        return Result.success()
    }

    /** يعرض Toast للمستخدم على الـ Main thread. */
    private fun notifyUser(context: Context, message: String) {
        val mainHandler = android.os.Handler(context.mainLooper)
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * فحص عميق (Recursive) للمجلدات الفرعية.
     */
    private suspend fun scanRecursive(dir: File, depth: Int, currentDepth: Int = 0) {
        if (currentDepth > depth) return
        if (!dir.exists() || !dir.isDirectory) return

        val children = try {
            dir.listFiles() ?: return
        } catch (e: SecurityException) {
            return
        } catch (e: Exception) {
            return
        }

        for (child in children) {
            try {
                if (child.isDirectory) {
                    scanRecursive(child, depth, currentDepth + 1)
                } else if (child.isFile) {
                    ReceiptProcessor.processFile(applicationContext, child)
                }
            } catch (e: Exception) {
                // تجاهل الأخطاء الفردية، تابع
            }
        }
    }

    companion object {
        private const val WORK_NAME = "receipt_backstop_scan"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PeriodicScanWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun triggerImmediateScan(context: Context) {
            val request = OneTimeWorkRequestBuilder<PeriodicScanWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
