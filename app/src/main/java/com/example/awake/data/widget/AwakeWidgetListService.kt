package com.example.awake.data.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.awake.AwakeApplication
import com.example.awake.R
import com.example.awake.data.local.CourseSlotEntity
import com.example.awake.data.local.PeriodConfigDefaults
import com.example.awake.data.local.TimetableEntity
import com.example.awake.data.repository.TimetableDisplaySettingsStore
import com.example.awake.domain.parser.WeekExpressionParser
import com.example.awake.ui.components.weekParityLabel
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 周课表集合数据源：这是官方支持的组件滚动方案（RemoteViews 不支持 ScrollView）。
 *
 * 行模型与 App 内周视图对齐：
 * - 按所有课程的时间边界把 1..periodCount 切成若干分段，含课程的段用一整行渲染
 *   （行高 = 节次数 × 50dp），课程合并为一整块；跨段课程用 full/top/mid/bottom
 *   拼块 drawable 无缝拼成整块；空段拆成单节行，保留每个节次的标签和时间；
 * - 非本周课程与 App 内一致：跟随显示（设置页「显示非本周」）、42% 淡化、
 *   单双周标注（[weekParityLabel] 与 App 的 pill 同规则）；
 * - 课程格配色与 App 同源：[WidgetPalette.paletteForAccent] 复刻 CourseCard 的
 *   HSV 算法，「外层描边层 + 内缩 1dp 填充层」实现 1dp accent 描边观感。
 */
class AwakeWidgetListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        return WeekRowsFactory(applicationContext, widgetId)
    }

    private class WeekRowsFactory(
        private val context: Context,
        private val widgetId: Int
    ) : RemoteViewsFactory {

        /** 课程格在某个分段行中的拼块类型：整块 / 块起始 / 块中段 / 块收尾。 */
        private enum class PieceKind { FULL, TOP, MID, BOTTOM }

        private data class CellPiece(
            val course: CourseSlotEntity,
            /** 是否属于当前查看的周；false 时整卡淡化并按需标注单双周。 */
            val inWeek: Boolean,
            val kind: PieceKind
        )

        /** 一个分段行：span 为覆盖的节次数；课程格合并为整块，但时间列保持逐节标签
         *  （labels 与节次一一对应，各占 50dp，与课程块的节次切片对齐）。 */
        private data class SegmentRow(
            val span: Int,
            val labels: List<Pair<String, String>>,
            val pieces: Map<Int, CellPiece>,
            val isEmptyGroup: Boolean = false,
            val emptyLabel: String = ""
        )

        private val rows = mutableListOf<SegmentRow>()
        private var dark = false
        private var widgetBackground = 0
        private var viewedWeek = 1
        private var totalWeeks = 30

        override fun onCreate() = Unit

        // Factory 会被多个调用方并发访问（notify 触发的 onDataSetChanged 与列表滚动的
        // getViewAt），rows 只在加锁后整体替换/读取，避免读到 clear 到一半的列表。
        @Synchronized
        override fun onDataSetChanged() {
            rows.clear()
            // 深浅模式与组件底色在每次数据刷新时重算，保证深浅切换后下一帧即用新配色。
            dark = WidgetPalette.isDark(context)
            widgetBackground = WidgetPalette.surface(context, dark)
            val app = context.applicationContext as? AwakeApplication ?: return
            rows.addAll(
                runBlocking(Dispatchers.IO) {
                    // 单次刷新失败不能让 Factory 崩掉（否则列表永久停留旧数据），兜底为空行。
                    runCatching { loadRows(app) }.getOrElse { emptyList() }
                }
            )
        }

        private suspend fun loadRows(app: AwakeApplication): List<SegmentRow> {
            val local = app.container.localRepository
            val prefs = AwakeWidgetPrefs(context)
            val timetable = prefs.timetableId(widgetId)
                ?.let { local.getTimetableOrNull(it) }
                ?: app.container.timetableSelectionStore.read()?.let { local.getTimetableOrNull(it) }
                ?: local.getFirstTimetable()
                ?: return emptyList()

            val totalWeeks = timetable.totalWeeks.coerceIn(1, 30)
            this.totalWeeks = totalWeeks
            val storedWeek = prefs.week(widgetId)
            val week = if (storedWeek in 1..totalWeeks) storedWeek else currentWeekOf(timetable)
            viewedWeek = week
            // 候选课程与 App 内完全同语义：
            // - 「显示非本周」开（默认）：本周或未来仍有课的时段（observeSlotsThroughEnd）；
            // - 关：仅本周时段（observeSlotsForWeek）。
            // 周次一律重新解析 rawWeekText；解析不出周次的时段两侧查询都不会返回，同样排除。
            val showOtherWeeks = TimetableDisplaySettingsStore(context).showOtherWeeks.value
            val weeksBySection = HashMap<Long, Set<Int>>()
            val candidates = local.getAllSlots(timetable.id).filter { slot ->
                val weeks = WeekExpressionParser.parse(slot.rawWeekText, totalWeeks).weeks
                weeksBySection[slot.sectionId] = weeks
                if (weeks.isEmpty()) {
                    false
                } else {
                    week in weeks || (showOtherWeeks && weeks.any { it >= week })
                }
            }

            // 普通无课节次也要占位；最多受小组件内置 14 节布局限制。
            val periodCount = maxOf(
                PeriodConfigDefaults.periodCount,
                local.getPeriodConfigsFor(timetable).maxOfOrNull { it.period } ?: 0,
                candidates.maxOfOrNull { it.endPeriod } ?: 0
            ).coerceIn(1, 14)
            val periods = local.getPeriodConfigsFor(timetable).associateBy { it.period }

            // 每天做与 App 内 selectVisibleVariants 相同的变体去重（同一时段只显示最合适的版本），
            // 再按节次铺进单元格；同一时段冲突时先开始的课程优先（与旧行为一致）。
            val courseByCell = HashMap<Pair<Int, Int>, Pair<CourseSlotEntity, Boolean>>()
            (1..7).forEach { day ->
                candidates.filter { it.dayOfWeek == day }
                    .let { selectVisibleVariants(it, week) }
                    .sortedWith(
                        compareBy(
                            { it.startPeriod },
                            { earliestWeek(it.rawWeekText) },
                            { it.endPeriod },
                            { it.name }
                        )
                    )
                    .forEach { course ->
                        val inWeek = weeksBySection[course.sectionId]?.contains(week) == true
                        val endEff = course.endPeriod.coerceAtMost(periodCount)
                        for (period in course.startPeriod..endEff) {
                            courseByCell.putIfAbsent(day to period, course.copy(endPeriod = endEff) to inWeek)
                        }
                    }
            }
            if (courseByCell.isEmpty()) return rowsBetween(periods, 1, periodCount + 1)

            // 小组件要和主界面一样把同一门课连续节次合并成一整块。
            // 否则有些数据会按节次拆成多个相邻 section，组件就会把一块课程拆成
            // TOP/BOTTOM 两行，文字只出现在第一行，看起来不是居中的。
            val mergedCourseByCell = HashMap<Pair<Int, Int>, Pair<CourseSlotEntity, Boolean>>()
            (1..7).forEach { day ->
                var period = 1
                while (period <= periodCount) {
                    val current = courseByCell[day to period]
                    if (current == null) {
                        period++
                        continue
                    }
                    val (course, inWeek) = current
                    var endPeriod = period
                    while (endPeriod + 1 <= periodCount) {
                        val next = courseByCell[day to endPeriod + 1] ?: break
                        val (nextCourse, nextInWeek) = next
                        val canMerge = nextCourse.courseId == course.courseId &&
                            nextCourse.room == course.room &&
                            nextCourse.teacher == course.teacher &&
                            nextCourse.rawWeekText == course.rawWeekText &&
                            nextCourse.source == course.source &&
                            nextInWeek == inWeek
                        if (!canMerge) break
                        endPeriod++
                    }
                    // 关键：同一门课合并后，仍要把每个被覆盖的节次都放进 map。
                    // 否则边界行（例如 4）查不到从 3 开始的课，课程底部会被画成空白。
                    val merged = course.copy(startPeriod = period, endPeriod = endPeriod) to inWeek
                    for (covered in period..endPeriod) {
                        mergedCourseByCell[day to covered] = merged
                    }
                    period = endPeriod + 1
                }
            }
            courseByCell.clear()
            courseByCell.putAll(mergedCourseByCell)

            // 分段边界 = 所有渲染课程的起点与终点+1。边界可以把课程分成 TOP/MID/BOTTOM，
            // 关键是每行都按“当前段起点覆盖到的课程”取数据，而不是只查课程的起始节次。
            val bounds = sortedSetOf(1, periodCount + 1)
            courseByCell.values.map { it.first }.forEach { course ->
                bounds.add(course.startPeriod.coerceIn(1, periodCount))
                bounds.add(course.endPeriod.coerceAtMost(periodCount) + 1)
            }
            val list = bounds.toList()
            val courseSegments = mutableListOf<Triple<Int, Int, Map<Int, CellPiece>>>()
            (0 until list.size - 1).forEach { index ->
                val a = list[index]
                val b = list[index + 1]
                val pieces = (1..7).mapNotNull { day ->
                    courseByCell[day to a]?.let { (course, inWeek) ->
                        val kind = when {
                            course.startPeriod >= a && course.endPeriod <= b - 1 -> PieceKind.FULL
                            course.startPeriod >= a -> PieceKind.TOP
                            course.endPeriod <= b - 1 -> PieceKind.BOTTOM
                            else -> PieceKind.MID
                        }
                        day to CellPiece(course, inWeek, kind)
                    }
                }.toMap()
                if (pieces.isNotEmpty()) {
                    courseSegments.add(Triple(a, b, pieces))
                }
            }

            val result = mutableListOf<SegmentRow>()
            var cursor = 1
            courseSegments.forEach { (a, b, pieces) ->
                result.addAll(rowsBetween(periods, cursor, a))
                val labels = (a until b).map { periodLabels(periods, it) }
                result.add(SegmentRow(span = b - a, labels = labels, pieces = pieces))
                cursor = b
            }
            result.addAll(rowsBetween(periods, cursor, periodCount + 1))
            return result
        }

        /** 在课程段之间补齐普通无课行；连续同类型放空节次仍合并成一个淡灰色块。 */
        private fun rowsBetween(
            periods: Map<Int, com.example.awake.data.local.PeriodConfigEntity>,
            from: Int,
            untilExclusive: Int
        ): List<SegmentRow> {
            val rows = mutableListOf<SegmentRow>()
            var cursor = from
            while (cursor < untilExclusive) {
                val config = periods[cursor]
                if (config?.isEmpty == true) {
                    var end = cursor
                    while (end + 1 < untilExclusive) {
                        val next = periods[end + 1]?.takeIf { it.isEmpty } ?: break
                        if (next.emptyType != config.emptyType) break
                        end++
                    }
                    rows.addAll(emptyRows(periods, cursor, end + 1))
                    cursor = end + 1
                } else {
                    var end = cursor
                    while (end + 1 < untilExclusive) {
                        val next = periods[end + 1]
                        if (next?.isEmpty == true) break
                        end++
                    }
                    rows.add(
                        SegmentRow(
                            span = end - cursor + 1,
                            labels = (cursor..end).map { periodLabels(periods, it) },
                            pieces = emptyMap()
                        )
                    )
                    cursor = end + 1
                }
            }
            return rows
        }

        /** 连续同类型放空节次（午休/晚休）在小组件里同样合并成一个淡灰色块。 */
        private fun emptyRows(
            periods: Map<Int, com.example.awake.data.local.PeriodConfigEntity>,
            from: Int,
            untilExclusive: Int
        ): List<SegmentRow> {
            val rows = mutableListOf<SegmentRow>()
            var cursor = from
            while (cursor < untilExclusive) {
                val config = periods[cursor]?.takeIf { it.isEmpty } ?: run {
                    cursor++
                    null
                } ?: continue
                var end = cursor
                while (end + 1 < untilExclusive) {
                    val next = periods[end + 1]?.takeIf { it.isEmpty } ?: break
                    if (next.emptyType != config.emptyType) break
                    end++
                }
                rows.add(
                    SegmentRow(
                        span = end - cursor + 1,
                        labels = emptyList(),
                        pieces = emptyMap(),
                        isEmptyGroup = true,
                        emptyLabel = config.emptyLabel
                    )
                )
                cursor = end + 1
            }
            return rows
        }

        /** 单个节次的时间列标签：节次号 + 起止时间。 */
        /** 单个节次的时间列标签：节次号 + 起止时间。 */
        private fun periodLabels(
            periods: Map<Int, com.example.awake.data.local.PeriodConfigEntity>,
            period: Int
        ): Pair<String, String> {
            val config = periods[period]
            return period.toString() to when (config) {
                null -> ""
                else -> "${config.startTime}\n${config.endTime}"
            }
        }

        @Synchronized
        override fun getCount(): Int = rows.size

        @Synchronized
        override fun getViewAt(position: Int): RemoteViews {
            val row = rows[position]
            if (row.isEmptyGroup) {
                val empty = RemoteViews(context.packageName, R.layout.widget_week_empty)
                empty.setInt(R.id.widget_empty_bg, "setBackgroundColor", WidgetPalette.emptyPeriodBackground(dark))
                empty.setTextViewText(R.id.widget_empty_tx, row.emptyLabel)
                empty.setTextColor(R.id.widget_empty_tx, WidgetPalette.textSecondary(dark))
                return empty
            }
            val layout = rowLayouts[(row.span - 1).coerceIn(rowLayouts.indices)]
            val views = RemoteViews(context.packageName, layout)
            // 时间列逐节渲染：labels 与行内节次一一对应（布局里的 widget_rl_0..N / widget_rlt_0..N）。
            // 标签颜色同样按深浅显式设置（XML 只在 inflate 时解析一次，切主题后不跟随）。
            row.labels.forEachIndexed { index, (number, times) ->
                views.setTextViewText(labelNumIds[index], number)
                views.setTextViewText(labelTimeIds[index], times)
                views.setTextColor(labelNumIds[index], WidgetPalette.textPrimary(dark))
                views.setTextColor(labelTimeIds[index], WidgetPalette.textSecondary(dark))
            }
            (1..7).forEach { day ->
                val borderId = borderIds[day - 1]
                val fillId = fillIds[day - 1]
                val txId = textIds[day - 1]
                val piece = row.pieces[day]
                if (piece == null) {
                    views.setImageViewResource(borderId, 0)
                    views.setImageViewResource(fillId, 0)
                    views.setTextViewText(txId, "")
                } else {
                    val palette = WidgetPalette.paletteForAccent(piece.course.color, dark)
                    // 非本周课程整卡淡化：把 42% 透明度预混到组件底色上（视觉等价 App 内 alpha）。
                    val borderBase = WidgetPalette.borderColor(palette.accent, widgetBackground)
                    val border = if (piece.inWeek) borderBase else WidgetPalette.fadeOnto(borderBase, widgetBackground)
                    val fill = if (piece.inWeek) palette.background else WidgetPalette.fadeOnto(palette.background, widgetBackground)
                    val text = if (piece.inWeek) {
                        WidgetPalette.onSurface(context, dark)
                    } else {
                        WidgetPalette.fadeOnto(WidgetPalette.onSurface(context, dark), widgetBackground)
                    }
                    views.setImageViewResource(borderId, borderDrawable(piece.kind))
                    views.setInt(borderId, "setColorFilter", border)
                    views.setImageViewResource(fillId, fillDrawable(piece.kind))
                    views.setInt(fillId, "setColorFilter", fill)
                    views.setTextColor(txId, text)
                    // 课程名只显示在整块/块起始上，中段与收尾保持纯色块。
                    views.setTextViewText(
                        txId,
                        if (piece.kind == PieceKind.FULL || piece.kind == PieceKind.TOP) courseText(piece) else ""
                    )
                }
            }
            return views
        }

        /** 课程格文字：课程名（连堂 ≥2 节追加 @教室，与 App 一致）+ 单双周标注（与 App 的 pill 同规则）。 */
        private fun courseText(piece: CellPiece): String {
            val course = piece.course
            val text = StringBuilder(course.name.ifBlank { "未命名" })
            val span = course.endPeriod - course.startPeriod + 1
            if (span >= 2 && course.room.isNotBlank()) text.append("\n@").append(course.room)
            val tag = weekParityLabel(course.rawWeekText, viewedWeek, totalWeeks)
            if (tag != null) text.append("\n").append(tag)
            return text.toString()
        }

        private fun borderDrawable(kind: PieceKind): Int = when (kind) {
            PieceKind.FULL -> R.drawable.widget_cell_b_full
            PieceKind.TOP -> R.drawable.widget_cell_b_top
            PieceKind.MID -> R.drawable.widget_cell_b_mid
            PieceKind.BOTTOM -> R.drawable.widget_cell_b_bot
        }

        private fun fillDrawable(kind: PieceKind): Int = when (kind) {
            PieceKind.FULL -> R.drawable.widget_cell_f_full
            PieceKind.TOP -> R.drawable.widget_cell_f_top
            PieceKind.MID -> R.drawable.widget_cell_f_mid
            PieceKind.BOTTOM -> R.drawable.widget_cell_f_bot
        }

        override fun getViewTypeCount(): Int = rowLayouts.size + 1

        override fun getItemId(position: Int): Long = position.toLong()

        override fun hasStableIds(): Boolean = true

        override fun getLoadingView(): RemoteViews? = null

        override fun onDestroy() = Unit

        private val labelNumIds = intArrayOf(
            R.id.widget_rl_0, R.id.widget_rl_1, R.id.widget_rl_2, R.id.widget_rl_3,
            R.id.widget_rl_4, R.id.widget_rl_5, R.id.widget_rl_6, R.id.widget_rl_7,
            R.id.widget_rl_8, R.id.widget_rl_9, R.id.widget_rl_10,
            R.id.widget_rl_11, R.id.widget_rl_12, R.id.widget_rl_13
        )
        private val labelTimeIds = intArrayOf(
            R.id.widget_rlt_0, R.id.widget_rlt_1, R.id.widget_rlt_2, R.id.widget_rlt_3,
            R.id.widget_rlt_4, R.id.widget_rlt_5, R.id.widget_rlt_6, R.id.widget_rlt_7,
            R.id.widget_rlt_8, R.id.widget_rlt_9, R.id.widget_rlt_10,
            R.id.widget_rlt_11, R.id.widget_rlt_12, R.id.widget_rlt_13
        )
        private val borderIds = intArrayOf(
            R.id.widget_cell_border_1, R.id.widget_cell_border_2, R.id.widget_cell_border_3,
            R.id.widget_cell_border_4, R.id.widget_cell_border_5, R.id.widget_cell_border_6,
            R.id.widget_cell_border_7
        )
        private val fillIds = intArrayOf(
            R.id.widget_cell_bg_1, R.id.widget_cell_bg_2, R.id.widget_cell_bg_3,
            R.id.widget_cell_bg_4, R.id.widget_cell_bg_5, R.id.widget_cell_bg_6,
            R.id.widget_cell_bg_7
        )
        private val textIds = intArrayOf(
            R.id.widget_cell_tx_1, R.id.widget_cell_tx_2, R.id.widget_cell_tx_3,
            R.id.widget_cell_tx_4, R.id.widget_cell_tx_5, R.id.widget_cell_tx_6,
            R.id.widget_cell_tx_7
        )
        private val rowLayouts = intArrayOf(
            R.layout.widget_week_row_s1, R.layout.widget_week_row_s2, R.layout.widget_week_row_s3,
            R.layout.widget_week_row_s4, R.layout.widget_week_row_s5, R.layout.widget_week_row_s6,
            R.layout.widget_week_row_s7, R.layout.widget_week_row_s8, R.layout.widget_week_row_s9,
            R.layout.widget_week_row_s10, R.layout.widget_week_row_s11,
            R.layout.widget_week_row_s12, R.layout.widget_week_row_s13,
            R.layout.widget_week_row_s14
        )
    }
}

