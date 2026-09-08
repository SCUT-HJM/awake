package com.example.awake.data.remote

import android.webkit.CookieManager
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 暨南大学教务会话存储：仅内存保存 jw.jnu.edu.cn 的会话 Cookie，
 * 与 SCUT 的 SessionCookieStore 完全隔离，登出互不影响。
 */
class JnuSessionStore {
    private val cookies = mutableListOf<Pair<String, String>>()
    @Volatile
    private var authenticated = false

    /** 从 WebView CookieManager 抓取 jw.jnu.edu.cn 当前可见的 Cookie（含 /jwapp 路径）。 */
    @Synchronized
    fun captureFromCookieManager(manager: CookieManager, extraPaths: List<String> = emptyList()) {
        (listOf("/", "/jwapp") + extraPaths).distinct().forEach { path ->
            val rawCookie = manager.getCookie("https://$JW_HOST$path")
            rawCookie
                ?.split(';')
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.forEach { item ->
                    val parts = item.split('=', limit = 2)
                    if (parts.size == 2 && parts[0].isNotBlank()) {
                        val cookie = parts[0].trim() to parts[1].trim()
                    if (cookies.none { it == cookie }) cookies.add(cookie)
                    }
                }
            val names = rawCookie
                ?.split(';')
                ?.map { it.trim().substringBefore('=') }
                .orEmpty()
            Log.d("AwakeJnuJw", "capture cookies path=$path names=${names.joinToString(",")}")
        }
        authenticated = cookies.any { it.first in AUTH_COOKIE_NAMES }
    }

    @Synchronized
    fun cookieHeader(): String = cookies.joinToString("; ") { (name, value) -> "$name=$value" }

    @Synchronized
    fun isAuthenticated(): Boolean = authenticated && cookies.isNotEmpty()

    @Synchronized
    fun clear() {
        cookies.clear()
        authenticated = false
    }

    companion object {
        const val JW_HOST = "jw.jnu.edu.cn"
        private val AUTH_COOKIE_NAMES = setOf("JSESSIONID", "CASTGC", "MOD_AMP_AUTH", "asessionid")
    }
}

/**
 * 暨南大学教务客户端。
 * - 学期列表：POST /jwapp/sys/wdkb/modules/jshkcb/xnxqcx.do（body: *order=-DM）
 * - 课表数据：POST /jwapp/sys/wdkb/modules/xskcb/xskcb.do（XNXQDM + 分页）
 * 课表行解析为与 SCUT 相同形状的 ScutCourseDto，周次由 SKZC 位图显式生成，
 * 复用现有 ScutScheduleMapper，无需新的映射器。
 */
