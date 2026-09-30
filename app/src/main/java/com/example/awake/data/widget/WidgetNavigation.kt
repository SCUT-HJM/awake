package com.example.awake.data.widget

object WidgetNavigation {
    const val ACTION_OPEN_WEEK = "com.example.awake.widget.OPEN_WEEK"
    const val EXTRA_TIMETABLE_ID = "com.example.awake.widget.TIMETABLE_ID"
    const val EXTRA_WEEK = "com.example.awake.widget.WEEK"
}

data class WidgetOpenRequest(
    val timetableId: Long?,
    val week: Int?,
    val token: Long = System.nanoTime()
)
