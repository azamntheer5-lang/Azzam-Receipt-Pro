package com.azzam.receiptscanner.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt

/**
 * كاشف ومصحّح ميل النص (Skew Detection & Rotation Correction).
 *
 * يستخدم خوارزمية Projection Profile:
 *  1. يحوّل الصورة لتدرج رمادي
 *  2. لكل زاوية مرشّحة (-15° إلى +15°)، يدوّر الصورة ويحسب تباين المجموع الأفقي
 *  3. الزاوية بأعلى تباين = زاوية الميل الصحيحة (النص يكون مستقيماً)
 *  4. يدوّر الصورة بالعكس لتصحيح الميل
 *
 * هذا يرفع دقة ML Kit بشكل هائل على الإيصالات المائلة.
 */
object DeskewDetector {

    private const val ANGLE_RANGE = 15 // درجة
    private const val ANGLE_STEP = 1 // درجة (دقة 1°)
    private const val DOWNSCALE_WIDTH = 200 // تقليل الأبعاد لتسريع الحساب

    /**
     * يكتشف زاوية الميل ويعيد Bitmap مُصحّحاً.
     * المتصل مسؤول عن recycle النتيجة.
     */
    fun deskew(bitmap: Bitmap): Bitmap {
        val angle = detectSkewAngle(bitmap)
        if (abs(angle) < 0.5f) return bitmap // زاوية ضئيلة جداً — لا تصحيح

        val matrix = Matrix().apply { postRotate(angle) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * يكتشف زاوية الميل عبر Projection Profile.
     * يعيد الزاوية بالدرجات (سالبة = يدور عكس عقارب الساعة لتصحيح).
     */
    private fun detectSkewAngle(bitmap: Bitmap): Float {
        // 1. صغّر الصورة لتسريع الحساب
        val scaled = downscale(bitmap)
        val width = scaled.width
        val height = scaled.height

        // 2. حوّل لتدرج رمادي (0-255)
        val gray = toGrayscaleArray(scaled, width, height)

        // 3. جرّب زوايا مرشّحة وابحث عن الأعلى تباين
        var bestAngle = 0f
        var bestVariance = 0.0

        for (angle in -ANGLE_RANGE..ANGLE_RANGE step ANGLE_STEP) {
            val variance = computeProjectionVariance(gray, width, height, angle.toFloat())
            if (variance > bestVariance) {
                bestVariance = variance
                bestAngle = angle.toFloat()
            }
        }

        return bestAngle
    }

    /** يقلّص الأبعاد إلى حد معقول لتسريع الحساب. */
    private fun downscale(bitmap: Bitmap): Bitmap {
        val scale = DOWNSCALE_WIDTH.toFloat() / bitmap.width
        if (scale >= 1f) return bitmap
        val newHeight = (bitmap.height * scale).roundToInt()
        val scaled = Bitmap.createScaledBitmap(bitmap, DOWNSCALE_WIDTH, newHeight, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** يحوّل Bitmap لـ Array رمادي. */
    private fun toGrayscaleArray(bitmap: Bitmap, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = IntArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toInt()
        }
        return gray
    }

    /**
     * يحسب تباين الـ Projection Profile الأفقي عند زاوية معيّنة.
     * الزاوية الصحيحة تعطي أعلى تباين (خطوط نص أوضح).
     */
    private fun computeProjectionVariance(
        gray: IntArray, width: Int, height: Int, angleDeg: Float
    ): Double {
        val angleRad = Math.toRadians(angleDeg.toDouble())
        val cos = kotlin.math.cos(angleRad)
        val sin = kotlin.math.sin(angleRad)

        // احسب المجموع الأفقي لكل صف (مع إزاحة بزاوية)
        val rowSums = IntArray(height)
        for (y in 0 until height) {
            var sum = 0
            for (x in 0 until width) {
                // إحداثيات جديدة بعد الدوران
                val newX = x * cos - y * sin
                val srcX = newX.toInt().coerceIn(0, width - 1)
                if (srcX in 0 until width) {
                    sum += gray[y * width + srcX]
                }
            }
            rowSums[y] = sum
        }

        // احسب التباين (variance) — نستخدم الانحراف المعياري المبسّط
        val mean = rowSums.average()
        var variance = 0.0
        for (s in rowSums) {
            val diff = s - mean
            variance += diff * diff
        }
        return variance / height
    }
}
