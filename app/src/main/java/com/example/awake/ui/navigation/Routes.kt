package com.example.awake.ui.navigation

object Routes {
    const val TIMETABLE = "timetable"
    const val SCHOOL_PICKER = "school-picker/{mode}"
    const val LOGIN = "login?school={school}&returnTo={returnTo}"
    const val TERM_IMPORT = "term-import?mode={mode}&school={school}"
    const val SETTINGS = "settings"
    const val COURSE_DETAIL = "course-detail/{courseId}"
    const val COURSE_EDITOR =
        "course-editor/{timetableId}/{dayOfWeek}/{startPeriod}?sectionId={sectionId}&masterId={masterId}"

    /** 学校选择页；mode 会透传给后续导入页。 */
    fun schoolPicker(mode: String) = "school-picker/$mode"

    /** 导入页模式：add = 添加新课表（可多选，一律新建）；overwrite = 覆盖当前课表（单选）。 */
    fun termImport(mode: String, school: String = "SCUT") =
        "term-import?mode=$mode&school=$school"

    fun login(school: String = "SCUT", returnTo: String = "import") =
        "login?school=$school&returnTo=$returnTo"

    fun courseDetail(courseId: Long) = "course-detail/$courseId"
    fun courseEditor(
        timetableId: Long,
        dayOfWeek: Int,
        startPeriod: Int,
        sectionId: Long = -1L,
        masterId: Long = -1L
    ) = "course-editor/$timetableId/$dayOfWeek/$startPeriod?sectionId=$sectionId&masterId=$masterId"
}
