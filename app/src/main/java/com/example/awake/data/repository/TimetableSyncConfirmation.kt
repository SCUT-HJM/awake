package com.example.awake.data.repository

/** 同步前需要用户确认的信息；UI 根据标记展示账号/内容确认文案。 */
data class TimetableSyncConfirmationRequest(
    val timetableId: Long,
    val ownerRequired: Boolean,
    val contentRequired: Boolean,
    val ownerStudentIdMasked: String? = null,
    val ownerStudentName: String? = null
)

/** 抛出时本地课程尚未替换；用户确认后重新发起本次同步即可。 */
class TimetableSyncConfirmationRequired(
    val request: TimetableSyncConfirmationRequest
) : IllegalStateException("同步需要用户确认")
