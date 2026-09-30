package com.example.awake

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.awake.data.widget.AwakeWidgetUpdater
import com.example.awake.data.widget.WidgetNavigation
import com.example.awake.data.widget.WidgetOpenRequest
import com.example.awake.ui.navigation.AppNavHost
import com.example.awake.ui.theme.AwakeTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val widgetOpenRequest = MutableStateFlow<WidgetOpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleWidgetIntent(intent)
        val container = (application as AwakeApplication).container
        container.updateManager.checkMajorUpdate()
        setContent {
            val themeMode by container.themeModeFlow.collectAsState()
            AwakeTheme(themeMode = themeMode) {
                AppNavHost(
                    container = container,
                    widgetOpenRequest = widgetOpenRequest,
                    onWidgetOpenConsumed = { widgetOpenRequest.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: Intent?) {
        if (intent?.action != WidgetNavigation.ACTION_OPEN_WEEK) return
        widgetOpenRequest.value = WidgetOpenRequest(
            timetableId = intent.takeIf { it.hasExtra(WidgetNavigation.EXTRA_TIMETABLE_ID) }
                ?.getLongExtra(WidgetNavigation.EXTRA_TIMETABLE_ID, -1L)
                ?.takeIf { it >= 0L },
            week = intent.takeIf { it.hasExtra(WidgetNavigation.EXTRA_WEEK) }
                ?.getIntExtra(WidgetNavigation.EXTRA_WEEK, -1)
                ?.takeIf { it in 1..30 }
        )
    }

    override fun onResume() {
        super.onResume()
        if ((application as AwakeApplication).container.updateManager.state.value.readyInstall) {
            (application as AwakeApplication).container.updateManager.installDownloaded()
        }
        AwakeWidgetUpdater.requestUpdate(this)
    }
}
