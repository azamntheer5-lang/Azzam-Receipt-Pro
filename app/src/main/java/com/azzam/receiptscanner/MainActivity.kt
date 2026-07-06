package com.azzam.receiptscanner

import android.app.DatePickerDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.azzam.receiptscanner.backup.BackupManager
import com.azzam.receiptscanner.data.database.ReceiptRoomRepo
import com.azzam.receiptscanner.data.entity.ReceiptData
import com.azzam.receiptscanner.databinding.ActivityMainBinding
import com.azzam.receiptscanner.databinding.DialogEditTransferBinding
import com.azzam.receiptscanner.export.CsvExporter
import com.azzam.receiptscanner.export.PdfReportExporter
import com.azzam.receiptscanner.model.Transfer
import com.azzam.receiptscanner.parser.BankMatcher
import com.azzam.receiptscanner.ui.AccountStatementsActivity
import com.azzam.receiptscanner.ui.AnalyticsActivity
import com.azzam.receiptscanner.ui.MainViewModel
import com.azzam.receiptscanner.ui.SettingsActivity
import com.azzam.receiptscanner.ui.TransferAdapter
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale

/**
 * الشاشة الرئيسية (إعادة تصميم جذري - "Financial Elegance").
 *
 * تحسينات الواجهة:
 *  - بطاقة هيرو بتدرّج زمردي للإجمالي
 *  - شريط بحث حديث بزاوية 28dp
 *  - Bottom Navigation (رئيسية/كشف/تحليلات/إعدادات)
 *  - قائمة داخل NestedScrollView (تمرير موحّد مع الهيرو)
 *  - حالة فارغة بأيقونة
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: TransferAdapter

    private var fullList: List<Transfer> = emptyList()
    private var pendingBackupPassword: String? = null
    private var pendingRestorePassword: String? = null

    // ★ StateFlows للفلترة المتقدمة (Live Search + Bank + Date Range)
    private val searchQuery = MutableStateFlow("")
    private val selectedBank = MutableStateFlow<String?>(null)
    private val dateFrom = MutableStateFlow<String?>(null)
    private val dateTo = MutableStateFlow<String?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val password = pendingBackupPassword
        pendingBackupPassword = null
        if (uri != null && password != null) {
            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    BackupManager.writeBackup(this@MainActivity, uri, password)
                }
                toast(if (success) R.string.backup_success else R.string.backup_failed)
            }
        }
    }

    private val openRestoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val password = pendingRestorePassword
        pendingRestorePassword = null
        if (uri != null && password != null) {
            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    BackupManager.restoreBackup(this@MainActivity, uri, password)
                }
                toast(if (success) R.string.restore_success else R.string.restore_failed)
            }
        }
    }

    /** مُطلق اختيار مجلد كامل للمعالجة بالجملة (Batch Processing). */
    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                // ★ احتفظ بصلاحية الوصول الدائمة (حاسم لـ Scoped Storage)
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
                startBatchScan(uri)
            } catch (e: SecurityException) {
                // النظام رفض منح الصلاحية الدائمة
                Toast.makeText(
                    this,
                    getString(R.string.batch_permission_denied),
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            // المستخدم ألغى الاختيار
            Toast.makeText(
                this,
                getString(R.string.batch_no_folder_selected),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // شاشة البداية تُعرض تلقائياً عبر ثيم Theme.ReceiptScanner.Splash
        // (لا حاجة لاستدعاء installSplashScreen برمجياً)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        adapter = TransferAdapter(
            onTapEdit = { openVerificationScreen(it.id) },
            onLongPressDelete = { viewModel.deleteTransfer(it.id) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        // ★ تحسينات Smooth Scrolling:
        //  - setHasFixedSize(true): البطاقات لها ارتفاع ثابت تقريباً → تجنّب requestLayout
        //  - setItemViewCacheSize(8): احتفظ بـ 8 بطاقات خارج الشاشة (تجنّب onBindViewHolder المتكرر)
        //  - setDrawingCacheQuality: جودة منخفضة للـ cache (أسرع)
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.setItemViewCacheSize(8)
        binding.recyclerView.isNestedScrollingEnabled = false

        binding.buttonGrantPermission.setOnClickListener { requestManageStoragePermission() }
        binding.fabScanNow.setOnClickListener { triggerManualScan() }
        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchQuery.value = s?.toString().orEmpty()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        setupFilters()
        setupBottomNavigation()
        observeTransfers()
    }

    /** يربط Filter Chips للبنوك + أزرار الفترة الزمنية + زر المسح. */
    private fun setupFilters() {
        // راقب البنوك المتاحة وأضف chips لها
        lifecycleScope.launch {
            ReceiptRoomRepo.observeDistinctBanks(this@MainActivity).collect { banks ->
                renderBankChips(banks)
            }
        }

        // أزرار الفترة الزمنية
        binding.chipDateFrom.setOnClickListener { showDatePicker(true) }
        binding.chipDateTo.setOnClickListener { showDatePicker(false) }

        // زر مسح الفلاتر
        binding.chipClearFilters.setOnClickListener {
            selectedBank.value = null
            dateFrom.value = null
            dateTo.value = null
            binding.editSearch.text.clear()
            binding.chipDateFrom.text = getString(R.string.filter_date_from)
            binding.chipDateTo.text = getString(R.string.filter_date_to)
            // أزل اختيار كل الـ chips
            binding.bankChips.clearCheck()
        }
    }

    /** يبني chips البنوك من السجلات المتاحة. */
    private fun renderBankChips(banks: List<String>) {
        binding.bankChips.removeAllViews()

        // chip "الكل"
        val allChip = Chip(this).apply {
            text = getString(R.string.filter_all_banks)
            isCheckable = true
            isChecked = selectedBank.value == null
            setOnClickListener {
                if (isChecked) selectedBank.value = null
            }
        }
        binding.bankChips.addView(allChip)

        // chip لكل بنك
        for (bankId in banks) {
            val chip = Chip(this).apply {
                text = BankMatcher.beautifyBankName(bankId)
                isCheckable = true
                isChecked = selectedBank.value == bankId
                setOnClickListener {
                    if (isChecked) selectedBank.value = bankId
                }
            }
            binding.bankChips.addView(chip)
        }
    }

    /** يفتح Date Picker لاختيار تاريخ from/to. */
    private fun showDatePicker(isFrom: Boolean) {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val formatted = "%04d-%02d-%02d".format(year, month + 1, day)
                if (isFrom) {
                    dateFrom.value = formatted
                    binding.chipDateFrom.text = formatted
                } else {
                    dateTo.value = formatted
                    binding.chipDateTo.text = formatted
                }
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    /** يربط شريط التنقل السفلي بشاشاته الأربع. */
    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { true } // نحن هنا بالفعل
                R.id.nav_statements -> {
                    startActivity(Intent(this, AccountStatementsActivity::class.java))
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    false // لا نثبّت الاختيار (نبقى في الرئيسية)
                }
                R.id.nav_analytics -> {
                    startActivity(Intent(this, AnalyticsActivity::class.java))
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    false
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    false
                }
                else -> false
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_statements -> startActivity(Intent(this, AccountStatementsActivity::class.java))
            R.id.action_batch_scan -> openFolderLauncher.launch(null)
            R.id.action_analytics -> startActivity(Intent(this, AnalyticsActivity::class.java))
            R.id.action_export_csv -> exportCsv()
            R.id.action_export_pdf -> exportPdf()
            R.id.action_backup -> startBackupFlow()
            R.id.action_restore -> startRestoreFlow()
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
        }
        return true
    }

    // ---------- الصلاحيات والخدمات ----------

    private fun refreshPermissionState() {
        val granted = Environment.isExternalStorageManager()
        binding.permissionContainer.visibility = if (granted) View.GONE else View.VISIBLE
        binding.mainContainer.visibility = if (granted) View.VISIBLE else View.GONE

        if (granted) {
            requestNotificationPermissionIfNeeded()
            startServices()
        } else {
            // ★ Toast تشخيصي واضح
            Toast.makeText(this, R.string.permission_needed_toast, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestManageStoragePermission() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startServices() {
        val serviceIntent = Intent(this, ReceiptWatcherService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        PeriodicScanWorker.schedule(this)
        // ★ لا فحص تلقائي — المستخدم يضغط FAB لبدء الفحص عبر API
        // (الفحص عبر API يستغرق وقتاً ويستهلك تكلفة، فلا بد أن يكون يدوياً)
    }

    private fun triggerManualScan() {
        com.azzam.receiptscanner.storage.ProcessedFilesTracker.resetAll(this)
        runImmediateScan(isAuto = false)
    }

    /**
     * ★ فحص فوري في lifecycleScope — يضمن ظهور النتائج والتقرير.
     * يعرض ProgressDialog أثناء الفحص (قد يستغرق دقائق لمئات الصور).
     */
    private fun runImmediateScan(isAuto: Boolean) {
        // ★ اعرض ProgressDialog فوراً ليعرف المستخدم أن الفحص بدأ
        val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⏳ جارٍ الفحص...")
            .setMessage("نفحص مجلدات واتساب والصور.\nقد يستغرق عدة دقائق لمئات الملفات.\nالرجاء الانتظار...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val report = try {
                withContext(Dispatchers.IO) {
                    com.azzam.receiptscanner.processing.ImmediateScanner.scanNow(this@MainActivity)
                }
            } catch (e: Exception) {
                "❌ خطأ أثناء الفحص: ${e.message?.take(100) ?: "غير معروف"}\n\n" +
                    "نوع الخطأ: ${e::class.simpleName}"
            }
            // ★ أخفِ ProgressDialog ثم اعرض التقرير
            progressDialog.dismiss()
            showScanReport(report, isAuto)
        }
    }

    /** يعرض تقرير الفحص في AlertDialog — مضمون الظهور. */
    private fun showScanReport(report: String, isAuto: Boolean) {
        try {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(if (isAuto) "📊 نتيجة الفحص التلقائي" else "📊 نتيجة الفحص اليدوي")
                .setMessage(report)
                .setPositiveButton(R.string.confirm) { _, _ -> }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            // fallback: Toast إن فشل AlertDialog
            Toast.makeText(this, report, Toast.LENGTH_LONG).show()
        }
    }

    /** يبدأ المعالجة بالجملة لمجلد كامل عبر BatchScanWorker. */
    private fun startBatchScan(folderUri: Uri) {
        val request = androidx.work.OneTimeWorkRequestBuilder<com.azzam.receiptscanner.processing.BatchScanWorker>()
            .setInputData(
                androidx.work.workDataOf(
                    com.azzam.receiptscanner.processing.BatchScanWorker.KEY_TREE_URI to folderUri.toString()
                )
            )
            .build()
        WorkManager.getInstance(this).enqueue(request)
        toast(R.string.batch_scan_started)
    }

    // ---------- القائمة + البحث المتقدم ----------

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeTransfers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // ادمج 4 StateFlows (search + bank + dateFrom + dateTo) وفلتر عبر Room
                combine(
                    searchQuery,
                    selectedBank,
                    dateFrom,
                    dateTo
                ) { query, bank, from, to ->
                    FilterCriteria(query, bank, from, to)
                }.flatMapLatest { criteria ->
                    ReceiptRoomRepo.search(
                        this@MainActivity,
                        query = criteria.query,
                        bankId = criteria.bank,
                        dateFrom = criteria.dateFrom,
                        dateTo = criteria.dateTo
                    )
                }.collect { receipts ->
                    // حوّل ReceiptData → Transfer واعرض
                    val transfers = receipts.map { it.toTransfer() }
                    fullList = transfers
                    render()
                }
            }
        }
    }

    /** معايير الفلترة المدمجة. */
    private data class FilterCriteria(
        val query: String,
        val bank: String?,
        val dateFrom: String?,
        val dateTo: String?
    )

    private fun render() {
        val sorted = fullList.sortedByDescending { it.processedAt }
        adapter.submitList(sorted)
        binding.textEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE

        val formatter = NumberFormat.getCurrencyInstance(Locale("ar", "SA"))
        binding.textTotal.text = formatter.format(sorted.sumOf { it.amount ?: 0.0 })
        binding.textCount.text = getString(R.string.count_format, sorted.size)

        val needsReview = sorted.count { it.confidence < 0.5f }
        if (needsReview > 0) {
            binding.textNeedsReview.visibility = View.VISIBLE
            binding.textNeedsReview.text =
                getString(R.string.main_needs_review_format, needsReview)
        } else {
            binding.textNeedsReview.visibility = View.GONE
        }
    }

    /** تحويل ReceiptData → Transfer لعرضه في Adapter. */
    private fun ReceiptData.toTransfer(): Transfer = Transfer(
        id = id,
        senderName = senderName,
        recipientName = recipientName,
        amount = amount,
        date = date,
        bankId = bankId,
        confidence = confidence,
        sourceFileName = sourceFileName,
        processedAt = processedAt,
        rawText = rawText,
        llmEngineUsed = llmEngineUsed,
        originalFilePath = originalFilePath
    )

    // ---------- تعديل سجل يدوياً ----------

    /** ★ يفتح شاشة المراجعة والمطابقة (Side-by-Side Verification). */
    private fun openVerificationScreen(receiptId: String) {
        val intent = Intent(this, com.azzam.receiptscanner.ui.ReceiptVerificationActivity::class.java)
        intent.putExtra(com.azzam.receiptscanner.ui.ReceiptVerificationActivity.EXTRA_RECEIPT_ID, receiptId)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun showEditDialog(transfer: Transfer) {
        val dialogBinding = DialogEditTransferBinding.inflate(layoutInflater)

        dialogBinding.textContextSource.text =
            getString(R.string.edit_context_source, transfer.sourceFileName)
        if (!transfer.llmEngineUsed.isNullOrBlank()) {
            dialogBinding.textContextEngine.visibility = View.VISIBLE
            dialogBinding.textContextEngine.text = transfer.llmEngineUsed
        }

        dialogBinding.editSender.setText(transfer.senderName.orEmpty())
        dialogBinding.editRecipient.setText(transfer.recipientName.orEmpty())
        dialogBinding.editAmount.setText(transfer.amount?.toString().orEmpty())
        dialogBinding.editDate.setText(transfer.date.orEmpty())

        dialogBinding.textRawOcr.text =
            transfer.rawText.ifBlank { getString(R.string.edit_raw_text_empty) }

        val dateEditText = dialogBinding.editDate
        val openDatePicker = {
            val cal = Calendar.getInstance()
            transfer.date?.let { d ->
                runCatching {
                    val parts = d.split("-")
                    if (parts.size == 3) {
                        cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                    }
                }
            }
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    dateEditText.setText("%04d-%02d-%02d".format(year, month + 1, day))
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        dateEditText.setOnClickListener { openDatePicker() }

        AlertDialog.Builder(this)
            .setTitle(R.string.edit_transfer_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val updated = transfer.copy(
                    senderName = dialogBinding.editSender.text.toString().trim().ifBlank { null },
                    recipientName = dialogBinding.editRecipient.text.toString().trim().ifBlank { null },
                    amount = dialogBinding.editAmount.text.toString().trim().toDoubleOrNull(),
                    date = dialogBinding.editDate.text.toString().trim().ifBlank { null },
                    confidence = 1.0f
                )
                viewModel.updateTransfer(updated)
            }
            .setNeutralButton(R.string.delete) { _, _ -> viewModel.deleteTransfer(transfer.id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- تصدير ----------

    private fun exportCsv() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                CsvExporter.export(this@MainActivity, viewModel.transfers.value)
            }
            shareFile(file, "text/csv")
        }
    }

    private fun exportPdf() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                PdfReportExporter.export(this@MainActivity, viewModel.transfers.value)
            }
            shareFile(file, "application/pdf")
        }
    }

    private fun shareFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_success)))
    }

    // ---------- نسخ احتياطي ----------

    private fun startBackupFlow() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_password_title)
            .setMessage(R.string.backup_password_desc)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val password = input.text.toString()
                if (password.isNotBlank()) {
                    pendingBackupPassword = password
                    createBackupLauncher.launch("receipt_backup_${System.currentTimeMillis()}.bak")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startRestoreFlow() {
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_confirm_title)
            .setMessage(R.string.restore_confirm_desc)
            .setPositiveButton(R.string.confirm) { _, _ -> askRestorePassword() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askRestorePassword() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_password_title)
            .setMessage(R.string.restore_password_desc)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val password = input.text.toString()
                if (password.isNotBlank()) {
                    pendingRestorePassword = password
                    openRestoreLauncher.launch(arrayOf("*/*"))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, getString(resId), Toast.LENGTH_LONG).show()
    }
}
