package com.azzam.receiptscanner.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.azzam.receiptscanner.MainActivity
import com.azzam.receiptscanner.R
import com.azzam.receiptscanner.storage.TransferRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * ودجت الشاشة الرئيسية (مُطوّر في المرحلة 5).
 *
 * تحسينات:
 *  - يعرض الإجمالي وعدد الإيصالات (كما سابقاً)
 *  - يضيف سطر تنبيه أحمر للسجلات منخفضة الثقة (<0.5) التي تحتاج مراجعة يدوية
 *    — يختفي السطر تلقائياً عند عدم وجود سجلات منخفضة الثقة
 */
class TotalWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                renderWidgets(context, appWidgetManager, appWidgetIds)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        fun updateWidgets(context: Context, manager: AppWidgetManager, ids: IntArray) {
            CoroutineScope(Dispatchers.IO).launch {
                renderWidgets(context, manager, ids)
            }
        }

        private fun renderWidgets(context: Context, manager: AppWidgetManager, ids: IntArray) {
            // تحميل طازج من الملف المشفَّر مباشرة - الودجت قد يُحدَّث والتطبيق مغلق تماماً
            TransferRepository.forceReload(context)
            val transfers = TransferRepository.transfers.value
            val total = transfers.sumOf { it.amount ?: 0.0 }
            val count = transfers.size
            // عدد السجلات منخفضة الثقة التي تحتاج مراجعة يدوية
            val needsReview = transfers.count { it.confidence < 0.5f }
            val formatter = NumberFormat.getCurrencyInstance(Locale("ar", "SA"))

            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_total)
                views.setTextViewText(R.id.widgetTotal, formatter.format(total))
                views.setTextViewText(
                    R.id.widgetSubtitle,
                    context.getString(R.string.widget_subtitle_format, count)
                )

                // سطر التنبيه: يظهر فقط عند وجود سجلات منخفضة الثقة
                if (needsReview > 0) {
                    views.setViewVisibility(R.id.widgetAlert, View.VISIBLE)
                    views.setTextViewText(
                        R.id.widgetAlert,
                        context.getString(R.string.widget_alert_format, needsReview)
                    )
                } else {
                    views.setViewVisibility(R.id.widgetAlert, View.GONE)
                }

                val launchIntent = Intent(context, MainActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)

                manager.updateAppWidget(id, views)
            }
        }
    }
}
