package com.azzam.receiptscanner.processing

import android.content.Context
import com.azzam.receiptscanner.ReceiptWatcherService
import java.io.File

/**
 * ماسح فوري — يعمل في lifecycleScope مباشرة (ليس WorkManager).
 *
 * هذا يضمن أن الفحص يبدأ فوراً عند استدعائه ويرى المستخدم النتائج.
 */
object ImmediateScanner {

    /**
     * يفحص كل المسارات المرشّحة بشكل عميق ويعيد تقرير DiagnosticLogger.
     * يعمل في coroutine خلفية لكن يُستدعى من lifecycleScope.
     */
    suspend fun scanNow(context: Context): String {
        DiagnosticLogger.reset()

        val allPaths = mutableListOf<String>().apply {
            addAll(ReceiptWatcherService.WHATSAPP_PATHS)
            add("/storage/emulated/0/Pictures")
            add("/storage/emulated/0/Download")
            add("/storage/emulated/0/DCIM")
            add("/storage/emulated/0/Documents")
            add("/storage/emulated/0/WhatsApp Business/Media")
            add("/storage/emulated/0/WhatsApp/Media")
        }.distinct()

        for (path in allPaths) {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) continue
            try {
                scanRecursive(context, dir, depth = 5)
            } catch (e: SecurityException) {
                continue
            } catch (e: Exception) {
                continue
            }
        }

        return DiagnosticLogger.buildReport()
    }

    private suspend fun scanRecursive(context: Context, dir: File, depth: Int, currentDepth: Int = 0) {
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
                    scanRecursive(context, child, depth, currentDepth + 1)
                } else if (child.isFile) {
                    ReceiptProcessor.processFile(context, child)
                }
            } catch (e: Exception) {
                // تجاهل الأخطاء الفردية
            }
        }
    }
}
