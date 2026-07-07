package com.azzam.receiptscanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.FileObserver
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.azzam.receiptscanner.processing.ReceiptProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class ReceiptWatcherService : Service() {

    companion object {
        const val CHANNEL_ID = "receipt_watcher_channel"
        const val NOTIFICATION_ID = 1001

        // المسارات الشائعة لواتساب العادي والبزنس. قد تختلف قليلاً حسب
        // الجهاز/الإصدار - إن لم تُكتشف إيصالاتك، تحقق من المسار الفعلي
        // عبر مدير ملفات وعدّل هذه القائمة.
        val WHATSAPP_PATHS = listOf(
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
            "/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images",
            "/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Documents"
        )
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observers = mutableListOf<FileObserver>()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startWatching()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startWatching() {
        WHATSAPP_PATHS.forEach { path ->
            val dir = File(path)
            if (!dir.exists()) return@forEach

            val observer = object : FileObserver(dir, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, relativePath: String?) {
                    relativePath ?: return
                    val file = File(dir, relativePath)
                    serviceScope.launch {
                        ReceiptProcessor.processFile(applicationContext, file)
                    }
                }
            }
            observer.startWatching()
            observers.add(observer)
        }
    }

    // مهم لأندرويد 15+: خدمات dataSync محدودة بـ6 ساعات تشغيل كل 24 ساعة.
    // بعدها يستدعي النظام هذه الدالة، ونوقف الخدمة بأدب. PeriodicScanWorker
    // (المسح كل 15 دقيقة) يغطي بقية اليوم، والخدمة تُعاد تلقائياً عند فتح التطبيق.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.watcher_notification_title),
            NotificationManager.IMPORTANCE_MIN
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.watcher_notification_title))
            .setContentText(getString(R.string.watcher_notification_desc))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
