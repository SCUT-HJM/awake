package com.example.awake.data.repository

import com.example.awake.data.remote.RemoteAcademicYear
import com.example.awake.data.remote.ScutSchedulePayload
import com.example.awake.domain.model.ParseWarning
import com.example.awake.domain.model.SchoolCode

/**
 * 按学校路由的课表仓储门面：
 * - academicTerms/preview/probeSessions 由调用方显式指定学校；
 * - import 按目标课表自身的 schoolCode 路由，保证刷新/覆盖不会串校。
 *
 * 这里只维护学校代码到 Provider 的注册关系，不包含具体学校逻辑。
 */
class SchoolScheduleRouter(
    private val local: LocalTimetableRepository,
    private val providers: Map<String, SchoolScheduleProvider>
) {
    suspend fun academicTerms(school: SchoolCode): List<RemoteAcademicYear> =
        provider(school.code).academicTerms()

    suspend fun probeSessions(school: SchoolCode) = provider(school.code).probeSessions()

    suspend fun preview(school: SchoolCode, xnm: Int, xqm: String): ScutSchedulePayload =
        provider(school.code).preview(xnm, xqm)

    suspend fun import(
        timetableId: Long,
        selectedRemoteKeys: Set<String>? = null
    ): List<ParseWarning> {
        val school = local.getTimetable(timetableId).schoolCode
        return provider(school).import(timetableId, selectedRemoteKeys)
    }

    private fun provider(code: String): SchoolScheduleProvider =
        providers[code] ?: error("未注册学校：$code")
}
