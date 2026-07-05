package com.azzam.receiptscanner.processing

import java.io.File

object FileFilter {
    const val MAX_PDF_SIZE_BYTES = 1L * 1024 * 1024   // 1 ميجا
    const val MAX_IMAGE_SIZE_BYTES = 5L * 1024 * 1024  // 5 ميجا

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    /** يُطبّق فلتر النوع والحجم بالضبط كما طُلب: PDF أكبر من 1 ميجا يُتجاهل،
     *  وصور أكبر من 5 ميجا تُتجاهل. أي امتداد آخر غير صورة/PDF يُتجاهل دائماً. */
    fun isCandidateReceipt(file: File): Boolean {
        if (!file.isFile) return false
        val ext = file.extension.lowercase()
        return when {
            ext == "pdf" -> file.length() in 1..MAX_PDF_SIZE_BYTES
            ext in imageExtensions -> file.length() in 1..MAX_IMAGE_SIZE_BYTES
            else -> false
        }
    }

    fun isPdf(file: File) = file.extension.lowercase() == "pdf"
}
