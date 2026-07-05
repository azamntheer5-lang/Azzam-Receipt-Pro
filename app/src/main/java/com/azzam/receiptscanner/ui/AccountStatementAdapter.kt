package com.azzam.receiptscanner.ui

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.azzam.receiptscanner.data.entity.AccountStatement
import com.azzam.receiptscanner.databinding.ItemAccountStatementBinding
import java.text.NumberFormat
import java.util.Locale

/**
 * محوّل قائمة كشوف الحسابات المجمّعة.
 * كل بطاقة = شخص واحد (اسم + إجمالي + عدد + دور) — الضغط يفتح التفاصيل.
 */
class AccountStatementAdapter(
    private val onClick: (AccountStatement) -> Unit
) : ListAdapter<AccountStatement, AccountStatementAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(val binding: ItemAccountStatementBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAccountStatementBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val formatter = NumberFormat.getCurrencyInstance(Locale("ar", "SA"))

        holder.binding.apply {
            textName.text = item.name
            textTotal.text = formatter.format(item.totalAmount)
            textCount.text = root.context.getString(
                R.string.statements_count_format, item.transferCount
            )
            textRole.text = when (item.role) {
                "both" -> root.context.getString(R.string.role_both)
                "sender" -> root.context.getString(R.string.role_sender)
                else -> root.context.getString(R.string.role_recipient)
            }
            root.setOnClickListener { onClick(item) }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<AccountStatement>() {
        override fun areItemsTheSame(oldItem: AccountStatement, newItem: AccountStatement) =
            oldItem.name == newItem.name

        override fun areContentsTheSame(oldItem: AccountStatement, newItem: AccountStatement) =
            oldItem == newItem
    }
}
