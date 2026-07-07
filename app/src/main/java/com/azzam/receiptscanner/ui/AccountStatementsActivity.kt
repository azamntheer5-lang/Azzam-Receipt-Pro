package com.azzam.receiptscanner.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.azzam.receiptscanner.R
import com.azzam.receiptscanner.data.database.ReceiptRoomRepo
import com.azzam.receiptscanner.databinding.ActivityAccountStatementsBinding
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * شاشة كشف الحسابات — تجمّع الحوالات بالاسم.
 *
 * التطوير (المرحلة 2): ميزة جديدة بالكامل. تعرض لكل شخص: الاسم، عدد
 * حوالاته، إجمالي مبالغه، ودوره (مرسِل/مستلِم/كلاهما). الضغط على بطاقة
 * تفتح شاشة [AccountDetailActivity] بكل حوالاته تفصيلياً.
 *
 * مصدر البيانات: Room عبر [ReceiptRoomRepo.buildAllStatements]، الذي يستخدم
 * استعلامات SQL مجمّعة (GROUP BY/Sum) — أسرع بكثير من التجميع في الذاكرة.
 */
class AccountStatementsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccountStatementsBinding
    private lateinit var adapter: AccountStatementAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountStatementsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.statementsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.statements_title)
        binding.statementsToolbar.setNavigationOnClickListener { finish() }

        adapter = AccountStatementAdapter { statement ->
            val intent = Intent(this, AccountDetailActivity::class.java)
            intent.putExtra(AccountDetailActivity.EXTRA_NAME, statement.name)
            startActivity(intent)
        }
        binding.recyclerStatements.layoutManager = LinearLayoutManager(this)
        binding.recyclerStatements.adapter = adapter

        loadStatements()
    }

    override fun onResume() {
        super.onResume()
        // أعد التحميل عند العودة (قد يكون المستخدم عدّل/حذف سجلاً في التفاصيل)
        loadStatements()
    }

    private fun loadStatements() {
        lifecycleScope.launch {
            val statements = ReceiptRoomRepo.buildAllStatements(this@AccountStatementsActivity)
            render(statements)
        }
    }

    private fun render(statements: List<com.azzam.receiptscanner.data.entity.AccountStatement>) {
        adapter.submitList(statements)
        binding.textStatementsEmpty.visibility =
            if (statements.isEmpty()) View.VISIBLE else View.GONE

        val formatter = NumberFormat.getCurrencyInstance(Locale("ar", "SA"))
        val grandTotal = statements.sumOf { it.totalAmount }
        binding.textStatementsTotal.text = formatter.format(grandTotal)
        binding.textStatementsCount.text = getString(
            R.string.statements_people_count, statements.size
        )
    }
}
