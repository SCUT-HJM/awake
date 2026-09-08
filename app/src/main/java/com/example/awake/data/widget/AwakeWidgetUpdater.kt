package com.example.awake.data.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** 请求所有 Awake 课表小组件重新读取当前选中的本地课表。 */
object AwakeWidgetUpdater {
    const val EXTRA_RESET_TO_CURRENT_WEEK = "com.example.awake.widget.RESET_TO_CURRENT_WEEK"

    fun requestUpdate(context: Context, resetToCurrentWeek: Boolean = true) {
        val appContext = context.applicationContext
        val component = ComponentName(appContext, AwakeWidgetProvider::class.java)
        val ids = AppWidgetManager.getInstance(appContext).getAppWidgetIds(component)
        if (ids.isEmpty()) return
        appContext.sendBroadcast(
            Intent(appContext, AwakeWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                putExtra(EXTRA_RESET_TO_CURRENT_WEEK, resetToCurrentWeek)
            }
        )
    }
}
