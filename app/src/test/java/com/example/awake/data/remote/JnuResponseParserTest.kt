package com.example.awake.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JnuResponseParserTest {
    @Test
    fun `parse semesters filters unusable rows and groups by academic year`() {
        val raw = """
            {"code":"0","datas":{"xnxqcx":{"totalSize":65,"pageSize":999,"rows":[
              {"DM":"2026-2027-1","MC":"2026-2027学年 第1学期","XNDM":"2026-2027","XQDM":"1","SFSY":"1","PX":"1"},
              {"DM":"2026-2027-2","MC":"2026-2027学年 第2学期","XNDM":"2026-2027","XQDM":"2","SFSY":"1","PX":"2"},
              {"DM":"2025-2026-1","MC":"旧学期","XNDM":"2025-2026","XQDM":"1","SFSY":"0","PX":"1"}
            ]}}}
        """.trimIndent()

        val years = JnuResponseParser.parseSemesters(raw)

        assertEquals(1, years.size)
        assertEquals(2026, years.first().xnm)
        assertEquals("2026-2027", years.first().label)
        assertEquals(listOf("1", "2"), years.first().semesters.map { it.xqm })
    }

    @Test
    fun `parse schedule rows to scut payload expands week bitmap`() {
        val row = JSONObject(
            """
            {
              "KCM":"高等数学","SKJS":"张老师","JASMC":"教学楼101",
              "SKXQ":"2","KSJC":"3","JSJC":"4",
              "SKZC":"111000000000000000","ZCMC":"1-3周",
              "XF":"4","XS":"64","XH":"20260001","XM":"张三","KXH":"01"
            }
            """.trimIndent()
        )
        val payload = JnuResponseParser.toSchedulePayload(listOf(row))

        assertEquals("张三", payload.student?.name)
        assertEquals("20260001", payload.student?.studentId)
        assertEquals(1, payload.courses.size)
        val course = payload.courses.first()
        assertEquals("JNU_KB", course.source)
        assertEquals("高等数学", course.name)
        assertEquals("张老师", course.teacher)
        assertEquals("教学楼101", course.room)
        assertEquals(2, course.day)
        assertEquals("3-4", course.periods)
        assertEquals("1,2,3", course.weeks)
        assertTrue(course.className == "01")
    }
}