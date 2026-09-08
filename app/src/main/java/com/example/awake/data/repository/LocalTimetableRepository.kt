package com.example.awake.data.repository

import androidx.room.withTransaction
import com.example.awake.data.local.AppDatabase
import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.CourseSectionEntity
import com.example.awake.data.local.CourseSlotEntity
import com.example.awake.data.local.CourseWeekEntity
import com.example.awake.data.local.ProfileEntity
import com.example.awake.data.local.PeriodConfigDefaults
import com.example.awake.data.local.PeriodConfigScopes
import com.example.awake.data.local.PeriodConfigTarget
import com.example.awake.data.local.PeriodConfigEntity
import com.example.awake.data.local.TimetableEntity
import com.example.awake.domain.model.CourseIdentity
import com.example.awake.domain.model.SchoolCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

private val TIME_PATTERN = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")

class LocalTimetableRepository(private val db: AppDatabase) {
    val activeProfile = db.profileDao().observeActive().map { it?.toDomain() }

    suspend fun ensureProfile(): ProfileEntity = db.profileDao().getActive() ?: ProfileEntity(
        schoolCode = SchoolCode.SCUT.code, displayName = "未登录"
    ).let { it.copy(id = db.profileDao().insert(it)) }

    suspend fun saveLoggedInProfile(
        displayName: String?,
        studentId: String?,
        schoolCode: String = SchoolCode.SCUT.code
    ): ProfileEntity {
        val existing = db.profileDao().getActive()
        val profile = (existing ?: ProfileEntity()).copy(
            schoolCode = schoolCode,
            displayName = displayName ?: existing?.displayName,
            maskedStudentId = studentId?.maskStudentId() ?: existing?.maskedStudentId,
            lastLoginAt = System.currentTimeMillis()
        )
        val id = if (profile.id == 0L) db.profileDao().insert(profile) else {
            db.profileDao().update(profile)
            profile.id
        }
        return profile.copy(id = id)
    }

    fun observeTimetables(profileId: Long): Flow<List<TimetableEntity>> = db.timetableDao().observeForProfile(profileId)
    suspend fun getTimetables(profileId: Long): List<TimetableEntity> = db.timetableDao().getAllForProfile(profileId)
    fun observeTimetable(id: Long): Flow<TimetableEntity?> = db.timetableDao().observeById(id)
    fun observeCourses(id: Long, week: Int): Flow<List<CourseSlotEntity>> = db.courseDao().observeSlotsForWeek(id, week)
    fun observeCoursesThroughEnd(id: Long, week: Int): Flow<List<CourseSlotEntity>> = db.courseDao().observeSlotsThroughEnd(id, week)
    fun observeCourse(id: Long): Flow<CourseEntity?> = db.courseDao().observeCourse(id)
    suspend fun getAllSlots(timetableId: Long): List<CourseSlotEntity> = db.courseDao().getAllSlots(timetableId)
    fun observeSections(courseId: Long): Flow<List<CourseSectionEntity>> = db.courseDao().observeSections(courseId)
    fun observeSlot(sectionId: Long): Flow<CourseSlotEntity?> = db.courseDao().observeSlot(sectionId)
    suspend fun getSlotOrNull(sectionId: Long): CourseSlotEntity? = db.courseDao().getSlot(sectionId)
    suspend fun getSectionOrNull(sectionId: Long): CourseSectionEntity? = db.courseDao().getSectionById(sectionId)
    suspend fun getCourseOrNull(id: Long): CourseEntity? = db.courseDao().getCourse(id)
    /** 时段设置跟随课表：优先课表独立配置，其次学校配置，最后回退全局默认（timetableId = 0）。 */
    suspend fun getPeriodConfigsFor(timetableId: Long): List<PeriodConfigEntity> {
        val custom = db.periodConfigDao().getFor(timetableId)
        return custom.ifEmpty { db.periodConfigDao().getDefaults() }
    }

