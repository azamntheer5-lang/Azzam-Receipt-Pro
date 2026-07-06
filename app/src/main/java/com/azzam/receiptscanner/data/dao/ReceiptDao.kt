package com.azzam.receiptscanner.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.azzam.receiptscanner.data.entity.ReceiptData
import kotlinx.coroutines.flow.Flow

/**
 * كائن الوصول للبيانات (DAO) لجدول receipts.
 *
 * يوفّر استعلامات لـ:
 *  - الإدراج/التحديث/الحذف
 *  - جلب كل السجلات (Flow reactive) للقائمة الرئيسية
 *  - استعلامات كشف الحسابات: تجميع بالاسم (مرسل/مستلم)
 */
@Dao
interface ReceiptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(receipt: ReceiptData)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(receipts: List<ReceiptData>)

    @Query("SELECT * FROM receipts ORDER BY processedAt DESC")
    fun observeAll(): Flow<List<ReceiptData>>

    @Query("SELECT * FROM receipts ORDER BY processedAt DESC")
    suspend fun getAll(): List<ReceiptData>

    // ---------- استعلامات البحث المتقدم ----------

    /**
     * بحث فوري (Live Search) متعدد المعايير عبر Flow.
     * @param query نص البحث (senderName/recipientName/amount/bankId) — فارغ = الكل
     * @param bankId فلتر البنك — null = كل البنوك
     * @param dateFrom تاريخ البداية (yyyy-MM-dd) — null = بدون حد أدنى
     * @param dateTo تاريخ النهاية (yyyy-MM-dd) — null = بدون حد أعلى
     */
    @Query("""
        SELECT * FROM receipts
        WHERE (:query = '' OR
               senderName LIKE '%' || :query || '%' OR
               recipientName LIKE '%' || :query || '%' OR
               CAST(amount AS TEXT) LIKE '%' || :query || '%' OR
               bankId LIKE '%' || :query || '%')
          AND (:bankId IS NULL OR bankId = :bankId)
          AND (:dateFrom IS NULL OR date >= :dateFrom)
          AND (:dateTo IS NULL OR date <= :dateTo)
        ORDER BY processedAt DESC
    """)
    fun search(
        query: String,
        bankId: String?,
        dateFrom: String?,
        dateTo: String?
    ): Flow<List<ReceiptData>>

    /** قائمة البنوك المختلفة الموجودة في السجلات (لزرائر الفلترة). */
    @Query("SELECT DISTINCT bankId FROM receipts ORDER BY bankId")
    fun observeDistinctBanks(): Flow<List<String>>

    @Query("SELECT * FROM receipts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ReceiptData?

    @Query("UPDATE receipts SET senderName = :sender, recipientName = :recipient, amount = :amount, date = :date, confidence = 1.0 WHERE id = :id")
    suspend fun updateFields(
        id: String,
        sender: String?,
        recipient: String?,
        amount: Double?,
        date: String?
    )

    @Query("DELETE FROM receipts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM receipts")
    suspend fun deleteAll()

    // ---------- استعلامات كشف الحسابات ----------

    /** كل حوالات شخص بعينه (كمرسل أو مستلم). */
    @Query(
        """
        SELECT * FROM receipts
        WHERE senderName = :name OR recipientName = :name
        ORDER BY processedAt DESC
        """
    )
    suspend fun getTransfersForName(name: String): List<ReceiptData>

    /** كل الأسماء المميزة (مرسلين + مستلمين) غير الفارغة — لعرض القائمة المجمّعة. */
    @Query(
        """
        SELECT DISTINCT name FROM (
            SELECT senderName AS name FROM receipts WHERE senderName IS NOT NULL AND senderName != ''
            UNION
            SELECT recipientName AS name FROM receipts WHERE recipientName IS NOT NULL AND recipientName != ''
        )
        ORDER BY name
        """
    )
    suspend fun getAllDistinctNames(): List<String>

    /** إجمالي مبالغ شخص كمرسِل (أرسلها). */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM receipts WHERE senderName = :name")
    suspend fun totalSentBy(name: String): Double

    /** إجمالي مبالغ شخص كمستلِم (استلمها). */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM receipts WHERE recipientName = :name")
    suspend fun totalReceivedBy(name: String): Double

    /** عدد حوالات شخص كمرسِل. */
    @Query("SELECT COUNT(*) FROM receipts WHERE senderName = :name")
    suspend fun countSentBy(name: String): Int

    /** عدد حوالات شخص كمستلِم. */
    @Query("SELECT COUNT(*) FROM receipts WHERE recipientName = :name")
    suspend fun countReceivedBy(name: String): Int

    // ---------- استعلامات Dashboard ----------

    /** إجمالي المبالغ لكل بنك (لتوزيع النسب المئوية). */
    @Query("SELECT bankId AS bank, COALESCE(SUM(amount), 0) AS total, COUNT(*) AS count FROM receipts WHERE amount IS NOT NULL GROUP BY bankId ORDER BY total DESC")
    suspend fun totalsByBank(): List<BankTotal>

    /** عدد السجلات لكل مستوى ثقة. */
    @Query("SELECT CASE WHEN confidence >= 0.85 THEN 'high' WHEN confidence >= 0.5 THEN 'medium' ELSE 'low' END AS level, COUNT(*) AS count FROM receipts GROUP BY level")
    suspend fun countsByConfidence(): List<ConfidenceCount>
}

/** نتيجة استعلام إجمالي البنك. */
data class BankTotal(
    val bank: String,
    val total: Double,
    val count: Int
)

/** نتيجة استعلام عدّ الثقة. */
data class ConfidenceCount(
    val level: String,
    val count: Int
)
