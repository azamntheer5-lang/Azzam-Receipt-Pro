package com.azzam.receiptscanner.processing

import android.content.Context
import com.azzam.receiptscanner.ReceiptWatcherService
import java.io.File

/**
 * ماسح مباشر — يعمل في coroutine مباشرة (وليس WorkManager).
 *
 * السبب الجذري للمشكلة: WorkManager يؤجل التنفيذ لثوانٍ/دقائق.
 * هذا الماسح يعمل فوراً عند الاستدعاء ويعيد النتيجة مباشرة.
 */
object DirectScanner {

    /**
     * يفحص كل المسارات المرشّحة بشكل عميق ويعيد تقريراً نصياً.
     * يعمل في coroutine خلفية لكن يُستدعى من lifecycleScope.
     */
    suspend fun scanNow(context: Context): ScanResult {
        var scanned = 0
        var saved = 0
        var foundFolders = 0
        val errors = mutableListOf<String>()

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
            foundFolders++
            try {
                val (s, sv) = scanRecursive(context, dir, depth = 5)
                scanned += s
                saved += sv
            } catch (e: SecurityException) {
                errors.add("لا صلاحية: $path")
            } catch (e: Exception) {
                errors.add("خطأ: ${e.message?.take(40)}")
            }
        }

        return ScanResult(scanned, saved, foundFolders, errors)
    }

    private suspend fun scanRecursive(
        context: Context, dir: File, depth: Int, currentDepth: Int = 0
    ): Pair<Int, Int> {
        if (currentDepth > depth) return 0 to 0
        if (!dir.exists() || !dir.isDirectory) return 0 to 0

        val children = try {
            dir.listFiles() ?: return 0 to 0
        } catch (e: Exception) {
            return 0 to 0
        }

        var scanned = 0
        var saved = 0
        for (child in children) {
            try {
                if (child.isDirectory) {
                    val (s, sv) = scanRecursive(context, child, depth, currentDepth + 1)
                    scanned += s
                    saved += sv
                } else if (child.isFile) {
                    val wasSaved = ReceiptProcessor.processFile(context, child)
                    scanned++
                    if (wasSaved) saved++
                }
            } catch (e: Exception) {
                // تجاهل الأخطاء الفردية
            }
        }
        return scanned to saved
    }

    data class ScanResult(
        val scanned: Int,
        val saved: Int,
        val foundFolders: Int,
        val errors: List<String>
    ) {
        fun toReport(): String {
            val sb = StringBuilder()
            sb.append("📊 نتيجة الفحص:\n\n")
            sb.append("• المجلدات المكتشفة: $foundFolders\n")
            sb.append("• الملفات المفحوصة: $scanned\n")
            sb.append("• الإيصالات المحفوظة: $saved\n")
            if (errors.isNotEmpty()) {
                sb.append("\n⚠️ أخطاء:\n")
                errors.take(5).forEach { sb.append("• $it\n") }
            }
            if (saved == 0 && scanned == 0 && foundFolders == 0) {
                sb.append("\n❌ لم نجد مجلدات. استخدم 'فحص مجلد كامل' من القائمة.")
            } else if (saved == 0 && scanned > 0) {
                sb.append("\n💡 فحصنا $scanned ملف لكن لم نجد إيصالات حوالة صالحة.")
            }
            return sb.toString().trim()
        }
    }
}
