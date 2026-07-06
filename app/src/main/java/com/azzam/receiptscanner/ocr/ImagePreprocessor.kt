package com.azzam.receiptscanner.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.azzam.receiptscanner.ocr.ImageCompressor.decodeSampled
import java.io.File

/**
 * معالج الصور المتقدّم (Advanced Image Pre-processing) لتحسين دقة OCR.
 *
 * يطبّق سلسلة فلاتر على الصورة قبل إرسالها لـ ML Kit:
 *  1. تحويل لتدرج رمادي (Grayscale) — يقلّل الضوضاء اللونية
 *  2. زيادة التباين (Contrast Enhancement) — يوضّح النصوص الباهتة
 *  3. ثنائية (Binarization/Otsu) — يحوّل الصورة لأبيض/أسود نقي
 *
 * هذا ضروري لإيصالات واتساب الرديئة (مضغوطة/باهتة/منخفضة الدقة).
 *
 * الاستراتيجية: Otsu's method يحسب عتبة (threshold) مثلى تلقائياً من
 * histogram الصورة، فيفصل النص عن الخلفية بكفاءة عالية حتى مع الإضاءة المتغيرة.
 */
object ImagePreprocessor {

    /**
     * يعالج ملف صورة ويُرجع Bitmap معالَجاً (رمادي + تباين + binarization).
     * المتصل مسؤول عن bitmap.recycle() بعد الاستخدام.
     */
    fun preprocess(file: File): Bitmap? {
        val original = decodeSampled(file) ?: return null
        return try {
            preprocessBitmap(original)
        } finally {
            if (original !== original) { /* no-op — preprocessBitmap يدمر الأصلية إن لزم */ }
        }
    }

    /**
     * يعالج Bitmap مباشرة.
     * يُرجع Bitmap جديد معالَجاً ويدمّر الأصلية.
     */
    fun preprocessBitmap(original: Bitmap): Bitmap {
        // المرحلة 1: تدرج رمادي + زيادة تباين (مدمجة في ColorMatrix واحدة)
        val enhanced = applyGrayscaleAndContrast(original, contrast = 1.4f, brightness = 10f)
        if (enhanced !== original) {
            original.recycle()
        }

        // المرحلة 2: Binarization بطريقة Otsu
        val binarized = applyOtsuBinarization(enhanced)
        enhanced.recycle()

        return binarized
    }

    /**
     * يطبّق تدرج رمادي + تباين في خطوة واحدة عبر ColorMatrix.
     * contrast=1.0 = لا تغيير، >1.0 = تباين أعلى.
     * brightness بوحدات [-255, 255].
     */
    private fun applyGrayscaleAndContrast(src: Bitmap, contrast: Float, brightness: Float): Bitmap {
        val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.RGB_565)
        val canvas = Canvas(output)

        // مصفوفة: تدرج رمادي (BT.709) + تباين + سطوع
        val cm = ColorMatrix()
        cm.setSaturation(0f) // تدرج رمادي

        // تباين: scale around 0.5
        val scale = contrast
        val translate = (-0.5f * scale + 0.5f) * 255f + brightness
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(contrastMatrix)

        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    /**
     * ثنائية Otsu (Binarization) — يحسب عتبة مثلى من histogram الصورة.
     * البكسلات فوق العتبة → أبيض، تحتها → أسود.
     */
    private fun applyOtsuBinarization(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1. حوّل لرمادي واحسب histogram
        val gray = IntArray(pixels.size)
        val histogram = IntArray(256)
        for (i in pixels.indices) {
            val p = pixels[i]
            // BT.709 luminance
            val g = (0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)).toInt()
                .coerceIn(0, 255)
            gray[i] = g
            histogram[g]++
        }

        // 2. احسب عتبة Otsu
        val threshold = otsuThreshold(histogram, pixels.size)

        // 3. طبّق العتبة
        val outPixels = IntArray(pixels.size)
        val white = -1 // 0xFFFFFFFF
        val black = -16777216 // 0xFF000000
        for (i in gray.indices) {
            outPixels[i] = if (gray[i] > threshold) white else black
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * حساب عتبة Otsu المثلى — يفصل النص عن الخلفة تلقائياً.
     * يعتمد على تقليل التباين داخل الصف (within-class variance).
     */
    private fun otsuThreshold(histogram: IntArray, total: Int): Int {
        var sum = 0
        for (i in 0..255) sum += i * histogram[i]

        var sumB = 0
        var wB = 0
        var maxVariance = 0.0
        var threshold = 127

        for (t in 0..255) {
            wB += histogram[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break

            sumB += t * histogram[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF

            val variance = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (variance > maxVariance) {
                maxVariance = variance
                threshold = t
            }
        }
        return threshold
    }
}
