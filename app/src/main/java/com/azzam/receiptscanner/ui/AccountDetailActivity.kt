package com.azzam.receiptscanner.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.azzam.receiptscanner.R
import com.azzam.receiptscanner.data.database.ReceiptRoomRepo
import com.azzam.receiptscanner.data.entity.AccountStatement
import com.azzam.receiptscanner.databinding.ActivityAccountDetailBinding
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * شاشة تفاصيل كشف حساب لشخص واحد.
 *
 * تعرض:
 *  - الاسم + الدور (مرسِل/مستلِم/كلاهما)
 *  - إجمالي ما أرسله + إجمالي ما استلمه + عدد الحوالات
 *  - قائمة كل حوالاته (مع تمييز الطرف الآخر في كل حوالة)
 *
 * مصدر البيانات: [ReceiptRoomRepo.buildStatementFor] — استعلامات Room مجمّعة.
 */
class AccountDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccountDetailBinding
    private lateinit var adapter: ReceiptDetailAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.detailToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.detailToolbar.setNavigationOnClickListener { finish() }

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        adapter = ReceiptDetailAdapter(highlightName = name)
        binding.recyclerDetailTransfers.layoutManager = LinearLayoutManager(this)
        binding.recyclerDetailTransfers.adapter = adapter

        loadStatement(name)
    }

    override fun onResume() {
        super.onResume()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        loadStatement(name)
    }

    private fun loadStatement(name: String) {
        if (name.isBlank()) {
            finish()
            return
        }
        lifecycleScope.launch {
            val statement = ReceiptRoomRepo.buildStatementFor(
                this@AccountDetailActivity, name
            )
            render(statement)
        }
    }

    private fun render(statement: AccountStatement?) {
        if (statement == null) {
            binding.textDetailEmpty.visibility = View.VISIBLE
            return
        }
        val formatter = NumberFormat.getCurrencyInstance(Locale("ar", "SA"))
        val ctx = this

        binding.textDetailName.text = statement.name
        binding.textDetailRole.text = when (statement.role) {
            "both" -> ctx.getString(R.string.role_both_full)
            "sender" -> ctx.getString(R.string.role_sender_full)
            else -> ctx.getString(R.string.role_recipient_full)
        }

        // الإجماليات: نفصل المرسَل عن المستلِم
        val sent = statement.transfers
            .filter { it.senderName == statement.name }
            .sumOf { it.amount ?: 0.0 }
        val received = statement.transfers
            .filter { it.recipientName == statement.name }
            .sumOf { it.amount ?: 0.0 }

        binding.textDetailSent.text = formatter.format(sent)
        binding.textDetailReceived.text = formatter.format(received)
        binding.textDetailCount.text = statement.transferCount.toString()

        adapter.submitList(statement.transfers)
        binding.textDetailEmpty.visibility =
            if (statement.transfers.isEmpty()) View.VISIBLE else View.GONE
    }

    companion object {
        const val EXTRA_NAME = "extra_name"
    }
}