    /** 课表没有独立配置时，使用其所属学校的节次配置，保证同一学校的时间统一生效。 */
    suspend fun getPeriodConfigsFor(timetable: TimetableEntity): List<PeriodConfigEntity> {
        db.periodConfigDao().getFor(timetable.id).takeIf { it.isNotEmpty() }?.let { return it }
        val target = PeriodConfigScopes.targetFor(
            timetable.schoolCode,
            timetable.campusCode,
            timetable.periodTargetCode
        )
        db.periodConfigDao().getFor(target.scope)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        return target.defaultConfigs
    }

    fun observePeriodConfigsFor(timetableId: Long): Flow<List<PeriodConfigEntity>> = kotlinx.coroutines.flow.combine(
        db.periodConfigDao().observeFor(timetableId),
        db.periodConfigDao().observeDefaults()
    ) { custom, defaults -> if (custom.isEmpty()) defaults else custom }

    fun observePeriodConfigsFor(timetable: TimetableEntity): Flow<List<PeriodConfigEntity>> =
        kotlinx.coroutines.flow.combine(
            db.periodConfigDao().observeFor(timetable.id),
            db.periodConfigDao().observeFor(
                PeriodConfigScopes.targetFor(
                    timetable.schoolCode,
                    timetable.campusCode,
                    timetable.periodTargetCode
                ).scope
            ),
            db.periodConfigDao().observeDefaults()
        ) { custom, school, defaults ->
            val target = PeriodConfigScopes.targetFor(
                timetable.schoolCode,
                timetable.campusCode,
                timetable.periodTargetCode
            )
            when {
                custom.isNotEmpty() -> custom
                school.isNotEmpty() -> school
                else -> target.defaultConfigs.ifEmpty { defaults }
            }
        }

    /** 学校级配置读写：所有属于该学校且没有课表独立配置的课表都会使用这份数据。 */
    suspend fun getPeriodConfigsForTarget(target: PeriodConfigTarget): List<PeriodConfigEntity> {
        val targetConfigs = db.periodConfigDao().getFor(target.scope)
        return targetConfigs.ifEmpty { target.defaultConfigs }
    }

    suspend fun getPeriodConfigs() = db.periodConfigDao().getDefaults()
    fun observePeriodConfigs() = db.periodConfigDao().observeDefaults()
    suspend fun deletePeriodConfigsFor(timetableId: Long) = db.periodConfigDao().deleteFor(timetableId)

    suspend fun savePeriodConfigs(timetableId: Long, configs: List<com.example.awake.data.local.PeriodConfigEntity>) {
        require(configs.map { it.period }.distinct().size == configs.size) { "节次编号不能重复" }
        require(configs.all { it.period in 1..30 && TIME_PATTERN.matches(it.startTime) && TIME_PATTERN.matches(it.endTime) }) {
            "节次时间格式应为 HH:mm"
        }
        db.withTransaction {
            db.periodConfigDao().deleteFor(timetableId)
            db.periodConfigDao().insertAll(configs.map { it.copy(timetableId = timetableId) })
        }
    }
    suspend fun getTimetable(id: Long): TimetableEntity = db.timetableDao().getById(id) ?: error("课表不存在")
    suspend fun getTimetableOrNull(id: Long): TimetableEntity? = db.timetableDao().getById(id)
    suspend fun getFirstTimetable(): TimetableEntity? = db.profileDao().getActive()?.let { db.timetableDao().getFirstForProfile(it.id) }
    suspend fun findTimetable(profileId: Long, xnm: Int, xqm: String): TimetableEntity? =
        db.timetableDao().find(profileId, xnm, xqm)

    suspend fun createTimetable(
        profileId: Long,
        xnm: Int,
        xqm: String,
        label: String,
        schoolCode: String = SchoolCode.SCUT.code,
        campusCode: String = ""
    ): TimetableEntity {
        val value = TimetableEntity(profileId = profileId, schoolCode = schoolCode, campusCode = campusCode, xnm = xnm, xqm = xqm, label = label)
        return value.copy(id = db.timetableDao().insert(value))
    }

    suspend fun updateTimetable(timetable: TimetableEntity) = db.timetableDao().update(timetable)

