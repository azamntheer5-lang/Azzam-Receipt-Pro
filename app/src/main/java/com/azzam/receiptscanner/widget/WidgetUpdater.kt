package com.azzam.receiptscanner.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

object WidgetUpdater {
    /** يُستدعى بعد أي تعديل على البيانات (إضافة/تعديل/حذف) لتحديث الودجت فوراً بدل انتظار دورة التحديث التلقائية. */
    fun notifyDataChanged(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, TotalWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            TotalWidgetProvider.updateWidgets(context, manager, ids)
        }
    }
}
