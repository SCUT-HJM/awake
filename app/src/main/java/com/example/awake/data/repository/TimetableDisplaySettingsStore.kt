package com.example.awake.data.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 课表显示偏好，设置页与课表页共享同一个进程内状态并持久化到本地。 */
class TimetableDisplaySettingsStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "awake_timetable_display_settings"
        private const val KEY_SHOW_OTHER_WEEKS = "show_other_weeks"
        private const val KEY_PERIODS_PER_SCREEN = "periods_per_screen"
        const val DEFAULT_PERIODS_PER_SCREEN = 9
        const val MIN_PERIODS_PER_SCREEN = 6
        const val MAX_PERIODS_PER_SCREEN = 14
        const val DEFAULT_SHOW_OTHER_WEEKS = true
    }

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _showOtherWeeks = MutableStateFlow(
        preferences.getBoolean(KEY_SHOW_OTHER_WEEKS, DEFAULT_SHOW_OTHER_WEEKS)
    )
    val showOtherWeeks: StateFlow<Boolean> = _showOtherWeeks.asStateFlow()

    /** 一屏希望看到的节次数；仅用于推算纵向行高，所有节次仍会渲染并可滚动。 */
    private val _periodsPerScreen = MutableStateFlow(
        preferences.getInt(KEY_PERIODS_PER_SCREEN, DEFAULT_PERIODS_PER_SCREEN)
            .coerceIn(MIN_PERIODS_PER_SCREEN, MAX_PERIODS_PER_SCREEN)
    )
    val periodsPerScreen: StateFlow<Int> = _periodsPerScreen.asStateFlow()

    /** 主课表页底部“课表纵向长度”编辑框，从设置页请求打开。 */
    private val _showLengthEditor = MutableStateFlow(false)
    val showLengthEditor: StateFlow<Boolean> = _showLengthEditor.asStateFlow()

    fun setShowOtherWeeks(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_OTHER_WEEKS, enabled).apply()
        _showOtherWeeks.value = enabled
    }

    fun setPeriodsPerScreen(count: Int) {
        val bounded = count.coerceIn(MIN_PERIODS_PER_SCREEN, MAX_PERIODS_PER_SCREEN)
        preferences.edit().putInt(KEY_PERIODS_PER_SCREEN, bounded).apply()
        _periodsPerScreen.value = bounded
    }

    fun requestShowLengthEditor() {
        _showLengthEditor.value = true
    }

    fun closeLengthEditor() {
        _showLengthEditor.value = false
    }
}
