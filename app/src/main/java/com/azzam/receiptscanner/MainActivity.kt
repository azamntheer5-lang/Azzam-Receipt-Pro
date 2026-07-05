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
import com.azzam.receiptscanner.databinding.ActivityMainBinding
import com.azzam.receiptscanner.databinding.DialogEditTransferBinding
import com.azzam.receiptscanner.export.CsvExporter
import com.azzam.receiptscanner.export.PdfReportExporter
import com.azzam.receiptscanner.model.Transfer
import com.azzam.receiptscanner.ui.AccountStatementsActivity
import com.azzam.receiptscanner.ui.AnalyticsActivity
import com.azzam.receiptscanner.ui.MainViewModel
import com.azzam.receiptscanner.ui.SettingsActivity
import com.azzam.receiptscanner.ui.TransferAdapter
import kotlinx.coroutines.Dispatchers
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
    private var searchQuery: String = ""
    private var pendingBackupPassword: String? = null
    private var pendingRestorePassword: String? = null

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
            // احتفظ بصلاحية الوصول الدائم
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            startBatchScan(uri)
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
            onTapEdit = { showEditDialog(it) },
            onLongPressDelete = { viewModel.deleteTransfer(it.id) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(false)
        binding.recyclerView.isNestedScrollingEnabled = false

        binding.buttonGrantPermission.setOnClickListener { requestManageStoragePermission() }
        binding.fabScanNow.setOnClickListener { triggerManualScan() }
        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                render()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        setupBottomNavigation()
        observeTransfers()
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
    }

    private fun triggerManualScan() {
        val request = OneTimeWorkRequestBuilder<PeriodicScanWorker>().build()
        WorkManager.getInstance(this).enqueue(request)
        toast(R.string.scan_started)
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

    // ---------- القائمة + البحث ----------

    private fun observeTransfers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.transfers.collect { list ->
                    fullList = list
                    render()
                }
            }
        }
    }

    private fun render() {
        val filtered = if (searchQuery.isBlank()) {
            fullList
        } else {
            fullList.filter {
                it.recipientName?.contains(searchQuery, ignoreCase = true) == true ||
                    it.senderName?.contains(searchQuery, ignoreCase = true) == true ||
                    it.bankId.contains(searchQuery, ignoreCase = true)
            }
        }
        val sorted = filtered.sortedByDescending { it.processedAt }
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

    // ---------- تعديل سجل يدوياً ----------

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
