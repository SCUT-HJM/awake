package com.example.awake.data.local

import android.content.ContentValues
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.awake.domain.model.CourseIdentity

@Database(
    entities = [
        ProfileEntity::class,
        TimetableEntity::class,
        CourseEntity::class,
        CourseSectionEntity::class,
        CourseWeekEntity::class,
        PeriodConfigEntity::class
    ],
    version = 14,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun timetableDao(): TimetableDao
    abstract fun courseDao(): CourseDao
    abstract fun periodConfigDao(): PeriodConfigDao

    companion object {
        /** 旧 CourseDBHelper 使用 user_version=2；迁移保留旧课程，再切换到规范化 Room schema。 */
        val LEGACY_MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS profiles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        schoolCode TEXT NOT NULL,
                        maskedStudentId TEXT,
                        displayName TEXT,
                        lastLoginAt INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS timetables (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        profileId INTEGER NOT NULL,
                        schoolCode TEXT NOT NULL,
                        campusCode TEXT NOT NULL DEFAULT '' ,
                        xnm INTEGER NOT NULL,
                        xqm TEXT NOT NULL,
                        label TEXT NOT NULL,
                        startDate TEXT,
                        totalWeeks INTEGER NOT NULL,
                        lastSyncedAt INTEGER,
                        FOREIGN KEY(profileId) REFERENCES profiles(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_timetables_profileId_xnm_xqm " +
                        "ON timetables(profileId, xnm, xqm)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS courses_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timetableId INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        remoteKey TEXT NOT NULL,
                        name TEXT NOT NULL,
                        teacher TEXT NOT NULL,
                        room TEXT NOT NULL,
                        dayOfWeek INTEGER NOT NULL,
                        startPeriod INTEGER NOT NULL,
                        endPeriod INTEGER NOT NULL,
                        credits TEXT,
                        totalHours TEXT,
                        courseType TEXT,
                        assessment TEXT,
                        className TEXT,
                        color INTEGER NOT NULL,
                        rawWeekText TEXT NOT NULL,
                        FOREIGN KEY(timetableId) REFERENCES timetables(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO profiles(schoolCode, maskedStudentId, displayName, lastLoginAt) " +
                        "SELECT 'SCUT', NULL, '历史数据', NULL " +
                        "WHERE NOT EXISTS (SELECT 1 FROM profiles)"
                )
                db.execSQL(
                    "INSERT INTO timetables(profileId, schoolCode, xnm, xqm, label, startDate, totalWeeks, lastSyncedAt) " +
                        "SELECT (SELECT id FROM profiles ORDER BY id LIMIT 1), 'SCUT', 0, 'legacy', '历史手工课表', NULL, 30, NULL " +
                        "WHERE NOT EXISTS (SELECT 1 FROM timetables WHERE xnm = 0 AND xqm = 'legacy')"
                )
                db.execSQL(
                    """
                    INSERT INTO courses_new(
                        id, timetableId, source, remoteKey, name, teacher, room,
                        dayOfWeek, startPeriod, endPeriod, credits, totalHours,
                        courseType, assessment, className, color, rawWeekText
                    )
                    SELECT
                        id,
                        (SELECT id FROM timetables WHERE xnm = 0 AND xqm = 'legacy' ORDER BY id LIMIT 1),
                        'MIGRATED_LEGACY',
                        'legacy_' || id,
                        COALESCE(name, ''),
                        COALESCE(teacher, ''),
                        COALESCE(room, ''),
                        COALESCE(day, 1),
                        COALESCE(start, 1),
                        COALESCE(end, COALESCE(start, 1)),
                        NULL,
                        NULL,
                        NULL,
                        NULL,
                        NULL,
                        COALESCE(color, 0),
                        COALESCE(week_config, '')
                    FROM courses
                    WHERE NOT EXISTS (
                        SELECT 1 FROM courses_new existing
                        WHERE existing.remoteKey = 'legacy_' || courses.id
                    )
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS courses")
                db.execSQL("ALTER TABLE courses_new RENAME TO courses")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_courses_timetableId_source_remoteKey " +
                        "ON courses(timetableId, source, remoteKey)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_courses_timetableId ON courses(timetableId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS course_weeks (
                        courseId INTEGER NOT NULL,
                        weekNumber INTEGER NOT NULL,
                        PRIMARY KEY(courseId, weekNumber),
                        FOREIGN KEY(courseId) REFERENCES courses(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_course_weeks_weekNumber ON course_weeks(weekNumber)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS period_configs (
                        period INTEGER NOT NULL PRIMARY KEY,
                        startTime TEXT NOT NULL,
                        endTime TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** 允许同一学期建立多个独立课表，用于导入时的“新建”选项。 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_timetables_profileId_xnm_xqm")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_timetables_profileId_xnm_xqm " +
                        "ON timetables(profileId, xnm, xqm)"
                )
            }
        }

        /**
         * v5 重构：courses 拆成「课程主记录」+「course_sections 时段」。
         * 旧的一行（课程+时段合一）迁移为一个主课程和一个时段；
         * 同 timetable 内「课程名 + 教学班号（缺失时退化为教师）」相同的旧行合并到同一个主课程。
         * 时段 remoteKey 沿用旧的行级 remoteKey，保证历史时段与新导入时段可以对齐。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses RENAME TO courses_v4")
                db.execSQL("ALTER TABLE course_weeks RENAME TO course_weeks_v4")

                // SQLite 保留被重命名表上的索引名称；如果不先删除，下面的
                // CREATE INDEX IF NOT EXISTS 会因为同名而跳过，最终旧表删除后新表没有索引。
                db.execSQL("DROP INDEX IF EXISTS `index_courses_timetableId`")
                db.execSQL("DROP INDEX IF EXISTS `index_courses_timetableId_source_remoteKey`")
                db.execSQL("DROP INDEX IF EXISTS `index_course_weeks_weekNumber`")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `courses` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timetableId` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `remoteKey` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `teacher` TEXT NOT NULL,
                        `credits` TEXT,
                        `totalHours` TEXT,
                        `courseType` TEXT,
                        `assessment` TEXT,
                        `className` TEXT,
                        `color` INTEGER NOT NULL,
                        FOREIGN KEY(`timetableId`) REFERENCES `timetables`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_courses_timetableId` ON `courses`(`timetableId`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_courses_timetableId_source_remoteKey " +
                        "ON `courses`(`timetableId`, `source`, `remoteKey`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `course_sections` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `courseId` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `remoteKey` TEXT NOT NULL,
                        `dayOfWeek` INTEGER NOT NULL,
                        `startPeriod` INTEGER NOT NULL,
                        `endPeriod` INTEGER NOT NULL,
                        `room` TEXT NOT NULL,
                        `teacher` TEXT NOT NULL,
                        `rawWeekText` TEXT NOT NULL,
                        `locked` INTEGER NOT NULL,
                        FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_course_sections_courseId` ON `course_sections`(`courseId`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_course_sections_courseId_source_remoteKey " +
                        "ON `course_sections`(`courseId`, `source`, `remoteKey`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `course_weeks` (
                        `sectionId` INTEGER NOT NULL,
                        `weekNumber` INTEGER NOT NULL,
                        PRIMARY KEY(`sectionId`, `weekNumber`),
                        FOREIGN KEY(`sectionId`) REFERENCES `course_sections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_course_weeks_weekNumber` ON `course_weeks`(`weekNumber`)")

                val sectionIdByOldCourseId = HashMap<Long, Long>()

                db.query(
                    """
                    SELECT id, timetableId, source, remoteKey, name, teacher, room,
                           dayOfWeek, startPeriod, endPeriod, credits, totalHours,
                           courseType, assessment, className, color, rawWeekText
                    FROM courses_v4 ORDER BY id
                    """.trimIndent()
                ).use { cursor ->
                    // masterKey → (主课程 id, 已登记的教师)；教师用于合并时补齐主记录缺失字段。
                    val masterByCourseKey = LinkedHashMap<String, Pair<Long, String?>>()

                    fun lastRowId(): Long = db.query("SELECT last_insert_rowid()").use { it.moveToFirst(); it.getLong(0) }

                    while (cursor.moveToNext()) {
                        val oldId = cursor.getLong(0)
                        val timetableId = cursor.getLong(1)
                        val source = cursor.getString(2) ?: ""
                        val oldRemoteKey = cursor.getString(3) ?: ""
                        val name = cursor.getString(4) ?: ""
                        val teacher = cursor.getString(5) ?: ""
                        val room = cursor.getString(6) ?: ""
                        val dayOfWeek = cursor.getInt(7)
                        val startPeriod = cursor.getInt(8)
                        val endPeriod = cursor.getInt(9)
                        val credits = if (cursor.isNull(10)) null else cursor.getString(10)
                        val totalHours = if (cursor.isNull(11)) null else cursor.getString(11)
                        val courseType = if (cursor.isNull(12)) null else cursor.getString(12)
                        val assessment = if (cursor.isNull(13)) null else cursor.getString(13)
                        val className = if (cursor.isNull(14)) null else cursor.getString(14)
                        val color = cursor.getInt(15)
                        val rawWeekText = cursor.getString(16) ?: ""

                        val masterKey = CourseIdentity.masterKey(source, name, className, teacher)
                        val existing = masterByCourseKey[masterKey]
                        val courseId: Long
                        if (existing == null) {
                            val values = ContentValues().apply {
                                put("timetableId", timetableId)
                                put("source", source)
                                put("remoteKey", masterKey)
                                put("name", name)
                                put("teacher", teacher)
                                if (credits != null) put("credits", credits) else putNull("credits")
                                if (totalHours != null) put("totalHours", totalHours) else putNull("totalHours")
                                if (courseType != null) put("courseType", courseType) else putNull("courseType")
                                if (assessment != null) put("assessment", assessment) else putNull("assessment")
                                if (className != null) put("className", className) else putNull("className")
                                put("color", color)
                            }
                            db.insert(
                                "courses",
                                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                                values
                            )
                            courseId = lastRowId()
                            masterByCourseKey[masterKey] = courseId to teacher.takeIf { it.isNotBlank() }
                        } else {
                            courseId = existing.first
                            if (existing.second.isNullOrBlank() && teacher.isNotBlank()) {
                                masterByCourseKey[masterKey] = existing.copy(second = teacher)
                                db.execSQL(
                                    "UPDATE courses SET teacher = ? WHERE id = ?",
                                    arrayOf<Any>(teacher, courseId)
                                )
                            }
                        }

                        db.execSQL(
                            """
                            INSERT INTO course_sections(
                                courseId, source, remoteKey, dayOfWeek, startPeriod, endPeriod,
                                room, teacher, rawWeekText, locked
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                            """.trimIndent(),
                            arrayOf<Any>(
                                courseId, source, oldRemoteKey, dayOfWeek, startPeriod,
                                endPeriod, room, teacher, rawWeekText
                            )
                        )
                        sectionIdByOldCourseId[oldId] = lastRowId()
                    }
                }

                // 周次关系从旧课程行搬到对应的新时段行。
                db.query("SELECT courseId, weekNumber FROM course_weeks_v4").use { cursor ->
                    while (cursor.moveToNext()) {
                        val oldCourseId = cursor.getLong(0)
                        val weekNumber = cursor.getInt(1)
                        val sectionId = sectionIdByOldCourseId[oldCourseId] ?: continue
                        db.execSQL(
                            "INSERT OR REPLACE INTO course_weeks(sectionId, weekNumber) VALUES (?, ?)",
                            arrayOf<Any>(sectionId, weekNumber)
                        )
                    }
                }

                db.execSQL("DROP TABLE IF EXISTS course_weeks_v4")
                db.execSQL("DROP TABLE IF EXISTS courses_v4")
            }
        }

        /**
         * v6：period_configs 增加 timetableId 维度（0 = 全局默认）。
         * 每个课表可保存独立节次时间，切换课表时段设置跟随课表。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `period_configs_new` (
                        `period` INTEGER NOT NULL,
                        `startTime` TEXT NOT NULL,
                        `endTime` TEXT NOT NULL,
                        `timetableId` INTEGER NOT NULL,
                        PRIMARY KEY(`timetableId`, `period`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO period_configs_new(period, startTime, endTime, timetableId) " +
                        "SELECT period, startTime, endTime, 0 FROM period_configs"
                )
                db.execSQL("DROP TABLE period_configs")
                db.execSQL("ALTER TABLE period_configs_new RENAME TO period_configs")

                // 修复早期 v5 数据库可能缺少的索引，并覆盖 4→5 迁移中
                // 因 SQLite 重命名索引同名冲突而未创建成功的情况。
                db.execSQL("DROP INDEX IF EXISTS `index_courses_timetableId`")
                db.execSQL("CREATE INDEX `index_courses_timetableId` ON `courses`(`timetableId`)")
                db.execSQL("DROP INDEX IF EXISTS `index_courses_timetableId_source_remoteKey`")
                db.execSQL(
                    "CREATE UNIQUE INDEX `index_courses_timetableId_source_remoteKey` " +
                        "ON `courses`(`timetableId`, `source`, `remoteKey`)"
                )
                db.execSQL("DROP INDEX IF EXISTS `index_course_sections_courseId`")
                db.execSQL("CREATE INDEX `index_course_sections_courseId` ON `course_sections`(`courseId`)")
                db.execSQL("DROP INDEX IF EXISTS `index_course_sections_courseId_source_remoteKey`")
                db.execSQL(
                    "CREATE UNIQUE INDEX `index_course_sections_courseId_source_remoteKey` " +
                        "ON `course_sections`(`courseId`, `source`, `remoteKey`)"
                )
                db.execSQL("DROP INDEX IF EXISTS `index_course_weeks_weekNumber`")
                db.execSQL("CREATE INDEX `index_course_weeks_weekNumber` ON `course_weeks`(`weekNumber`)")
            }
        }

        /**
         * v7：period_configs 增加放空标记。放空节次仍保留编号与占位，
         * 课程跨过该节次时课表仍能连续显示。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE period_configs ADD COLUMN isEmpty INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v8：isEmpty 改为 emptyType，可区分午休和晚休。
         * 旧数据中的 isEmpty=1 迁移为午休。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `period_configs_new` (
                        `period` INTEGER NOT NULL,
                        `startTime` TEXT NOT NULL,
                        `endTime` TEXT NOT NULL,
                        `emptyType` TEXT NOT NULL,
                        `timetableId` INTEGER NOT NULL,
                        PRIMARY KEY(`timetableId`, `period`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO period_configs_new(period, startTime, endTime, emptyType, timetableId)
                    SELECT period, startTime, endTime,
                           CASE WHEN isEmpty = 1 THEN 'LUNCH' ELSE '' END,
                           timetableId
                    FROM period_configs
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE period_configs")
                db.execSQL("ALTER TABLE period_configs_new RENAME TO period_configs")
            }
        }

        /** v12：修正旧版本预填的暨大本部默认作息；仅清理旧的 11 节错误默认，不覆盖用户自定义。 */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    DELETE FROM period_configs
                    WHERE timetableId = -1000001
                      AND (
                        SELECT COUNT(*) FROM period_configs old
                        WHERE old.timetableId = -1000001
                      ) = 11
                      AND EXISTS (
                        SELECT 1 FROM period_configs p
                        WHERE p.timetableId = -1000001
                          AND p.period = 1 AND p.startTime = '08:50' AND p.endTime = '09:35'
                      )
                      AND EXISTS (
                        SELECT 1 FROM period_configs p
                        WHERE p.timetableId = -1000001
                          AND p.period = 11 AND p.startTime = '20:50' AND p.endTime = '21:35'
                      )
                    """.trimIndent()
                )
            }
        }

        /** v13：课表记录属主学校/脱敏学号，同步前校验当前登录账号。 */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE timetables ADD COLUMN ownerSchoolCode TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE timetables ADD COLUMN ownerStudentIdMasked TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /** v14：记住上次同步内容确认时的两侧指纹，避免内容未变化时重复打扰。 */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE timetables ADD COLUMN syncConfirmedLocalHash TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE timetables ADD COLUMN syncConfirmedRemoteHash TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /** v11：允许课表显式选择任意一套上课时间，而不是只能按学校/校区自动匹配。 */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timetables ADD COLUMN periodTargetCode TEXT")
            }
        }

        /** v10：课表记录校区，用于暨大本部/番禺匹配不同的上课时间 scope。 */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE timetables ADD COLUMN campusCode TEXT NOT NULL DEFAULT ''"
                )
                // 旧的番禺导入没有持久化校区，这里按其“10 节 + 缺 5/9”的配置推断；其余暨大课表归为本部。
                db.execSQL(
                    """
                    UPDATE timetables SET campusCode = 'PANYU'
                    WHERE schoolCode = 'JNU' AND (
                        SELECT COUNT(*) FROM period_configs pc
                        WHERE pc.timetableId = timetables.id
                    ) = 10 AND NOT EXISTS (
                        SELECT 1 FROM period_configs pc
                        WHERE pc.timetableId = timetables.id AND pc.period IN (5, 9)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "UPDATE timetables SET campusCode = 'MAIN' WHERE schoolCode = 'JNU' AND campusCode = ''"
                )
            }
        }

        /** v9：增加 customLabel 字段，允许用户自定义午休/晚休显示名称。 */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE period_configs ADD COLUMN customLabel TEXT NOT NULL DEFAULT ''"
                )
            }
        }
    }
}