    /**
     * 同步前校验远端课表属主。已有属主必须学校/学号完全匹配；
     * 旧课表尚无属主时，只有当前档案能证明是同一账号才允许绑定。
     */
    private data class TimetableContentHashes(
        val local: String,
        val remote: String
    )

    private data class TimetableContentRow(
        val source: String,
        val remoteKey: String,
        val name: String,
        val teacher: String,
        val room: String,
        val dayOfWeek: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        val weeks: Set<Int>,
        val locked: Boolean
    )

    private suspend fun prepareRemoteSync(
        timetable: TimetableEntity,
        schoolCode: String,
        courses: List<CourseEntity>,
        sections: List<CourseSectionEntity>,
        weeks: List<CourseWeekEntity>,
        remoteStudentId: String?,
        remoteStudentName: String?,
        ownerConfirmed: Boolean,
        contentConfirmed: Boolean
    ): Pair<TimetableEntity, TimetableContentHashes> {
        val maskedRemoteId = remoteStudentId?.trim()?.takeIf { it.isNotEmpty() }?.maskStudentId()
            ?: error("教务响应缺少学号，无法确认课表属主")

        val ownerBound = timetable.ownerSchoolCode.isNotBlank() &&
            timetable.ownerStudentIdMasked.isNotBlank()
        if (ownerBound) {
            require(
                timetable.ownerSchoolCode == schoolCode &&
                    timetable.ownerStudentIdMasked == maskedRemoteId
            ) { "当前登录账号不是这份课表的主人，请新建课表后同步" }
        }

        val hasExistingCourses = db.courseDao().countCourses(timetable.id) > 0
        val isLegacyTimetable = hasExistingCourses || timetable.lastSyncedAt != null
        val ownerRequired = !ownerBound && isLegacyTimetable

        val hashes = TimetableContentHashes(
            local = localContentHash(timetable.id),
            remote = remoteContentHash(courses, sections, weeks)
        )
        val hasStoredFingerprints = timetable.syncConfirmedLocalHash.isNotBlank() &&
            timetable.syncConfirmedRemoteHash.isNotBlank()
        val fingerprintsUnchanged = hasStoredFingerprints &&
            timetable.syncConfirmedLocalHash == hashes.local &&
            timetable.syncConfirmedRemoteHash == hashes.remote
        val contentRequired = if (!isLegacyTimetable) {
            false
        } else if (hasStoredFingerprints) {
            !fingerprintsUnchanged
        } else {
            hashes.local != hashes.remote
        }

        if ((ownerRequired && !ownerConfirmed) || (contentRequired && !contentConfirmed)) {
            throw TimetableSyncConfirmationRequired(
                TimetableSyncConfirmationRequest(
                    timetableId = timetable.id,
                    ownerRequired = ownerRequired && !ownerConfirmed,
                    contentRequired = contentRequired && !contentConfirmed,
                    ownerStudentIdMasked = maskedRemoteId,
                    ownerStudentName = remoteStudentName
                )
            )
        }

        val ownerReady = if (ownerBound) {
            timetable
        } else {
            timetable.copy(
                ownerSchoolCode = schoolCode,
                ownerStudentIdMasked = maskedRemoteId
            )
        }
        if (!ownerBound) {
            db.timetableDao().update(ownerReady)
        }
        return ownerReady to hashes
    }

    suspend fun findOrCreateTimetable(profileId: Long, xnm: Int, xqm: String, label: String): TimetableEntity {
        return findTimetable(profileId, xnm, xqm) ?: createTimetable(profileId, xnm, xqm, label)
    }

    suspend fun deleteTimetable(id: Long) = db.withTransaction {
        // 课表删除时同步清理它的独立节次配置。
        db.periodConfigDao().deleteFor(id)
        db.timetableDao().deleteById(id)
    }

