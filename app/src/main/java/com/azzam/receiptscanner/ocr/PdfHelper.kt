package com.azzam.receiptscanner.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * يحوّل صفحات PDF إلى Bitmaps بدقة مضاعفة لتحسين الـ OCR.
 *
 * تحسين: البقائيّة (memory safety) — كل Bitmap تُعاد للمتصل ليُدمرها بنفسه
 * (recycle) بعد الاستخدام، لتفادي Memory Leaks عند معالجة PDFs متعددة الصفحات.
 */
object PdfHelper {

    /** يحوّل كل صفحة PDF إلى Bitmap بدقة x2. المتصل مسؤول عن recycle. */
    fun renderPages(file: File): List<Bitmap> {
        val bitmaps = mutableListOf<Bitmap>()
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

        PdfRenderer(pfd).use { renderer ->
            for (i in 0 until renderer.pageCount) {
                renderer.openPage(i).use { page ->
                    val bitmap = Bitmap.createBitmap(
                        page.width * 2, page.height * 2, Bitmap.Config.RGB_565
                    )
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmaps.add(bitmap)
                }
            }
        }
        return bitmaps
    }
}
