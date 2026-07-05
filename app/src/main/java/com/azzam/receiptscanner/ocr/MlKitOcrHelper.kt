package com.azzam.receiptscanner.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * تنبيه مهم: ML Kit's Text Recognition (الإصدار المستخدم هنا) يقرأ فقط
 * السكربت اللاتيني/الإنجليزي على الجهاز - لا يقرأ العربية. سيستخرج الأرقام
 * والمبالغ والتواريخ بشكل موثوق غالباً، لكن الأسماء العربية قد لا تُقرأ.
 * راجع README للخيارات الأذكى (Gemini Nano أو LLM سحابي) كخطوة تالية.
 */
object MlKitOcrHelper {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result -> cont.resume(result.text) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }
}
