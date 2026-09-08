package com.example.awake.data.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * 监听息屏事件并按用户设置刷新小组件。
 * Android 不提供“小组件所在桌面页可见/不可见”的公开事件，
 * 这里用息屏这个系统信号做可靠触发点。
 */
class AwakeWidgetRefreshController(context: Context) {
    private val appContext = context.applicationContext
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prefs = AwakeWidgetPrefs(context)
            val shouldRefresh = when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> prefs.screenOffRefresh()
                else -> false
            }
            if (shouldRefresh) AwakeWidgetUpdater.requestUpdate(context)
        }
    }

    @Synchronized
    fun applySettings() {
        val prefs = AwakeWidgetPrefs(appContext)
        val shouldRegister = prefs.screenOffRefresh()
        if (shouldRegister && !registered) {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registered = true
        } else if (!shouldRegister && registered) {
            appContext.unregisterReceiver(receiver)
            registered = false
        }
    }
}
