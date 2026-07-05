package com.azzam.receiptscanner.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.azzam.receiptscanner.R
import com.azzam.receiptscanner.data.dao.BankTotal
import com.azzam.receiptscanner.data.database.AppDatabase
import com.azzam.receiptscanner.databinding.ActivityAnalyticsBinding
import com.azzam.receiptscanner.storage.TransferRepository
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * شاشة Dashboard (Modern Minimalist) — ملخص مالي مرئي شامل.
 *
 * المكوّنات:
 *  1. بطاقة الإجمالي الكبير
 *  2. شبكة إحصائيات (متوسط، أعلى، عدد البنوك، مراجعة)
 *  3. مخطط دائري لتوزيع المبالغ حسب البنوك (بالنسب المئوية)
 *  4. مخطط عمودي للإجمالي الشهري
 *  5. قائمة أكثر الجهات تكراراً
 */
class AnalyticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnalyticsBinding

    // لوحة ألوان هادئة متناسقة مع المخططات
    private val chartColors = listOf(
        Color.parseColor("#0EA5A5"),  // تركواز
        Color.parseColor("#3B82F6"),  // أزرق
        Color.parseColor("#F59E0B"),  // برتقالي
        Color.parseColor("#EC4899"),  // وردي
        Color.parseColor("#8B5CF6"),  // بنفسجي
        Color.parseColor("#10B981"),  // أخضر
        Color.parseColor("#F97316")   // برتقالي داكن
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnalyticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.analyticsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.analytics_title)
        binding.analyticsToolbar.setNavigationOnClickListener { finish() }

        lifecycleScope.launch {
            TransferRepository.loadIfNeeded(applicationContext)
            renderDashboard()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { renderDashboard() }
    }

    private fun renderDashboard() {
        val transfers = TransferRepository.transfers.value
        val dao = AppDatabase.get(this).receiptDao()
        val bankTotals = dao.totalsByBank()
        val monthly = TransferRepository.monthlyTotals()
        val top = TransferRepository.topCounterparties(5)

        if (transfers.isEmpty() && bankTotals.isEmpty()) {
            binding.textNoData.visibility = View.VISIBLE
            binding.pieChart.visibility = View.GONE
            binding.barChart.visibility = View.GONE
            binding.textBankDistTitle.visibility = View.GONE
            binding.textBankDistSubtitle.visibility = View.GONE
            binding.textMonthlyTitle.visibility = View.GONE
            binding.bankListContainer.visibility = View.GONE
            return
        }

        binding.textNoData.visibility = View.GONE
        renderHeroStat(transfers)
        renderStatsGrid(transfers, bankTotals)
        renderPieChart(bankTotals)
        renderBankList(bankTotals)
        renderBarChart(monthly)
        renderTopList(top)
    }

    // ===== 1) بطاقة الإجمالي =====
    private fun renderHeroStat(transfers: List<com.azzam.receiptscanner.model.Transfer>) {
        val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = 2; maximumFractionDigits = 2
        }
        val total = transfers.sumOf { it.amount ?: 0.0 }
        binding.textTotalAmount.text = "${formatter.format(total)} ${getString(R.string.currency_sar)}"
        binding.textTotalCount.text = getString(R.string.count_format, transfers.size)
    }

    // ===== 2) شبكة الإحصائيات =====
    private fun renderStatsGrid(
        transfers: List<com.azzam.receiptscanner.model.Transfer>,
        bankTotals: List<BankTotal>
    ) {
        val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = 0; maximumFractionDigits = 0
        }
        val amounts = transfers.mapNotNull { it.amount }
        val avg = if (amounts.isNotEmpty()) amounts.average() else 0.0
        val max = amounts.maxOrNull() ?: 0.0
        val reviewCount = transfers.count { it.confidence < 0.5f }

        binding.textStatAvg.text = formatter.format(avg)
        binding.textStatMax.text = formatter.format(max)
        binding.textStatBanks.text = bankTotals.size.toString()
        binding.textStatReview.text = reviewCount.toString()
    }

    // ===== 3) المخطط الدائري لتوزيع البنوك =====
    private fun renderPieChart(bankTotals: List<BankTotal>) {
        if (bankTotals.isEmpty()) {
            binding.pieChart.visibility = View.GONE
            binding.textBankDistTitle.visibility = View.GONE
            binding.textBankDistSubtitle.visibility = View.GONE
            return
        }

        val totalAll = bankTotals.sumOf { it.total }
        val entries = bankTotals.mapIndexed { i, bt ->
            PieEntry(bt.total.toFloat(), beautifyBankName(bt.bank))
        }

        val dataSet = PieDataSet(entries, "").apply {
            colors = chartColors.take(entries.size)
            valueTextSize = 12f
            valueTextColor = Color.WHITE
            sliceSpace = 2f
            selectionShift = 6f
        }

        binding.pieChart.apply {
            data = PieData(dataSet).apply {
                setValueFormatter(PercentFormatter(binding.pieChart))
                setValueTextSize(11f)
                setValueTextColor(Color.WHITE)
            }
            description.isEnabled = false
            // مركز الإجمالي
            centerText = "${NumberFormat.getNumberInstance(Locale.US).format(totalAll.toInt())}\n${getString(R.string.currency_sar)}"
            setCenterTextSize(14f)
            setCenterTextColor(Color.parseColor("#0F172A"))
            setHoleColor(Color.TRANSPARENT)
            transparentCircleRadius = 0f
            holeRadius = 55f
            legend.apply {
                isEnabled = true
                orientation = Legend.LegendOrientation.VERTICAL
                verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                textSize = 11f
                textColor = Color.parseColor("#64748B")
                form = Legend.LegendForm.CIRCLE
                formSize = 8f
                xEntrySpace = 12f
                yEntrySpace = 4f
            }
            setEntryLabelTextSize(10f)
            setEntryLabelColor(Color.parseColor("#64748B"))
            animateY(800, Easing.EaseInOutCubic)
            invalidate()
        }
    }

    // ===== قائمة البنوك بالنسب =====
    private fun renderBankList(bankTotals: List<BankTotal>) {
        val container = binding.bankListContainer
        container.removeAllViews()
        val totalAll = bankTotals.sumOf { it.total }
        val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = 0; maximumFractionDigits = 0
        }

        bankTotals.forEachIndexed { i, bt ->
            val pct = if (totalAll > 0) (bt.total / totalAll * 100).toInt() else 0
            val view = LayoutInflater.from(this).inflate(R.layout.item_bank_stat, container, false)
            val colorDot = view.findViewById<View>(R.id.colorDot)
            val textBank = view.findViewById<TextView>(R.id.textBankName)
            val textAmount = view.findViewById<TextView>(R.id.textBankAmount)
            val textPct = view.findViewById<TextView>(R.id.textBankPct)

            colorDot.setBackgroundColor(chartColors[i % chartColors.size])
            textBank.text = beautifyBankName(bt.bank)
            textAmount.text = "${formatter.format(bt.total.toInt())} ${getString(R.string.currency_sar)}"
            textPct.text = "$pct%"

            container.addView(view)
        }
    }

    // ===== 4) المخطط العمودي الشهري =====
    private fun renderBarChart(monthly: List<Pair<String, Double>>) {
        if (monthly.isEmpty()) {
            binding.barChart.visibility = View.GONE
            binding.textMonthlyTitle.visibility = View.GONE
            return
        }

        val entries = monthly.mapIndexed { i, (_, total) ->
            BarEntry(i.toFloat(), total.toFloat())
        }
        val dataSet = BarDataSet(entries, getString(R.string.analytics_monthly_title)).apply {
            color = Color.parseColor("#0EA5A5")
            valueTextSize = 10f
            valueTextColor = Color.parseColor("#64748B")
            barShadowColor = Color.parseColor("#F1F5F9")
        }

        binding.barChart.apply {
            data = BarData(dataSet).apply {
                barWidth = 0.6f
                setValueFormatter(object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return NumberFormat.getNumberInstance(Locale.US).format(value.toInt())
                    }
                })
            }
            description.isEnabled = false
            legend.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            setDrawGridBackground(false)
            setDrawBarShadow(true)

            xAxis.apply {
                position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
                valueFormatter = IndexAxisValueFormatter(monthly.map { it.first })
                granularity = 1f
                setDrawGridLines(false)
                setDrawAxisLine(false)
                textColor = Color.parseColor("#94A3B8")
                textSize = 10f
            }
            axisLeft.apply {
                setDrawGridLines(true)
                setDrawAxisLine(false)
                gridColor = Color.parseColor("#F1F5F9")
                textColor = Color.parseColor("#94A3B8")
                textSize = 10f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return NumberFormat.getNumberInstance(Locale.US).format(value.toInt() / 1000) + "K"
                    }
                }
            }
            axisRight.isEnabled = false
            animateY(800, Easing.EaseInOutCubic)
            invalidate()
        }
    }

    // ===== 5) قائمة أكثر الجهات =====
    private fun renderTopList(top: List<Pair<String, Double>>) {
        val container = binding.topContainer
        container.removeAllViews()
        val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = 0; maximumFractionDigits = 0
        }

        top.forEachIndexed { i, (name, total) ->
            val view = LayoutInflater.from(this).inflate(R.layout.item_top_counterparty, container, false)
            val textRank = view.findViewById<TextView>(R.id.textRank)
            val textName = view.findViewById<TextView>(R.id.textName)
            val textAmount = view.findViewById<TextView>(R.id.textAmount)

            textRank.text = (i + 1).toString()
            textName.text = name
            textAmount.text = "${formatter.format(total.toInt())} ${getString(R.string.currency_sar)}"

            container.addView(view)
        }
    }

    /** يحوّل bankId لاسم عرض جميل. */
    private fun beautifyBankName(bankId: String): String = when (bankId.lowercase()) {
        "al_rajhi" -> "الراجحي"
        "stc_pay" -> "STC Pay"
        "cloud_ai", "llm_claude" -> "Claude AI"
        "llm_gemini" -> "Gemini AI"
        "llm_groq" -> "Groq AI"
        "llm_huggingface" -> "HuggingFace"
        "generic" -> "عام"
        "unknown" -> "غير مصنّف"
        else -> bankId
    }
}
