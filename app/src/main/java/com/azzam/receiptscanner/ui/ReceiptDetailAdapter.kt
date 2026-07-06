package com.azzam.receiptscanner.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.azzam.receiptscanner.data.entity.ReceiptData
import com.azzam.receiptscanner.databinding.ItemTransferBinding
import java.text.NumberFormat
import java.util.Locale

/**
 * محوّل قائمة الحوالات في شاشة تفاصيل كشف الحساب.
 * يعيد استخدام تخطيط item_transfer.xml (بطاقة الحوالة) لاتساق الواجهة.
 *
 * يضيف لمسة: تمييز الدور (مرسِل/مستلِم) عبر لون المبلغ، لأن المستخدم قد
 * يرسل ويستلم في نفس الوقت — هذا يساعد على التمييز السريع.
 */
class ReceiptDetailAdapter(
    private val highlightName: String?
) : ListAdapter<ReceiptData, ReceiptDetailAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(val binding: ItemTransferBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTransferBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val formatter = NumberFormat.getCurrencyInstance(Locale("ar", "SA"))

        holder.binding.apply {
            textAmount.text = item.amount?.let { formatter.format(it) } ?: "—"
            // ميزة الدور: إن كان الشخص المحدد هو المرسِل، اعرض المستلم (الطرف الآخر)
            // والعكس. هذا يوضّح جهة الحوالة في سياق الكشف.
            val otherParty = when (highlightName) {
                item.senderName -> item.recipientName
                item.recipientName -> item.senderName
                else -> item.recipientName ?: item.senderName
            }
            textRecipient.text = otherParty ?: "بدون اسم مستخرَج"
            textDate.text = item.date ?: "—"
            textBank.text = item.bankId

            // تلوين خفيف بحسب الدور (مرسِل = رمادي، مستلِم = أخضر داكن)
            val ctx = root.context
            val colorRes = if (highlightName != null && highlightName == item.senderName)
                android.R.color.darker_gray
            else
                com.azzam.receiptscanner.R.color.primary_dark
            textAmount.setTextColor(
                androidx.core.content.ContextCompat.getColor(ctx, colorRes)
            )

            root.setOnClickListener { /* يمكن ربطه بمحرّر السجل لاحقاً */ }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<ReceiptData>() {
        override fun areItemsTheSame(oldItem: ReceiptData, newItem: ReceiptData) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ReceiptData, newItem: ReceiptData) =
            oldItem == newItem
    }
}
