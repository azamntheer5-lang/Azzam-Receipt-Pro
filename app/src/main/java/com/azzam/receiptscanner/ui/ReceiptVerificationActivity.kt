package com.azzam.receiptscanner.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.azzam.receiptscanner.R
import com.azzam.receiptscanner.data.database.AppDatabase
import com.azzam.receiptscanner.data.entity.ReceiptData
import com.azzam.receiptscanner.databinding.ActivityVerificationBinding
import com.azzam.receiptscanner.parser.BankMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * شاشة المراجعة والمطابقة (Side-by-Side Verification).
 *
 * النصف العلوي: عرض الملف الأصلي (صورة/PDF) مع Pinch-to-Zoom
 * النصف السفلي: حقول قابلة للتعديل + زر حفظ + زر حذف
 *
 * الحفظ يحدّث السجل مباشرة في Room DB.
 */
class ReceiptVerificationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVerificationBinding
    private var receipt: ReceiptData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.verificationToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.verification_title)
        binding.verificationToolbar.setNavigationOnClickListener { finish() }

        val receiptId = intent.getStringExtra(EXTRA_RECEIPT_ID) ?: run {
            finish(); return
        }

        // حمّل السجل من Room
        lifecycleScope.launch {
            receipt = AppDatabase.get(this@ReceiptVerificationActivity).receiptDao().getById(receiptId)
            receipt?.let { render(it) }
        }

        binding.buttonSave.setOnClickListener { saveChanges() }
        binding.buttonDelete.setOnClickListener { deleteReceipt() }
        binding.buttonResetZoom.setOnClickListener { binding.imagePreview.resetZoom() }
    }

    private fun render(r: ReceiptData) {
        // املأ الحقول
        binding.editSender.setText(r.senderName.orEmpty())
        binding.editRecipient.setText(r.recipientName.orEmpty())
        binding.editAmount.setText(r.amount?.toString().orEmpty())
        binding.editBank.setText(BankMatcher.beautifyBankName(r.bankId))
        binding.editDate.setText(r.date.orEmpty())
        binding.textRawOcr.text = r.rawText.ifBlank { getString(R.string.edit_raw_text_empty) }

        // اعرض الملف الأصلي في الخلفية
        loadPreview(r.originalFilePath)
    }

    /** يحمّل الصورة أو PDF ويعرضها في ZoomableImageView. */
    private fun loadPreview(filePath: String?) {
        if (filePath.isNullOrBlank()) {
            showPreviewError(getString(R.string.verification_no_path))
            return
        }

        binding.previewProgress.visibility = View.VISIBLE
        binding.previewError.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    when {
                        // URI (content://)
                        filePath.startsWith("content://") -> {
                            val uri = Uri.parse(filePath)
                            contentResolver.openInputStream(uri)?.use { input ->
                                BitmapFactory.decodeStream(input)
                            }
                        }
                        // PDF
                        filePath.lowercase().endsWith(".pdf") -> {
                            renderPdfFirstPage(File(filePath))
                        }
                        // ملف صورة عادي
                        else -> {
                            BitmapFactory.decodeFile(filePath)
                        }
                    }
                }

                if (bitmap != null) {
                    binding.imagePreview.setImageBitmap(bitmap)
                    binding.previewProgress.visibility = View.GONE
                } else {
                    showPreviewError(getString(R.string.verification_load_failed))
                }
            } catch (e: Exception) {
                showPreviewError(getString(R.string.verification_load_failed))
            }
        }
    }

    /** يعرض الصفحة الأولى من PDF كصورة. */
    private fun renderPdfFirstPage(file: File): android.graphics.Bitmap? {
        if (!file.exists()) return null
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(pfd).use { renderer ->
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { page ->
                val bitmap = android.graphics.Bitmap.createBitmap(
                    page.width * 2, page.height * 2, android.graphics.Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pfd.close()
                return bitmap
            }
        }
    }

    private fun showPreviewError(message: String) {
        binding.previewProgress.visibility = View.GONE
        binding.imagePreview.visibility = View.GONE
        binding.previewError.visibility = View.VISIBLE
        binding.textPreviewError.text = message
    }

    private fun saveChanges() {
        val r = receipt ?: return
        val sender = binding.editSender.text.toString().trim().ifBlank { null }
        val recipient = binding.editRecipient.text.toString().trim().ifBlank { null }
        val amount = binding.editAmount.text.toString().trim().toDoubleOrNull()
        val bankDisplay = binding.editBank.text.toString().trim()
        val date = binding.editDate.text.toString().trim().ifBlank { null }

        // حوّل اسم البنك الجميل لـ canonicalId عبر BankMatcher
        val bankId = BankMatcher.match(bankDisplay)?.first
            ?: BankMatcher.normalizeBankId(r.bankId)

        val updated = r.copy(
            senderName = sender,
            recipientName = recipient,
            amount = amount,
            date = date,
            bankId = bankId,
            confidence = 1.0f // تم التحقق يدوياً
        )

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.get(this@ReceiptVerificationActivity).receiptDao().upsert(updated)
            }
            Toast.makeText(this@ReceiptVerificationActivity, R.string.verification_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun deleteReceipt() {
        val r = receipt ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.verification_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.get(this@ReceiptVerificationActivity).receiptDao().delete(r.id)
                    }
                    Toast.makeText(this@ReceiptVerificationActivity, R.string.verification_deleted, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_RECEIPT_ID = "extra_receipt_id"
    }
}