/** 同 App 内 WeeklyTimetableGrid 的变体去重：同一时段只显示最合适的版本。 */
private fun selectVisibleVariants(courses: List<CourseSlotEntity>, currentWeek: Int): List<CourseSlotEntity> =
    courses.filter { course ->
        courses.none { other ->
            other.sectionId != course.sectionId &&
                periodsOverlap(other, course) &&
                preferredVariant(other, course) &&
                latestWeek(other.rawWeekText) >= currentWeek
        }
    }

private fun preferredVariant(other: CourseSlotEntity, course: CourseSlotEntity): Boolean {
    val otherStart = earliestWeek(other.rawWeekText)
    val courseStart = earliestWeek(course.rawWeekText)
    return otherStart < courseStart ||
        (otherStart == courseStart && latestWeek(other.rawWeekText) > latestWeek(course.rawWeekText))
}

private fun periodsOverlap(first: CourseSlotEntity, second: CourseSlotEntity): Boolean =
    first.startPeriod <= second.endPeriod && second.startPeriod <= first.endPeriod

private fun earliestWeek(raw: String): Int =
    Regex("\\d+").find(raw)?.value?.toIntOrNull() ?: Int.MAX_VALUE

private fun latestWeek(raw: String): Int =
    Regex("\\d+").findAll(raw).mapNotNull { it.value.toIntOrNull() }.maxOrNull() ?: 0

private fun currentWeekOf(timetable: TimetableEntity): Int {
    val startDate = timetable.startDate ?: return 1
    return runCatching {
        ChronoUnit.WEEKS.between(LocalDate.parse(startDate), LocalDate.now()).toInt() + 1
    }.getOrNull()?.coerceIn(1, 30) ?: 1
}
