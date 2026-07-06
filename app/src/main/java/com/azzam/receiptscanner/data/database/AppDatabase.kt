package com.azzam.receiptscanner.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.azzam.receiptscanner.data.dao.ReceiptDao
import com.azzam.receiptscanner.data.entity.ReceiptData

/**
 * قاعدة بيانات Room الرئيسية للتطبيق.
 *
 * التطوير (المرحلة 2): إضافة طبقة Room لحفظ ReceiptData، مما يتيح استعلامات
 * كشف الحسابات (Group By) بكفاءة. تتعايش مع التخزين المشفّر القديم
 * (TransferRepository/transfers.enc) عبر مزامنة ثنائية الاتجاه في ReceiptRoomRepo.
 *
 * لماذا Room الآن؟ استعلامات التجميع (Group By/Sum) على قائمة in-memory
 * مكلفة مع نمو البيانات؛ Room يفهرس الأسماء ويُنفّذها بسرعة SQL.
 */
@Database(
    entities = [ReceiptData::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun receiptDao(): ReceiptDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "receipt_scanner.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