    /**
     * 手动新增一个时段：身份相同（名称+教师）的手动课程会复用已有主记录，不同时段成为新子记录。
     * 时段键相同、或“星期+节次”完全相同的既有时段会被复用更新（兼容迁移后的旧时段键），
     * 避免手动重复添加产生重复行。返回主课程 ID。
     */
    suspend fun insertManualCourse(course: CourseEntity, section: CourseSectionEntity, weeks: Set<Int>): Long = db.withTransaction {
        val masterId = db.courseDao().findCourse(course.timetableId, course.source, course.remoteKey)?.id
            ?: db.courseDao().insertCourse(course.copy(id = 0))
        val existingSection = db.courseDao().findSection(masterId, section.source, section.remoteKey)
            ?: db.courseDao().findIdenticalSlot(masterId, section.source, section.dayOfWeek, section.startPeriod, section.endPeriod)
        val sectionId = if (existingSection != null) {
            db.courseDao().updateSection(section.copy(id = existingSection.id, courseId = masterId))
            existingSection.id
        } else {
            db.courseDao().insertSection(section.copy(id = 0, courseId = masterId))
        }
        db.courseDao().deleteWeeks(sectionId)
        if (weeks.isNotEmpty()) {
            db.courseDao().insertWeeks(weeks.map { CourseWeekEntity(sectionId, it) })
        }
        masterId
    }

    /** 编辑课程主记录（名称/教师/学分等）；周次与时间在时段层维护。 */
    suspend fun updateCourse(course: CourseEntity) = db.withTransaction {
        db.courseDao().updateCourse(course)
    }

    /** 立即把颜色写库（点选即存，不等待“保存课程信息”）。 */
    suspend fun updateCourseColor(courseId: Long, color: Int) {
        db.courseDao().updateCourseColor(courseId, color or 0xFF000000.toInt())
    }

    /** 编辑单个时段；周次文本变化时同步重建该时段的 week 关系。 */
    suspend fun updateSection(section: CourseSectionEntity, weeks: Set<Int>? = null) = db.withTransaction {
        db.courseDao().updateSection(section)
        if (weeks != null) {
            db.courseDao().deleteWeeks(section.id)
            if (weeks.isNotEmpty()) {
                db.courseDao().insertWeeks(weeks.map { CourseWeekEntity(section.id, it) })
            }
        }
    }

    suspend fun deleteSection(sectionId: Long) = db.withTransaction {
        val section = db.courseDao().getSectionById(sectionId) ?: return@withTransaction
        db.courseDao().deleteWeeks(sectionId)
        db.courseDao().deleteSectionById(sectionId)
        if (db.courseDao().countSections(section.courseId) == 0) {
            db.courseDao().deleteCourseById(section.courseId)
        }
    }

    suspend fun deleteCourse(id: Long) = db.courseDao().deleteCourseById(id)

    /** 清理旧版本可能混入正式课表的演示样例，不触碰用户真正的手动课程。 */
    suspend fun cleanupLegacyDemoCourses() = db.withTransaction {
        val profile = db.profileDao().getActive()
        if (profile != null) {
            db.timetableDao().getAllForProfile(profile.id)
                .filterNot { it.label.endsWith("（演示）") }
                .forEach { timetable ->
                    db.courseDao().deleteDemoWeeks(timetable.id)
                    db.courseDao().deleteDemoSections(timetable.id)
                    db.courseDao().deleteDemoCourses(timetable.id)
                }
        }
    }

