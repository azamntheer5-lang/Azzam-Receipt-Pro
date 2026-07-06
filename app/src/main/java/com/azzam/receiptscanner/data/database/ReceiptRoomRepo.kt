package com.azzam.receiptscanner.data.database

import android.content.Context
import com.azzam.receiptscanner.data.dao.ReceiptDao
import com.azzam.receiptscanner.data.entity.AccountStatement
import com.azzam.receiptscanner.data.entity.ReceiptData
import com.azzam.receiptscanner.model.Transfer
import com.azzam.receiptscanner.storage.TransferRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * مستودع الوصول لقاعدة بيانات Room.
 *
 * يوفّر:
 *  1. مزامنة ثنائية: كل عملية على التخزين المشفّر القديم تنعكس على Room
 *     تلقائياً، مما يحافظ على مصدر واحد للحقيقة (Single Source of Truth)
 *     مع إبقاء التشفير القوي للبيانات.
 *  2. منطق "كشف الحسابات": تجميع الحوالات بالاسم + حساب الإجماليات.
 *
 * تصميم: يستخدم TransferRepository كمصدر للكتابة (المشفّر)، ويبني استعلامات
 * القراءة/التجميع على Room للأداء. هذا يفصل المخاوف (SoC) ويحافظ على
 * التوافق الخلفي مع الكود القائم.
 */
object ReceiptRoomRepo {

    private fun dao(context: Context): ReceiptDao = AppDatabase.get(context).receiptDao()

    // ---------- مزامنة من TransferRepository إلى Room ----------

    /** يُحدّث سجلاً في Room من Transfer. يستخدم upsert (REPLACE). */
    suspend fun upsertFromTransfer(context: Context, transfer: Transfer) {
        dao(context).upsert(transfer.toEntity())
    }

    /** يحذف سجلاً من Room. */
    suspend fun delete(context: Context, id: String) {
        dao(context).delete(id)
    }

    /**
     * يعيد بناء جدول Room بالكامل من التخزين المشفّر.
     * مفيد بعد استعادة نسخة احتياطية أو عند أول تشغيل.
     */
    suspend fun resyncFromEncryptedStore(context: Context) {
        TransferRepository.loadIfNeeded(context)
        val all = TransferRepository.transfers.value
        val dao = dao(context)
        dao.deleteAll()
        dao.upsertAll(all.map { it.toEntity() })
    }

    /** يراقب كل السجلات كـ Flow — للقائمة الرئيسية. */
    fun observeAll(context: Context): Flow<List<ReceiptData>> =
        dao(context).observeAll()

    /**
     * بحث متقدم موحّد عبر Flow — يدعم:
     *  - نص البحث (query) في الأسماء/المبلغ/البنك
     *  - فلتر بنك محدّد
     *  - فترة زمنية (dateFrom → dateTo)
     *
     * يتحدّث فورياً عند تغيّر أي معيار أو البيانات نفسها.
     */
    fun search(
        context: Context,
        query: String = "",
        bankId: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null
    ): Flow<List<ReceiptData>> =
        dao(context).search(query, bankId, dateFrom, dateTo)

    /** قائمة البنوك المتاحة للفلترة (يتم تحديثها تلقائياً). */
    fun observeDistinctBanks(context: Context): Flow<List<String>> =
        dao(context).observeDistinctBanks()

    // ---------- منطق كشف الحسابات ----------

    /**
     * يبني كشوف الحسابات لكل الأسماء المميّزة.
     *
     * لكل اسم، يُجمّع:
     *  - الإجمالي الكلي (مرسَل + مستلِم)
     *  - عدد الحوالات
     *  - القائمة التفصيلية
     *  - الدور: "sender" / "recipient" / "both"
     *
     * مرتّبة تنازلياً بالإجمالي (الأكثر تعاملاً أولاً).
     */
    suspend fun buildAllStatements(context: Context): List<AccountStatement> {
        val dao = dao(context)
        val names = dao.getAllDistinctNames()
        if (names.isEmpty()) return emptyList()

        return names.map { name ->
            val transfers = dao.getTransfersForName(name)
            val sent = dao.totalSentBy(name)
            val received = dao.totalReceivedBy(name)
            val countSent = dao.countSentBy(name)
            val countReceived = dao.countReceivedBy(name)
            val role = when {
                countSent > 0 && countReceived > 0 -> "both"
                countSent > 0 -> "sender"
                else -> "recipient"
            }
            AccountStatement(
                name = name,
                role = role,
                transferCount = transfers.size,
                totalAmount = sent + received,
                transfers = transfers
            )
        }.sortedByDescending { it.totalAmount }
    }

    /** كشف حساب لاسم واحد فقط (شاشة التفاصيل). */
    suspend fun buildStatementFor(context: Context, name: String): AccountStatement? {
        val dao = dao(context)
        val transfers = dao.getTransfersForName(name)
        if (transfers.isEmpty()) return null
        val sent = dao.totalSentBy(name)
        val received = dao.totalReceivedBy(name)
        val countSent = dao.countSentBy(name)
        val countReceived = dao.countReceivedBy(name)
        val role = when {
            countSent > 0 && countReceived > 0 -> "both"
            countSent > 0 -> "sender"
            else -> "recipient"
        }
        return AccountStatement(
            name = name,
            role = role,
            transferCount = transfers.size,
            totalAmount = sent + received,
            transfers = transfers
        )
    }
}

/** تحويل من نموذج Transfer (التخزين المشفّر) إلى ReceiptData (Room). */
private fun Transfer.toEntity(): ReceiptData = ReceiptData(
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
    llmEngineUsed = llmEngineUsed
)
