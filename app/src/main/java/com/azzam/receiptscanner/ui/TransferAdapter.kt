package com.azzam.receiptscanner.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.azzam.receiptscanner.R
import com.azzam.receiptscanner.databinding.ItemTransferBinding
import com.azzam.receiptscanner.model.Transfer
import java.text.NumberFormat
import java.util.Locale

/**
 * محوّل قائمة السجلات (إعادة تصميم جذري - "Financial Elegance").
 *
 * التصميم الجديد:
 *  - شريط جانبي ملوّن حسب الثقة (بديل شارة الثقة السابقة)
 *  - سهم اتجاه بين المرسل والمستلم
 *  - المبلغ منفصل عن العملة (تنسيق مالي حديث)
 *  - شارة المحرك + المصدر في سطر واحد
 *  - ألوان متناسقة: المبلغ ذهبي، المستلم زمردي
 */
class TransferAdapter(
    private val onTapEdit: (Transfer) -> Unit,
    private val onLongPressDelete: (Transfer) -> Unit
) : ListAdapter<Transfer, TransferAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(val binding: ItemTransferBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTransferBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context

        holder.binding.apply {
            // ===== المبلغ: رقم منفصل + عملة "ر.س" =====
            val amount = item.amount
            if (amount != null) {
                // تنسيق رقمي نظيف بلا عملة (العملة منفصلة)
                val formatter = NumberFormat.getNumberInstance(Locale.US)
                formatter.minimumFractionDigits = 2
                formatter.maximumFractionDigits = 2
                textAmount.text = formatter.format(amount)
                textCurrency.visibility = View.VISIBLE
            } else {
                textAmount.text = "—"
                textCurrency.visibility = View.GONE
            }

            // ===== المرسل والمستلم =====
            textSender.text = item.senderName?.takeIf { it.isNotBlank() }
                ?: ctx.getString(R.string.card_unknown_name)
            textRecipient.text = item.recipientName?.takeIf { it.isNotBlank() }
                ?: ctx.getString(R.string.card_unknown_name)

            // ===== التاريخ + البنك =====
            textDate.text = item.date ?: "—"
            textBank.text = item.bankId

            // ===== المصدر =====
            if (item.sourceFileName.isNotBlank()) {
                textSource.visibility = View.VISIBLE
                textSource.text = "📷 ${item.sourceFileName}"
            } else {
                textSource.visibility = View.GONE
            }

            // ===== شارة المحرك =====
            if (!item.llmEngineUsed.isNullOrBlank()) {
                textEngine.visibility = View.VISIBLE
                textEngine.text = item.llmEngineUsed
            } else {
                textEngine.visibility = View.GONE
            }

            // ===== شريط الثقة الجانبي (بديل الشارة) =====
            val (stripColor, showBadge, badgeText, badgeColor) = when {
                item.confidence >= 0.85f -> Quad(
                    R.color.confidence_high, false, "", R.color.confidence_high
                )
                item.confidence >= 0.5f -> Quad(
                    R.color.confidence_medium, false, "", R.color.confidence_medium
                )
                else -> Quad(
                    R.color.confidence_low, true,
                    ctx.getString(R.string.confidence_low_short),
                    R.color.confidence_low
                )
            }
            confidenceStrip.setBackgroundColor(ContextCompat.getColor(ctx, stripColor))

            // الشارة النصية تظهر فقط للثقة المنخفضة (تنبيه)
            if (showBadge) {
                textConfidence.visibility = View.VISIBLE
                textConfidence.text = badgeText
                textConfidence.setBackgroundColor(ContextCompat.getColor(ctx, badgeColor))
                textConfidence.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
            } else {
                textConfidence.visibility = View.GONE
            }

            // ===== التفاعل =====
            root.setOnClickListener { onTapEdit(item) }
            root.setOnLongClickListener {
                onLongPressDelete(item)
                true
            }
        }
    }

    /** نوع مساعد لإرجاع 4 قيم معاً. */
    private data class Quad<A, B, C, D>(
        val first: A, val second: B, val third: C, val fourth: D
    ) {
        operator fun component1() = first
        operator fun component2() = second
        operator fun component3() = third
        operator fun component4() = fourth
    }

    object DiffCallback : DiffUtil.ItemCallback<Transfer>() {
        override fun areItemsTheSame(oldItem: Transfer, newItem: Transfer) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Transfer, newItem: Transfer) =
            oldItem == newItem
    }
}
