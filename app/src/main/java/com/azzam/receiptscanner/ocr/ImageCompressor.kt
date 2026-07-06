package com.azzam.receiptscanner.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ضاغط الصور الذكي — يقلّل حجم الصور قبل الـ OCR لتسريع المعالجة وتقليل
 * استهلاك الذاكرة والبيانات.
 *
 * الاستراتيجية:
 *  1. فك تشفير الصورة بأبعاد مخفّضة (inSampleSize) إن كانت كبيرة
 *  2. إعادة ضبط الأبعاد إلى حد أقصى 1280px (كافٍ لـ ML Kit بدقة عالية)
 *  3. ضغط JPEG بجودة 85% (توازن بين الحجم والدقة)
 *
 * النتيجة: صورة 5MB → ~150KB، أسرع 5x في الـ OCR.
 */
object ImageCompressor {

    private const val MAX_DIMENSION = 1280
    private const val JPEG_QUALITY = 85

    /**
     * يحوّل ملف صورة إلى Bitmap مضغوط بأبعاد مخفّضة.
     * يعيد null إن فشل فك التشفير.
     */
    fun decodeSampled(file: File): Bitmap? {
        // المرحلة 1: اقرأ الأبعاد فقط (bounds)
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, boundsOptions)

        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight
        if (width <= 0 || height <= 0) return null

        // احسب inSampleSize للوصول إلى ~MAX_DIMENSION
        var sampleSize = 1
        val maxSide = maxOf(width, height)
        while (maxSide / sampleSize > MAX_DIMENSION * 2) {
            sampleSize *= 2
        }

        // المرحلة 2: فك التشفير الفعلي بالأبعاد المخفّضة
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565  // يوفر 50% من الذاكرة vs ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null

        // المرحلة 3: إعادة ضبط الأبعاد إن لزم
        return scaleIfNeeded(bitmap)
    }

    /** يقلّص Bitmap إن تجاوز الحد الأقصى للأبعاد. */
    private fun scaleIfNeeded(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxSide = maxOf(width, height)

        if (maxSide <= MAX_DIMENSION) return bitmap

        val scale = MAX_DIMENSION.toFloat() / maxSide
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        // تخلّص من الأصلية إن اختلفا
        if (scaled !== bitmap) {
            bitmap.recycle()
        }
        return scaled
    }

    /**
     * يحوّل Bitmap إلى File مضغوط (JPEG 85%) للمعالجة المؤقتة.
     * مفيد للـ BatchScanWorker الذي ينسخ ملفات كثيرة.
     */
    fun compressToCache(bitmap: Bitmap, cacheDir: File, name: String): File? {
        val outFile = File(cacheDir, "compressed_$name.jpg")
        return try {
            ByteArrayOutputStream().use { baos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                outFile.writeBytes(baos.toByteArray())
            }
            outFile
        } catch (e: Exception) {
            null
        }
    }
}
