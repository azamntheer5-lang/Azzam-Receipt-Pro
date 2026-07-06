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

        var scannedCount = 0
        var savedCount = 0
        val foundPaths = mutableListOf<String>()

        for (path in allPaths) {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) continue
            foundPaths.add(path)
            try {
                val result = scanRecursive(dir, depth = 5)
                scannedCount += result.first
                savedCount += result.second
            } catch (e: SecurityException) {
                continue
            } catch (e: Exception) {
                continue
            }
        }

        // ★ تشخيص واضح للمستخدم
        val message = when {
            savedCount > 0 -> applicationContext.getString(
                R.string.scan_success_with_results, scannedCount, savedCount
            )
            scannedCount > 0 -> applicationContext.getString(
                R.string.scan_no_receipts, scannedCount
            )
            foundPaths.isEmpty() -> applicationContext.getString(R.string.scan_no_paths)
            else -> applicationContext.getString(
                R.string.scan_paths_found_no_files, foundPaths.size
            )
        }
        notifyUser(applicationContext, message)

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
     * @return Pair(scannedCount, savedCount)
     */
    private suspend fun scanRecursive(dir: File, depth: Int, currentDepth: Int = 0): Pair<Int, Int> {
        if (currentDepth > depth) return 0 to 0
        if (!dir.exists() || !dir.isDirectory) return 0 to 0

        var scanned = 0
        var saved = 0
        val children = try {
            dir.listFiles() ?: return 0 to 0
        } catch (e: SecurityException) {
            return 0 to 0
        } catch (e: Exception) {
            return 0 to 0
        }

        for (child in children) {
            try {
                if (child.isDirectory) {
                    val (s, sv) = scanRecursive(child, depth, currentDepth + 1)
                    scanned += s
                    saved += sv
                } else if (child.isFile) {
                    val wasSaved = ReceiptProcessor.processFile(applicationContext, child)
                    scanned++
                    if (wasSaved) saved++
                }
            } catch (e: Exception) {
                // تجاهل الأخطاء الفردية، تابع
            }
        }
        return scanned to saved
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
