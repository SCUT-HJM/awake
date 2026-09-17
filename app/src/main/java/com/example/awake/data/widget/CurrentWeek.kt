package com.example.awake.data.widget

import com.example.awake.data.local.TimetableEntity
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 兼容旧数据里手动输入的 `2026-9-7` 等非补零格式。 */
internal val timetableDateParser: DateTimeFormatter = DateTimeFormatterBuilder()
    .appendPattern("yyyy")
    .appendLiteral('-')
    .appendValue(ChronoField.MONTH_OF_YEAR)
    .appendLiteral('-')
    .appendValue(ChronoField.DAY_OF_MONTH)
    .toFormatter()

internal fun parseTimetableDate(value: String?): LocalDate? {
    val text = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    return runCatching { LocalDate.parse(text, timetableDateParser) }.getOrNull()
}

/** 全应用统一的“今天在第几周”计算，避免主界面和小组件各自实现产生偏差。 */
internal fun currentWeekOf(timetable: TimetableEntity?, today: LocalDate = LocalDate.now()): Int? {
    val start = parseTimetableDate(timetable?.startDate) ?: return null
    val days = ChronoUnit.DAYS.between(start, today)
    return ((days / 7L).toInt() + 1).coerceIn(1, 30)
}