    /**
     * 原子替换课表内的教务同步课程。
     * courses 为去重主记录；sections.courseId 是主课程下标、weeks.sectionId 是时段下标，
     * 事务内统一重映射。任一步冲突抛异常时整体回滚，旧课表保留。
     */
    suspend fun replaceRemoteCourses(
        timetable: TimetableEntity,
        courses: List<CourseEntity>,
        sections: List<CourseSectionEntity>,
        weeks: List<CourseWeekEntity>,
        remoteStudentId: String?,
        remoteStudentName: String?,
        ownerConfirmed: Boolean,
        contentConfirmed: Boolean
    ) = db.withTransaction {
        // 所有学校的教务同步都统一绑定/校验属主，避免新增学校时漏掉账号检查。
        val (ownerBound, hashes) = prepareRemoteSync(
            timetable = timetable,
            schoolCode = timetable.schoolCode,
            courses = courses,
            sections = sections,
            weeks = weeks,
            remoteStudentId = remoteStudentId,
            remoteStudentName = remoteStudentName,
            ownerConfirmed = ownerConfirmed,
            contentConfirmed = contentConfirmed
        )
        // 用户在详情页改过颜色的课程（≠ 默认算法色），在整删重建后按 (source, remoteKey) 原样带回，
        // 避免自动刷新看起来“没有落库”。
        val customizedColors = db.courseDao().getRemoteMasters(timetable.id)
            .filter { it.color != com.example.awake.domain.model.defaultCourseColor(it.remoteKey) }
            .associate { (it.source to it.remoteKey) to it.color }
        db.courseDao().deleteDemoWeeks(timetable.id)
        db.courseDao().deleteDemoSections(timetable.id)
        db.courseDao().deleteDemoCourses(timetable.id)
        db.courseDao().deleteRemoteWeeks(timetable.id)
        db.courseDao().deleteRemoteSections(timetable.id)
        db.courseDao().deleteRemoteForTimetable(timetable.id)
        val adjusted = courses.map { master ->
            customizedColors[master.source to master.remoteKey]?.let { master.copy(color = it) } ?: master
        }
        val masterIds = db.courseDao().insertCoursesStrict(adjusted)
        val sectionIds = sections.map { section ->
            val masterId = masterIds.getOrNull(section.courseId.toInt())
                ?: error("时段引用了不存在的主课程下标 ${section.courseId}")
            db.courseDao().insertSection(section.copy(id = 0, courseId = masterId))
        }
        val remapped = weeks.mapNotNull { week ->
            sectionIds.getOrNull(week.sectionId.toInt())?.let { CourseWeekEntity(it, week.weekNumber) }
        }
        if (remapped.isNotEmpty()) db.courseDao().insertWeeks(remapped)
        // 替换完成后再取本地指纹：下次刷新时它代表“用户确认过的最新课表”。
        val confirmedLocalHash = localContentHash(timetable.id)
        db.timetableDao().update(
            ownerBound.copy(
                lastSyncedAt = System.currentTimeMillis(),
                syncConfirmedLocalHash = confirmedLocalHash,
                syncConfirmedRemoteHash = hashes.remote
            )
        )
    }

    private suspend fun localContentHash(timetableId: Long): String {
        val masters = db.courseDao().getAllMasters(timetableId).associateBy { it.id }
        return hashContentRows(
            db.courseDao().getAllSectionsRaw(timetableId).mapNotNull { section ->
                val master = masters[section.courseId] ?: return@mapNotNull null
                TimetableContentRow(
                    source = section.source,
                    remoteKey = section.remoteKey,
                    name = master.name,
                    teacher = section.teacher.ifBlank { master.teacher },
                    room = section.room,
                    dayOfWeek = section.dayOfWeek,
                    startPeriod = section.startPeriod,
                    endPeriod = section.endPeriod,
                    weeks = db.courseDao().getWeeks(section.id).mapTo(mutableSetOf()) { it.weekNumber },
                    locked = section.locked
                )
            }
        )
    }

    private fun remoteContentHash(
        courses: List<CourseEntity>,
        sections: List<CourseSectionEntity>,
        weeks: List<CourseWeekEntity>
    ): String {
        val masterByIndex = courses.withIndex().associate { (index, course) -> index to course }
        val weeksBySectionIndex: Map<Long, List<Int>> =
            weeks.groupBy({ it.sectionId }, { it.weekNumber })
        return hashContentRows(
            sections.mapIndexedNotNull { index, section ->
                val master = masterByIndex[section.courseId.toInt()] ?: return@mapIndexedNotNull null
                TimetableContentRow(
                    source = section.source,
                    remoteKey = section.remoteKey,
                    name = master.name,
                    teacher = section.teacher.ifBlank { master.teacher },
                    room = section.room,
                    dayOfWeek = section.dayOfWeek,
                    startPeriod = section.startPeriod,
                    endPeriod = section.endPeriod,
                    weeks = weeksBySectionIndex[index.toLong()].orEmpty().toSet(),
                    locked = section.locked
                )
            }
        )
    }

