package com.example.awake.data.local

import com.example.awake.domain.model.SchoolCode

/** 上课时间配置目标：学校级；暨南大学进一步区分校区。 */
enum class PeriodConfigTarget(
    val scope: Long,
    val displayName: String,
    val school: SchoolCode,
    val campus: JnuCampus?
) {
    SCUT(PeriodConfigScopes.SCHOOL_SCOPE_BASE - SchoolCode.SCUT.ordinal, "华南理工大学", SchoolCode.SCUT, null),
    JNU_MAIN(PeriodConfigScopes.SCHOOL_SCOPE_BASE - SchoolCode.JNU.ordinal, "暨南大学（本部校区）", SchoolCode.JNU, JnuCampus.MAIN),
    JNU_PANYU(-1_000_002L, "暨南大学（番禺校区）", SchoolCode.JNU, JnuCampus.PANYU);

    val defaultPeriodCount: Int
        get() = defaultConfigs.size

    /** 目标 scope 还没保存过配置时的内置时间。 */
    val defaultConfigs: List<PeriodConfigEntity>
        get() = when (this) {
            SCUT -> PeriodConfigDefaults.entities()
            JNU_MAIN -> JnuCampus.MAIN.configs
            JNU_PANYU -> JnuCampus.PANYU.configs
        }
}

/**
 * 学校级节次时间的存储作用域。
 *
 * period_configs 主键仍是 (timetableId, period)；学校配置复用这张表，但使用固定负数 ID，
 * 不会和真实课表 ID（Room autoincrement 从 1 开始）冲突。JNU 的旧 school scope 继续作为本部，
 * 番禺使用独立 scope，避免破坏已有配置。
 */
object PeriodConfigScopes {
    const val SCHOOL_SCOPE_BASE = -1_000_000L

    fun scopeFor(school: SchoolCode): Long = SCHOOL_SCOPE_BASE - school.ordinal

    @Deprecated("请使用 PeriodConfigTarget.scope")
    fun scopeFor(schoolCode: String): Long {
        val school = SchoolCode.entries.firstOrNull { it.code == schoolCode }
            ?: error("不支持的节次时间学校：$schoolCode")
        return scopeFor(school)
    }

    fun scopeFor(target: PeriodConfigTarget): Long = target.scope

    fun scopeFor(campus: JnuCampus): Long {
        return PeriodConfigTarget.entries.first { it.campus == campus }.scope
    }

    fun targetFor(
        schoolCode: String,
        campusCode: String?,
        periodTargetCode: String?
    ): PeriodConfigTarget {
        periodTargetCode?.let { code ->
            PeriodConfigTarget.entries.firstOrNull { it.name == code }?.let { return it }
        }
        return targetFor(schoolCode, campusCode)
    }

    fun targetFor(schoolCode: String, campusCode: String?): PeriodConfigTarget {
        val school = SchoolCode.entries.firstOrNull { it.code == schoolCode } ?: return PeriodConfigTarget.SCUT
        return when (school) {
            SchoolCode.SCUT -> PeriodConfigTarget.SCUT
            SchoolCode.JNU -> JnuCampus.entries.firstOrNull { it.name == campusCode }
                ?.let { PeriodConfigTarget.entries.first { target -> target.campus == it } }
                ?: PeriodConfigTarget.JNU_MAIN
        }
    }

    val supportedTargets: List<PeriodConfigTarget> get() = PeriodConfigTarget.entries.toList()
    @Deprecated("设置页已改用校区级目标，请使用 supportedTargets")
    val supportedSchools: List<SchoolCode> get() = SchoolCode.entries.toList()
}

