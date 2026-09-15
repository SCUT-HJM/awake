package com.example.awake.data.widget

import com.example.awake.data.local.TimetableEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 全应用统一的“今天在第几周”计算，避免主界面和小组件各自实现产生偏差。 */
internal fun currentWeekOf(timetable: TimetableEntity?, today: LocalDate = LocalDate.now()): Int? {
    val startDate = timetable?.startDate ?: return null
    return runCatching {
        val start = LocalDate.parse(startDate)
        val days = ChronoUnit.DAYS.between(start, today)
        (days / 7L).toInt() + 1
    }.getOrNull()?.coerceIn(1, 30)
}
