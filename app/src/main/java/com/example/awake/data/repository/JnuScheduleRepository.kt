package com.example.awake.data.repository

import com.example.awake.data.mapper.MappedSchedule
import com.example.awake.data.mapper.ScutScheduleMapper
import com.example.awake.data.remote.JnuJwClient
import com.example.awake.data.remote.RemoteAcademicYear
import com.example.awake.data.remote.ScutAccessMode
import com.example.awake.data.remote.ScutHttpException
import com.example.awake.data.remote.SessionAvailability
import com.example.awake.data.remote.SessionAvailabilityState
import com.example.awake.data.remote.ScutSchedulePayload
import com.example.awake.domain.model.ParseWarning
import com.example.awake.domain.model.SchoolCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** 暨南大学课表仓储：预览、导入、学期列表，与 SCUT 仓储保持同一交互形状。 */
class JnuScheduleRepository(
    private val local: LocalTimetableRepository,
    private val client: JnuJwClient,
    private val mapper: ScutScheduleMapper = ScutScheduleMapper(),
    /** 检查前先从系统 WebView CookieManager 恢复进程内会话；应用更新/进程重启后 Cookie 仍在。 */
    private val restoreSession: () -> Unit = {}
) : SchoolScheduleProvider {
    private val importLocks = ConcurrentHashMap<Long, Mutex>()

    /** 登录后读取真实学期列表；不创建或修改本地课表。 */
    override suspend fun academicTerms(): List<RemoteAcademicYear> = client.fetchAcademicTerms()

    /** 检查进程内暨大会话；更新或进程重启后先从系统 WebView 恢复。 */
    override suspend fun probeSessions(): List<SessionAvailability> {
        runCatching { restoreSession() }
        return listOf(
        SessionAvailability(
            accessMode = ScutAccessMode.DIRECT,
            state = if (client.hasSession()) SessionAvailabilityState.AVAILABLE
            else SessionAvailabilityState.NOT_CONFIGURED,
            detail = if (client.hasSession()) null else "未配置登录会话"
        )
        )
    }

    /** 登录后先预览真实课程，不创建本地课表、不写数据库。 */
    override suspend fun preview(xnm: Int, xqm: String): ScutSchedulePayload {
        require(xnm > 0) { "学年起始年无效" }
        require(xqm.isNotBlank()) { "学期码不能为空" }
        return client.fetchSchedule(xnm, xqm)
    }

    override suspend fun import(
        timetableId: Long,
        selectedRemoteKeys: Set<String>?,
        ownerConfirmed: Boolean,
        contentConfirmed: Boolean
    ): List<ParseWarning> {
        val lock = importLocks.getOrPut(timetableId) { Mutex() }
        return lock.withLock {
            val timetable = local.getTimetable(timetableId)
            if (timetable.schoolCode != SchoolCode.JNU.code) {
                throw ScutHttpException(ScutHttpException.Kind.INVALID_RESPONSE, "课表不属于暨南大学")
            }
            val payload = client.fetchSchedule(timetable.xnm, timetable.xqm).let { full ->
                selectedRemoteKeys?.let { selected ->
                    full.copy(courses = full.courses.filter { it.remoteKey() in selected })
                } ?: full
            }
            val mapped: MappedSchedule = mapper.map(payload, timetable.id, timetable.totalWeeks)
            local.replaceRemoteCourses(
                timetable,
                mapped.courses,
                mapped.sections,
                mapped.weeks,
                remoteStudentId = mapped.studentId,
                remoteStudentName = mapped.studentName,
                ownerConfirmed = ownerConfirmed,
                contentConfirmed = contentConfirmed
            )
            if (mapped.studentId != null || mapped.studentName != null) {
                local.saveLoggedInProfile(mapped.studentName, mapped.studentId, SchoolCode.JNU.code)
            }
            mapped.warnings
        }
    }
}






