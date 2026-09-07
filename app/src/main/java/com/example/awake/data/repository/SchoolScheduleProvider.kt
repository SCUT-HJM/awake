package com.example.awake.data.repository

import com.example.awake.data.remote.RemoteAcademicYear
import com.example.awake.data.remote.ScutSchedulePayload
import com.example.awake.domain.model.ParseWarning

/**
 * 学校课表能力的统一入口。
 *
 * 新增学校时实现该接口，并注册到 [SchoolScheduleRouter] 的 provider map；
 * 通用 UI 只依赖这个契约，不感知具体教务系统。
 */
interface SchoolScheduleProvider {
    suspend fun academicTerms(): List<RemoteAcademicYear>
    suspend fun probeSessions(): List<com.example.awake.data.remote.SessionAvailability>
    suspend fun preview(xnm: Int, xqm: String): ScutSchedulePayload
    suspend fun import(timetableId: Long, selectedRemoteKeys: Set<String>? = null): List<ParseWarning>
}