    private fun hashContentRows(rows: List<TimetableContentRow>): String {
        if (rows.isEmpty()) return "empty"
        val canonical = rows
            .sortedWith(
                compareBy(
                    { it.source }, { it.name }, { it.remoteKey }, { it.dayOfWeek },
                    { it.startPeriod }, { it.endPeriod }, { it.room }, { it.teacher }
                )
            )
            .joinToString("\n") { row ->
                listOf(
                    row.source, row.remoteKey, row.name, row.teacher, row.room,
                    row.dayOfWeek, row.startPeriod, row.endPeriod,
                    row.weeks.sorted().joinToString(","), row.locked
                ).joinToString("|")
            }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ---- JSON 分享导出 / 导入 ----

    /** 导出当前课表完整 JSON：学期元数据 + 全部主课程与时段（含周次、颜色）。 */
    suspend fun exportTimetableJson(timetableId: Long): String? {
        val timetable = db.timetableDao().getById(timetableId) ?: return null
        val masters = db.courseDao().getAllMasters(timetableId)
        val meta = com.example.awake.data.export.TimetableJson.JsonTimetableMeta(
            label = timetable.label,
            xnm = timetable.xnm,
            xqm = timetable.xqm,
            startDate = timetable.startDate,
            totalWeeks = timetable.totalWeeks
        )
        val courses = masters.map { master ->
            val sections = db.courseDao().getSections(master.id).map { section ->
                com.example.awake.data.export.TimetableJson.JsonSection(
                    dayOfWeek = section.dayOfWeek,
                    startPeriod = section.startPeriod,
                    endPeriod = section.endPeriod,
                    room = section.room,
                    teacher = section.teacher,
                    rawWeekText = section.rawWeekText
                )
            }
            com.example.awake.data.export.TimetableJson.JsonCourse(
                source = master.source,
                name = master.name,
                teacher = master.teacher,
                color = master.color,
                credits = master.credits,
                totalHours = master.totalHours,
                courseType = master.courseType,
                assessment = master.assessment,
                className = master.className,
                sections = sections
            )
        }
        return com.example.awake.data.export.TimetableJson.toString(meta, courses)
    }

    /**
     * JSON 导入 = 整表替换：目标课表的全部课程（含手动课）被分享文本中的内容重建。
     * 仅用于“从 JSON 新建课表”的路径，不参与教务同步。
     */
    suspend fun replaceAllCourses(
        timetable: TimetableEntity,
        courses: List<CourseEntity>,
        sections: List<CourseSectionEntity>,
        weeks: List<CourseWeekEntity>
    ) = db.withTransaction {
        db.courseDao().deleteWeeksFor(timetable.id)
        db.courseDao().deleteSectionsFor(timetable.id)
        db.courseDao().deleteCoursesFor(timetable.id)
        val masterIds = db.courseDao().insertCoursesStrict(courses)
        val sectionIds = sections.map { section ->
            val masterId = masterIds.getOrNull(section.courseId.toInt())
                ?: error("时段引用了不存在的主课程下标 ${section.courseId}")
            db.courseDao().insertSection(section.copy(id = 0, courseId = masterId))
        }
        val remapped = weeks.mapNotNull { week ->
            sectionIds.getOrNull(week.sectionId.toInt())?.let { CourseWeekEntity(it, week.weekNumber) }
        }
        if (remapped.isNotEmpty()) db.courseDao().insertWeeks(remapped)
    }

    /**
     * 从分享文本创建/覆盖课表：
     * - overrideTargetId != null：整表替换目标课表（元数据一并更新），失败恢复原元数据；
     * - 否则新建课表，直接使用来源中的名称。
     */
    suspend fun importTimetableFromJson(
        profileId: Long,
        data: com.example.awake.data.export.TimetableJson.JsonTimetableData,
        overrideTargetId: Long? = null
    ): TimetableEntity {
        val timetable: TimetableEntity
        var originalMeta: TimetableEntity? = null
        if (overrideTargetId != null) {
            val target = getTimetableOrNull(overrideTargetId) ?: error("要覆盖的课表不存在")
            originalMeta = target
            timetable = target.copy(
                label = data.meta.label,
                xnm = data.meta.xnm,
                xqm = data.meta.xqm,
                startDate = data.meta.startDate,
                totalWeeks = data.meta.totalWeeks
            ).also { updateTimetable(it) }
        } else {
            timetable = createTimetable(profileId, data.meta.xnm, data.meta.xqm, data.meta.label).copy(
                startDate = data.meta.startDate,
                totalWeeks = data.meta.totalWeeks
            ).also { updateTimetable(it) }
        }
        try {
            val (courses, sections, weeks) = buildJsonEntities(timetable, data)
            replaceAllCourses(timetable, courses, sections, weeks)
        } catch (error: Throwable) {
            originalMeta?.let { updateTimetable(it) }
            throw error
        }
        return timetable
    }

    private fun buildJsonEntities(
        timetable: TimetableEntity,
        data: com.example.awake.data.export.TimetableJson.JsonTimetableData
    ): Triple<List<CourseEntity>, List<CourseSectionEntity>, List<CourseWeekEntity>> {
        val courses = mutableListOf<CourseEntity>()
        val sections = mutableListOf<CourseSectionEntity>()
        val weeks = mutableListOf<CourseWeekEntity>()
        val usedColors = mutableListOf<Int>()
        data.courses.forEach { jsonCourse ->
            val index = courses.size
            val color = jsonCourse.color
                ?.takeIf { it != 0 }
                ?: com.example.awake.domain.model.pickNewCourseColor(usedColors)
            usedColors += color
            courses += CourseEntity(
                timetableId = timetable.id,
                source = jsonCourse.source,
                remoteKey = CourseIdentity.masterKey(jsonCourse.source, jsonCourse.name, jsonCourse.className, jsonCourse.teacher),
                name = jsonCourse.name,
                teacher = jsonCourse.teacher,
                credits = jsonCourse.credits,
                totalHours = jsonCourse.totalHours,
                courseType = jsonCourse.courseType,
                assessment = jsonCourse.assessment,
                className = jsonCourse.className,
                color = color
            )
            jsonCourse.sections.forEach { jsonSection ->
                val sectionIndex = sections.size
                sections += CourseSectionEntity(
                    courseId = index.toLong(),
                    source = jsonCourse.source,
                    remoteKey = CourseIdentity.sectionKey(
                        jsonCourse.source, jsonCourse.name, jsonSection.teacher, jsonSection.room,
                        jsonSection.dayOfWeek, "${jsonSection.startPeriod}-${jsonSection.endPeriod}",
                        jsonSection.rawWeekText, jsonCourse.className
                    ),
                    dayOfWeek = jsonSection.dayOfWeek,
                    startPeriod = jsonSection.startPeriod,
                    endPeriod = jsonSection.endPeriod,
                    room = jsonSection.room,
                    teacher = jsonSection.teacher,
                    rawWeekText = jsonSection.rawWeekText
                )
                val parsed = com.example.awake.domain.parser.WeekExpressionParser.parse(jsonSection.rawWeekText, maxWeek = 60)
                parsed.weeks.forEach { weeks += CourseWeekEntity(sectionIndex.toLong(), it) }
            }
        }
        return Triple(courses, sections, weeks)
    }

    suspend fun deleteAll() = db.withTransaction {
        db.courseDao().deleteAllWeeks()
        db.courseDao().deleteAllSections()
        db.courseDao().deleteAllCourses()
        db.timetableDao().deleteAll()
        db.profileDao().deleteAll()
    }

    private fun String.maskStudentId(): String = if (length <= 4) "****" else take(2) + "****" + takeLast(2)
    private fun ProfileEntity.toDomain() = com.example.awake.domain.model.Profile(id, SchoolCode.SCUT, maskedStudentId, displayName, lastLoginAt)
}