class JnuJwClient(
    private val sessionStore: JnuSessionStore,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: HttpUrl = "https://$JNU_HOST/".toHttpUrl(),
    private val webApi: JnuWebApiBridge? = null
) {
    /** 是否已有可用教务会话。 */
    fun hasSession(): Boolean = sessionStore.isAuthenticated()


    /** 读取学期列表；返回按学年分组、最近学年在前，且只保留最近几年，避免列表过长。 */
    fun fetchAcademicTerms(): List<RemoteAcademicYear> {
        val body = SEMESTER_PATHS.firstNotNullOfOrNull { path ->
            runCatching { postForm(path, "*order=-DM") }.getOrNull()
        } ?: throw ScutHttpException(
            ScutHttpException.Kind.SESSION_EXPIRED, "教务会话已失效，请重新登录"
        )
        val years = JnuResponseParser.parseSemesters(body)
        if (years.isEmpty()) throw ScutHttpException(
            ScutHttpException.Kind.INVALID_RESPONSE, "教务系统暂未返回可用学期"
        )
        return years.take(RECENT_YEAR_LIMIT)
    }

    /** 按学期码（xnm=学年起始年，xqm=1/2/3）分页拉取全量课表行。 */
    fun fetchSchedule(xnm: Int, xqm: String): ScutSchedulePayload {
        val dm = "$xnm-${xnm + 1}-$xqm"
        val allRows = mutableListOf<JSONObject>()
        var pageNumber = 1
        while (pageNumber <= MAX_PAGE_COUNT) {
            val form = "XNXQDM=$dm&pageSize=$PAGE_SIZE&pageNumber=$pageNumber"
            val page = JnuResponseParser.parseSchedulePage(postForm(SCHEDULE_PATH, form))
            allRows += page.rows
            val fetched = allRows.size
            if (page.totalSize <= fetched || page.rows.isEmpty()) break
            pageNumber++
        }
        if (allRows.isEmpty()) {
            throw ScutHttpException(
                ScutHttpException.Kind.INVALID_RESPONSE, "教务系统未返回任何课程数据"
            )
        }
        return JnuResponseParser.toSchedulePayload(allRows)
    }

    private fun postForm(path: String, form: String): String {
        val cookieHeader = sessionStore.cookieHeader()
        if (cookieHeader.isBlank()) {
            throw ScutHttpException(ScutHttpException.Kind.SESSION_EXPIRED, "教务会话已失效，请重新登录")
        }
        val url = baseUrl.newBuilder().addEncodedPathSegments(path.removePrefix("/")).build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", baseUrl.toString().trimEnd('/'))
            .header("Referer", SCHEDULE_PAGE_URL)
            .header("User-Agent", USER_AGENT)
            .header("DNT", "1")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Cookie", cookieHeader)
            .post(form.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
            .build()
        Log.d(
            TAG,
            "jnu request path=$path form=$form cookieNames=${cookieHeader.split("; ").joinToString(",") { it.substringBefore("=") }} " +
                "headers=${request.headers.names().filter { it != "Cookie" }.joinToString(",")}"
        )
        webApi?.let { bridge ->
            val responseBody = bridge.post(path, form)
            Log.d(TAG, "jnu bridge response path=$path bodyPrefix=${responseBody.take(200).replace(Regex("\\s+"), " ")}")
            return responseBody
        }
        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                Log.d(
                    TAG,
                    "jnu response path=$path code=${response.code} type=${response.header("Content-Type")} " +
                        "location=${response.header("Location")} bodyPrefix=${responseBody.take(4000).replace(Regex("\\s+"), " ")}"
                )
                if (response.code in 300..399 || response.code == 401 || response.code == 403) {
                    throw ScutHttpException(ScutHttpException.Kind.SESSION_EXPIRED, "教务会话已失效，请重新登录")
                }
                if (response.code == 429) {
                    throw ScutHttpException(ScutHttpException.Kind.RATE_LIMITED, "请求过于频繁，请稍后再试")
                }
                if (!response.isSuccessful) {
                    throw ScutHttpException(ScutHttpException.Kind.SERVER, "教务系统暂时不可用（${response.code}）")
                }
                val text = responseBody
                if (text.isBlank()) {
                    throw ScutHttpException(ScutHttpException.Kind.INVALID_RESPONSE, "教务系统返回内容为空")
                }
                val normalized = text.lowercase()
                if (normalized.contains("请先登录") || normalized.contains("统一身份认证") ||
                    normalized.trimStart().startsWith("<html")
                ) {
                    throw ScutHttpException(ScutHttpException.Kind.SESSION_EXPIRED, "教务会话已失效，请重新登录")
                }
                return text
            }
        } catch (error: ScutHttpException) {
            throw error
        } catch (error: java.io.IOException) {
            Log.w(TAG, "jnu request failed path=$path type=${error.javaClass.simpleName}")
            throw ScutHttpException(ScutHttpException.Kind.NETWORK, "网络连接失败，请检查网络后重试", error)
        }
    }

    companion object {
        private const val TAG = "AwakeJnuJw"
        private const val JNU_HOST = JnuSessionStore.JW_HOST
        private val SEMESTER_PATHS = listOf(
            "/jwapp/sys/wdkb/modules/jshkcb/xnxqcx.do",
            "/jwapp/sys/wdkb/modules/xskcb/xnxqcx.do"
        )
        private const val SCHEDULE_PATH = "/jwapp/sys/wdkb/modules/xskcb/xskcb.do"
        private const val SCHEDULE_PAGE_URL =
            "https://jw.jnu.edu.cn/jwapp/sys/wdkb/*default/index.do?t_s=1788708192491" +
            "&amp_sec_version_=1&gid_=QmhOSm9WL0dTSlc1SHgwTmw3eVBtWWFsalIvSDV2TlpORUtlWU1sMTVIMzZGaG5TRTRxNDB6QzZQSFlTSWc2WFcwUmFVZmNYV2tXMUg2dWxWWU1XeWc9PQ" +
            "&EMAP_LANG=zh&THEME=cherry#/xskcb"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Edg/152.0.0.0 Mobile Safari/537.36"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGE_COUNT = 5
        private const val RECENT_YEAR_LIMIT = 4

        private fun defaultClient() = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/** 暨大课表响应的纯解析逻辑，独立于网络便于单元测试。 */
object JnuResponseParser {
    data class SchedulePage(val rows: List<JSONObject>, val totalSize: Int)

    /** 学期列表：datas.xnxqcx.rows，按 XNDM 分组；xnm 取学年首年。 */
    fun parseSemesters(raw: String): List<RemoteAcademicYear> {
        val rows = rowsOf(raw, "xnxqcx")
        data class Row(val xnm: Int, val order: Int, val xqm: String, val label: String)
        val parsed = rows.mapNotNull { row ->
            val dm = row.optString("DM").trim()
            if (row.optInt("SFSY", 0) != 1 || dm.isBlank()) return@mapNotNull null
            val xnm = dm.substringBefore('-').toIntOrNull() ?: return@mapNotNull null
            val xqm = row.optString("XQDM").trim().ifBlank { dm.substringAfterLast('-') }
            Row(xnm, row.optInt("PX", 0), xqm, shortSemesterLabel(xqm))
        }
        return parsed
            .groupBy { it.xnm }
            .map { (xnm, list) ->
                RemoteAcademicYear(
                    xnm = xnm,
                    label = "$xnm-${xnm + 1}",
                    semesters = list.sortedBy { it.order }
                        .distinctBy { it.xqm }
                        .map { RemoteSemester(it.xqm, it.label) }
                )
            }
            .sortedByDescending { it.xnm }
    }

    /** 课表分页：datas.xskcb.rows + totalSize。 */
    fun parseSchedulePage(raw: String): SchedulePage {
        val module = moduleOf(raw, "xskcb")
        val array = module.optJSONArray("rows")
            ?: throw IllegalArgumentException("响应缺少 rows 课程字段")
        val rows = buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { add(it) }
            }
        }
        return SchedulePage(rows, module.optInt("totalSize", rows.size))
    }

    /** 把解析好的行转换为与 SCUT 相同形状的课表载荷。 */
    fun toSchedulePayload(rows: List<JSONObject>): ScutSchedulePayload {
        val first = rows.firstOrNull()
        val student = first?.let {
            val studentId = it.optStringOrNull("XH")
            val name = it.optStringOrNull("XM")
            if (studentId.isNullOrBlank() && name.isNullOrBlank()) null
            else ScutStudentDto(studentId, name)
        }
        val courses = rows.mapNotNull { row ->
            val name = row.optStringOrNull("KCM").orEmpty().trim()
            if (name.isBlank()) return@mapNotNull null
            val sksj = row.optStringOrNull("SKSJ").orEmpty()
            val day = row.optStringOrNull("SKXQ")?.toIntOrNull()?.takeIf { d -> d in 1..7 }
                ?: dayFromName(sksj)
            val start = row.optStringOrNull("KSJC")?.toIntOrNull()
                ?: periodRange(sksj).first
            val end = row.optStringOrNull("JSJC")?.toIntOrNull()
                ?: periodRange(sksj).second
            ScutCourseDto(
                source = "JNU_KB",
                name = name,
                teacher = row.optStringOrNull("SKJS").orEmpty(),
                room = row.optStringOrNull("JASMC").orEmpty(),
                day = day,
                dayName = dayNameText(day),
                periods = if (start in 1..15 && end >= start) "$start-$end" else "",
                weeks = weeksFromBitmap(row.optStringOrNull("SKZC"))
                    ?: row.optStringOrNull("ZCMC").orEmpty(),
                credits = row.optStringOrNull("XF"),
                hours = row.optStringOrNull("XS"),
                courseType = row.optStringOrNull("XDLX_DISPLAY"),
                assessment = null,
                className = row.optStringOrNull("KXH") ?: row.optStringOrNull("JXBID")
            )
        }
        return ScutSchedulePayload(student, courses)
    }

    /** SKZC 周次位图：第 i 个字符为 1 表示第 i+1 周有课。 */
    private fun weeksFromBitmap(bitmap: String?): String? {
        val bits = bitmap.orEmpty()
        if (!bits.all { it == '0' || it == '1' } || bits.none { it == '1' }) return null
        return bits.mapIndexedNotNull { index, bit ->
            if (bit == '1') (index + 1).toString() else null
        }.joinToString(",").ifBlank { null }
    }

    private fun rowsOf(raw: String, module: String): List<JSONObject> =
        moduleOf(raw, module).let { moduleObject ->
            val array = moduleObject.optJSONArray("rows")
                ?: throw IllegalArgumentException("响应缺少 rows 字段")
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(it) }
                }
            }
        }

    private fun moduleOf(raw: String, module: String): JSONObject {
        val root = JSONObject(raw)
        if (root.optString("code") != "0" && root.optString("code").isNotBlank()) {
            val message = root.optJSONObject("datas")
                ?.optJSONObject(module)
                ?.optJSONObject("extParams")
                ?.optStringOrNull("msg")
            throw IllegalArgumentException(message ?: "教务系统返回错误")
        }
        val moduleObject = root.optJSONObject("datas")?.optJSONObject(module)
            ?: throw IllegalArgumentException("响应缺少 $module 字段")
        moduleObject.optJSONObject("extParams")?.takeIf { it.optString("code") != "1" && it.has("code") }
            ?.let { ext ->
                throw IllegalArgumentException(ext.optStringOrNull("msg") ?: "教务系统返回错误")
            }
        return moduleObject
    }

    /** 从 SKSJ 提取“第3-4节”式节次，兜底 KSJC/JSJC 缺失。 */
    private fun periodRange(text: String): Pair<Int, Int> {
        val match = Regex("第(\\d+)(?:-(\\d+))?节").find(text)
        val start = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val end = match?.groupValues?.get(2)?.toIntOrNull() ?: start
        return start to end
    }

    private fun dayFromName(text: String): Int = when {
        text.contains("一") -> 1
        text.contains("二") -> 2
        text.contains("三") -> 3
        text.contains("四") -> 4
        text.contains("五") -> 5
        text.contains("六") -> 6
        text.contains("日") || text.contains("天") -> 7
        else -> 0
    }

    private fun dayNameText(day: Int): String = when (day) {
        1 -> "星期一"
        2 -> "星期二"
        3 -> "星期三"
        4 -> "星期四"
        5 -> "星期五"
        6 -> "星期六"
        7 -> "星期日"
        else -> ""
    }

    private fun shortSemesterLabel(xqm: String): String = when (xqm) {
        "1" -> "第1学期"
        "2" -> "第2学期"
        "3" -> "暑期"
        else -> "学期 $xqm"
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return opt(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }
}




