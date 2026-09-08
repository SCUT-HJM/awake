package com.example.awake.domain.usecase

import com.example.awake.data.repository.SchoolScheduleRouter
import com.example.awake.domain.model.ParseWarning

/** 刷新已存在课表；仓储保证请求和本地替换单飞且失败不覆盖旧数据。 */
class RefreshTimetableUseCase(private val remote: SchoolScheduleRouter) {
    suspend operator fun invoke(
        timetableId: Long,
        ownerConfirmed: Boolean = false,
        contentConfirmed: Boolean = false
    ): List<ParseWarning> = remote.import(
        timetableId,
        ownerConfirmed = ownerConfirmed,
        contentConfirmed = contentConfirmed
    )
}
