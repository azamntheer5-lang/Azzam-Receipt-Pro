package com.azzam.receiptscanner

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.azzam.receiptscanner.processing.ReceiptProcessor
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * خط الدفاع الثاني: مسح دوري كل 15 دقيقة (الحد الأدنى المسموح لأندرويد
 * لمهام WorkManager الدورية). يغطي الفترات التي لا تعمل بها الخدمة
 * الفورية (خارج نافذة الـ6 ساعات على أندرويد 15+، أو إن أوقف النظام
 * الخدمة لأي سبب). كل استدعاء لـ ReceiptProcessor.processFile آمن حتى
 * لو عولج الملف مسبقاً (يتحقق بنفسه ويتجاهله).
 */
class PeriodicScanWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        ReceiptWatcherService.WHATSAPP_PATHS.forEach { path ->
            File(path).listFiles()?.forEach { file ->
                ReceiptProcessor.processFile(applicationContext, file)
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "receipt_backstop_scan"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PeriodicScanWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
