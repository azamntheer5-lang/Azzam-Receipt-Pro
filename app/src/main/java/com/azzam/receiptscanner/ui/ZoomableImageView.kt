package com.azzam.receiptscanner.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * ZoomableImageView — يدعم Pinch-to-Zoom والسحب للصور في شاشة المراجعة.
 *
 * يستخدم ScaleGestureDetector للتقريب/التصغير و Matrix للتطبيق.
 * - الحد الأدنى للتكبير: 1.0x (الصورة الطبيعية)
 * - الحد الأقصى للتكبير: 5.0x
 * - يدعم السحب (pan) بعد التكبير
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrixTransform = Matrix()
    private val lastTouchPoint = PointF()
    private var mode = NONE

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newScale = currentScale * scaleFactor
            // قيّد التكبير بين 1.0 و 5.0
            if (newScale in 1.0f..5.0f) {
                currentScale = newScale
                matrixTransform.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                imageMatrix = matrixTransform
            }
            return true
        }
    })

    private var currentScale = 1f

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchPoint.set(event.x, event.y)
                mode = DRAG
            }
            MotionEvent.ACTION_POINTER_DOWN -> mode = ZOOM
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && currentScale > 1f) {
                    val dx = event.x - lastTouchPoint.x
                    val dy = event.y - lastTouchPoint.y
                    matrixTransform.postTranslate(dx, dy)
                    imageMatrix = matrixTransform
                    lastTouchPoint.set(event.x, event.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> mode = NONE
        }
        return true
    }

    /** يعيد ضبط التكبير إلى 1.0x. */
    fun resetZoom() {
        currentScale = 1f
        matrixTransform.reset()
        imageMatrix = matrixTransform
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
