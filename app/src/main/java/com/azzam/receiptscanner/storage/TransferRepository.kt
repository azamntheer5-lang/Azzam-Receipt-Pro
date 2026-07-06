package com.azzam.receiptscanner.storage

import android.content.Context
import com.azzam.receiptscanner.data.database.ReceiptRoomRepo
import com.azzam.receiptscanner.model.Transfer
import com.azzam.receiptscanner.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * مخزن بيانات بسيط: قائمة التحويلات محفوظة كملف JSON واحد مشفّر بالكامل
 * (عبر SecureStorage) + مرآة في Room لكفاءة استعلامات كشف الحسابات.
 *
 * التطوير (المرحلة 2): كل عملية كتابة (add/update/delete/replaceAll) تنعكس
 * تلقائياً على Room عبر ReceiptRoomRepo في coroutine خلفية. هذا يحافظ على
 * مصدر واحد للحقيقة (Single Source of Truth) مع إبقاء التشفير القوي.
 */
object TransferRepository {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    private var loaded = false
    // scope خلفي لمزامنة Room دون حظر الكاتب
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun dataFile(context: Context): File =
        File(context.filesDir, "transfers.enc")

    @Synchronized
    fun loadIfNeeded(context: Context) {
        if (loaded) return
        loaded = true
        val decrypted = SecureStorage.readAndDecrypt(dataFile(context))
        if (decrypted != null) {
            runCatching {
                _transfers.value = json.decodeFromString<List<Transfer>>(decrypted)
            }
        }
        // زامن Room عند أول تحميل
        syncScope.launch { ReceiptRoomRepo.resyncFromEncryptedStore(context) }
    }

    /** يجبر إعادة التحميل من القرص - مفيد بعد استعادة نسخة احتياطية. */
    @Synchronized
    fun forceReload(context: Context) {
        loaded = false
        loadIfNeeded(context)
    }

    @Synchronized
    fun addTransfer(context: Context, transfer: Transfer) {
        loadIfNeeded(context)
        val updated = _transfers.value + transfer
        _transfers.value = updated
        persist(context, updated)
        syncScope.launch { ReceiptRoomRepo.upsertFromTransfer(context, transfer) }
    }

    @Synchronized
    fun updateTransfer(context: Context, updated: Transfer) {
        loadIfNeeded(context)
        val newList = _transfers.value.map { if (it.id == updated.id) updated else it }
        _transfers.value = newList
        persist(context, newList)
        syncScope.launch { ReceiptRoomRepo.upsertFromTransfer(context, updated) }
    }

    @Synchronized
    fun deleteTransfer(context: Context, id: String) {
        loadIfNeeded(context)
        val updated = _transfers.value.filterNot { it.id == id }
        _transfers.value = updated
        persist(context, updated)
        syncScope.launch { ReceiptRoomRepo.delete(context, id) }
    }

    /** يستبدل القائمة كاملة دفعة واحدة - يُستخدم عند استعادة نسخة احتياطية. */
    @Synchronized
    fun replaceAll(context: Context, newList: List<Transfer>) {
        loaded = true
        _transfers.value = newList
        persist(context, newList)
        syncScope.launch { ReceiptRoomRepo.resyncFromEncryptedStore(context) }
    }

    private fun persist(context: Context, list: List<Transfer>) {
        val text = json.encodeToString(list)
        SecureStorage.encryptAndWrite(dataFile(context), text)
        WidgetUpdater.notifyDataChanged(context)
    }

    fun totalAmount(): Double = _transfers.value.sumOf { it.amount ?: 0.0 }

    fun rawJsonForBackup(): String = json.encodeToString(_transfers.value)

    fun parseBackupJson(text: String): List<Transfer> = json.decodeFromString(text)

    fun monthlyTotals(): List<Pair<String, Double>> {
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.US)
        val grouped = _transfers.value
            .filter { it.amount != null }
            .groupBy { monthFormat.format(it.processedAt) }
            .mapValues { entry -> entry.value.sumOf { it.amount ?: 0.0 } }
        return grouped.toList().sortedBy { it.first }
    }

    fun topCounterparties(limit: Int = 5): List<Pair<String, Double>> {
        return _transfers.value
            .mapNotNull { t ->
                val name = t.recipientName?.takeIf { it.isNotBlank() }
                    ?: t.senderName?.takeIf { it.isNotBlank() }
                if (name != null && t.amount != null) name to t.amount else null
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }
            .toList()
            .sortedByDescending { it.second }
            .take(limit)
    }
}
